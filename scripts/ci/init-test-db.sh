#!/usr/bin/env bash
# Only for a fresh, disposable GitHub runner service (or explicitly simulated CI).
set +x
set -euo pipefail

fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ ${CI:-} == true && ${GITHUB_ACTIONS:-} == true ]] || fail 'CI and GITHUB_ACTIONS must both be true.'
[[ ${PGHOST:-} == 127.0.0.1 ]] || fail 'Only the explicit IPv4 loopback host is permitted.'
[[ ${PGPORT:-} =~ ^[0-9]{4,5}$ ]] || fail 'An explicit isolated PostgreSQL port is required.'
(( 10#$PGPORT >= 1024 && 10#$PGPORT <= 65535 && 10#$PGPORT != 5432 )) || fail 'The PostgreSQL port must be isolated from the default local server.'
[[ ${TEST_DB_USERNAME:-} == chagok_test ]] || fail 'Only the chagok_test application role is permitted.'
[[ ${TEST_DB_URL:-} == "jdbc:postgresql://127.0.0.1:$PGPORT/reconciliation_test" ]] || fail 'TEST_DB_URL must select the isolated reconciliation_test database exactly.'
[[ -n ${CI_DB_ADMIN_PASSWORD:-} && -n ${TEST_DB_PASSWORD:-} ]] || fail 'Both disposable passwords must be supplied.'
[[ $CI_DB_ADMIN_PASSWORD != "$TEST_DB_PASSWORD" ]] || fail 'Administrator and application passwords must differ.'

# Explicit arguments plus removal of alternate libpq routing prevent service or
# hostaddr settings inherited from a developer shell from changing the target.
unset PGSERVICE PGSERVICEFILE PGHOSTADDR PGOPTIONS
export PGCONNECT_TIMEOUT=5 PGSSLMODE=disable PGPASSFILE=/dev/null
export PGPASSWORD=$CI_DB_ADMIN_PASSWORD
psql --no-psqlrc --no-password --host=127.0.0.1 --port="$PGPORT" \
    --username=postgres --dbname=postgres --set=ON_ERROR_STOP=1 <<'SQL'
\set VERBOSITY terse
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
DO $$
BEGIN
    IF current_database() <> 'postgres' OR current_user <> 'postgres'
       OR current_setting('server_version_num')::integer / 10000 <> 17 THEN
        RAISE EXCEPTION 'Expected the disposable PostgreSQL 17 bootstrap service';
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'chagok_test')
       OR EXISTS (SELECT FROM pg_database WHERE datname = 'reconciliation_test') THEN
        RAISE EXCEPTION 'Existing or partially initialized CI database refused';
    END IF;
    IF EXISTS (SELECT FROM pg_database WHERE NOT datistemplate AND datname <> 'postgres') THEN
        RAISE EXCEPTION 'Expected an empty disposable service, not an existing database server';
    END IF;
END
$$;
\getenv app_password TEST_DB_PASSWORD
CREATE ROLE chagok_test LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD :'app_password';
CREATE DATABASE reconciliation_test OWNER chagok_test;
REVOKE ALL ON DATABASE reconciliation_test FROM PUBLIC;
SQL
unset PGPASSWORD CI_DB_ADMIN_PASSWORD

export PGPASSWORD=$TEST_DB_PASSWORD
psql --no-psqlrc --no-password --host=127.0.0.1 --port="$PGPORT" \
    --username=chagok_test --dbname=reconciliation_test --set=ON_ERROR_STOP=1 <<'SQL'
\set VERBOSITY terse
DO $$
BEGIN
    IF current_database() <> 'reconciliation_test' OR current_user <> 'chagok_test'
       OR EXISTS (SELECT FROM pg_roles WHERE rolname = current_user
                  AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit)) THEN
        RAISE EXCEPTION 'Unexpected test database identity or role privileges';
    END IF;
END
$$;
SQL
unset PGPASSWORD
printf '%s\n' 'Initialized the isolated test database with a non-administrator role.'

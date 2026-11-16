#!/bin/sh
set -eu
case "${APP_DB_USER:-}:${APP_DB_NAME:-}" in
    chagok_dev:reconciliation_dev|chagok_test:reconciliation_test) ;;
    *) echo 'Unexpected application database/user pair.' >&2; exit 1 ;;
esac
[ -n "${APP_DB_PASSWORD:-}" ] || { echo 'Application database password is missing.' >&2; exit 1; }

# getenv avoids putting the password in argv. psql quotes both identifiers and
# literals; do not interpolate shell values into SQL text.
psql --no-psqlrc --username "$POSTGRES_USER" --dbname postgres \
    --set=ON_ERROR_STOP=1 <<'SQL'
\set VERBOSITY terse
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
\getenv app_user APP_DB_USER
\getenv app_db APP_DB_NAME
\getenv app_password APP_DB_PASSWORD
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD :'app_password';
CREATE DATABASE :"app_db" OWNER :"app_user";
REVOKE ALL ON DATABASE :"app_db" FROM PUBLIC;
SQL
unset APP_DB_PASSWORD

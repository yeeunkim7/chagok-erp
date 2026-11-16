#!/bin/sh
set -eu
secret_file=${TEST_DB_PASSWORD_FILE:-/run/secrets/app_test}
if [ ! -r "$secret_file" ] || [ ! -s "$secret_file" ]; then
    echo 'Test database password file is missing, unreadable, or empty.' >&2
    exit 1
fi
TEST_DB_PASSWORD=$(cat "$secret_file")
[ -n "$TEST_DB_PASSWORD" ] || { echo 'Test database password is empty.' >&2; exit 1; }
export TEST_DB_PASSWORD
AI_API_KEY= APP_AI_ENABLED=false
export AI_API_KEY APP_AI_ENABLED
if [ "$(id -u)" = 0 ]; then
    exec setpriv --reuid=10001 --regid=10001 --init-groups \
        ./gradlew --offline --no-daemon --console=plain "$@"
fi
[ "$(id -u)" = 10001 ] || { echo 'Tests must run as UID 10001.' >&2; exit 1; }
exec ./gradlew --offline --no-daemon --console=plain "$@"

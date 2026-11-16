#!/bin/sh
set -eu
secret_file=${DB_PASSWORD_FILE:-/run/secrets/app_dev}
if [ ! -r "$secret_file" ] || [ ! -s "$secret_file" ]; then
    echo 'Development database password file is missing, unreadable, or empty.' >&2
    exit 1
fi
DB_PASSWORD=$(cat "$secret_file")
[ -n "$DB_PASSWORD" ] || { echo 'Development database password is empty.' >&2; exit 1; }
export DB_PASSWORD
# Never inherit an API key, even when the image is launched outside Compose.
AI_API_KEY= APP_AI_ENABLED=false
export AI_API_KEY APP_AI_ENABLED
if [ "$(id -u)" = 0 ]; then
    exec setpriv --reuid=10001 --regid=10001 --init-groups "$@"
fi
[ "$(id -u)" = 10001 ] || { echo 'Application must run as UID 10001.' >&2; exit 1; }
exec "$@"

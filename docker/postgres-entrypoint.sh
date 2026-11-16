#!/bin/sh
set -eu
# Local Compose file secrets retain their host UID/mode. Read 0600 files before
# the official entrypoint drops from root to the postgres OS user.
secret_file=${APP_DB_PASSWORD_FILE:?APP_DB_PASSWORD_FILE is required}
if [ ! -r "$secret_file" ] || [ ! -s "$secret_file" ]; then
    echo 'Application database password file is missing, unreadable, or empty.' >&2
    exit 1
fi
APP_DB_PASSWORD=$(cat "$secret_file")
[ -n "$APP_DB_PASSWORD" ] || { echo 'Application database password is empty.' >&2; exit 1; }
export APP_DB_PASSWORD
exec /usr/local/bin/docker-entrypoint.sh "$@"

#!/usr/bin/env python3
"""Compare source migration files with the actual isolated CI database history."""
import csv
import io
import os
from pathlib import Path
import re
import subprocess


def main() -> None:
    if os.environ.get('CI') != 'true' or os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('Flyway history verification requires explicit CI mode.')
    port = os.environ.get('PGPORT', '')
    if not port.isascii() or not port.isdigit() or not 1024 <= int(port) <= 65535 or int(port) == 5432:
        raise SystemExit('An explicit isolated PostgreSQL port is required.')
    if (os.environ.get('PGHOST') != '127.0.0.1'
            or os.environ.get('TEST_DB_USERNAME') != 'chagok_test'
            or os.environ.get('TEST_DB_URL') != f'jdbc:postgresql://127.0.0.1:{port}/reconciliation_test'
            or not os.environ.get('TEST_DB_PASSWORD')):
        raise SystemExit('Unexpected isolated test database configuration.')
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith('PG')}
    environment.update(PGPASSWORD=os.environ['TEST_DB_PASSWORD'], PGCONNECT_TIMEOUT='5',
                       PGSSLMODE='disable', PGPASSFILE='/dev/null')
    command = ['psql', '--no-psqlrc', '--no-password', '--host=127.0.0.1',
               f'--port={port}', '--username=chagok_test', '--dbname=reconciliation_test',
               '--set=ON_ERROR_STOP=1', '--csv', '--tuples-only', '--command']

    def query(sql: str) -> list[list[str]]:
        try:
            result = subprocess.run(command + [sql], env=environment, text=True,
                                    capture_output=True, timeout=15, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise SystemExit('Unable to read isolated database migration evidence.') from None
        if result.returncode:
            # Avoid forwarding client/server diagnostics that might include configuration.
            raise SystemExit('Unable to read isolated database migration evidence.')
        return list(csv.reader(io.StringIO(result.stdout)))

    if query('SELECT current_database(), current_user;') != [['reconciliation_test', 'chagok_test']]:
        raise SystemExit('Unexpected database identity during migration verification.')
    source = Path(__file__).resolve().parents[2] / 'backend-java/src/main/resources/db/migration'
    expected = {}
    for path in source.glob('V*__*.sql'):
        match = re.fullmatch(r'V([0-9]+(?:[._][0-9]+)*)__.+\.sql', path.name)
        if not match:
            raise SystemExit('Unsupported source migration filename.')
        version = '.'.join(str(int(part)) for part in re.split(r'[._]', match[1]))
        if version in expected:
            raise SystemExit('Duplicate source migration version.')
        expected[version] = path.name
    if not expected:
        raise SystemExit('No source migrations were found.')
    rows = query('SELECT version, script, success FROM public.flyway_schema_history ORDER BY installed_rank;')
    if (len(rows) != len(expected) or any(len(row) != 3 or row[2] != 't' for row in rows)
            or len({row[0] for row in rows}) != len(expected)
            or {row[0]: row[1] for row in rows} != expected):
        raise SystemExit('Flyway history does not exactly match successful source migrations.')
    print(f'Flyway history verified: {len(expected)} source migrations applied successfully.')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Restore a trusted drill backup into the separate, empty drill target only."""
import argparse
import sys

from db_backup import (BackupError, command, fingerprint, guard, load_backup,
                       query, run)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('backup', help='A trusted .dump created by backup-db.py, with its manifest')
    args = parser.parse_args()
    path, manifest = load_backup(args.backup)
    source, source_volume = guard('db-source')
    target, target_volume = guard('db-restore')
    if source['Id'] == target['Id'] or source_volume == target_volume:
        raise BackupError('Source and restore database must be separate containers and volumes.')
    objects = query('db-restore', "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'")
    schemas = query('db-restore', "SELECT count(*) FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname NOT IN ('public','information_schema')")
    routines = query('db-restore', "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'")
    if objects != [[0]] or schemas != [[0]] or routines != [[0]]:
        raise BackupError('Restore target is not empty; overwrites and repeated restores are refused.')
    # Reading a dump executes the SQL it contains. Only locally-created, trusted
    # backups belong here; SHA256 detects corruption, not a malicious producer.
    with path.open('rb') as stream:
        if stream.read(5) != b'PGDMP':
            raise BackupError('Only custom-format PostgreSQL backups are accepted.')
    with path.open('rb') as stream:
        # Subprocess input is streamed to Docker stdin; do not load the dump in RAM.
        import subprocess
        try:
            result = subprocess.run(command('db-restore', 'pg_restore', '--no-password',
                                            '--single-transaction', '--exit-on-error',
                                            '--no-owner', '--no-acl'), stdin=stream,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                    timeout=300, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise BackupError('Restore unavailable or timed out.') from None
        if result.returncode:
            raise BackupError('Restore command failed; inspect the isolated target before any retry.')
    restored = fingerprint('db-restore')
    if restored != manifest['fingerprint']:
        raise BackupError('Restored database fingerprints do not match the backup snapshot.')
    print('Restore verified: schema, table rows/SHA256, sequences and Flyway history match.')
    print('Tables: ' + str(len(restored['tables'])) + '; Flyway migrations: ' + str(restored['flyway']['total']))
    print('Original database unchanged. Java balance/API comparison is a separate verification step.')


if __name__ == '__main__':
    try:
        main()
    except (BackupError, OSError, ValueError, KeyError, TypeError):
        print('Restore refused or failed. Existing databases were not reset; do not reuse a nonempty target.', file=sys.stderr)
        sys.exit(1)

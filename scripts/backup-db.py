#!/usr/bin/env python3
"""Back up only the dedicated, quiesced PostgreSQL 17 recovery-drill source."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import sys

from db_backup import (BackupError, DB, PROJECT, ROLE, Snapshot, command, digest,
                       fingerprint, guard, outside_repo, run)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', default=str(Path.home() / 'chagok-backups'))
    args = parser.parse_args()
    os.umask(0o077)
    directory = outside_repo(args.output_dir, directory=True)
    guard('db-source')
    name = 'chagok-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + secrets.token_hex(8) + '.dump'
    target = directory / name
    partial = directory / (name + '.partial')
    with Snapshot('db-source') as snapshot:
        before = fingerprint('db-source', snapshot)
        with partial.open('xb') as stream:
            run(command('db-source', 'pg_dump', '--no-password', '--format=custom',
                        '--no-owner', '--no-acl', '--lock-wait-timeout=10s',
                        '--snapshot=' + snapshot), stdout=stream)
            stream.flush()
            os.fsync(stream.fileno())
        after = fingerprint('db-source', snapshot)
        if before != after:
            raise BackupError('Source sequence/schema state changed; partial file is not a completed backup.')
    with partial.open('rb') as stream:
        if partial.stat().st_size < 5 or stream.read(5) != b'PGDMP':
            raise BackupError('A valid custom-format backup was not produced.')
    manifest = {'format': 2, 'project': PROJECT, 'database': DB, 'role': ROLE,
                'created_utc': datetime.now(timezone.utc).isoformat(),
                'sha256': digest(partial), 'fingerprint': before}
    manifest_path = Path(str(target) + '.manifest.json')
    manifest_partial = Path(str(manifest_path) + '.partial')
    with manifest_partial.open('x', encoding='utf-8') as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())
    # Hard-link publication is atomic and refuses to replace an existing name.
    # The manifest is published last and acts as the completion marker.
    os.link(partial, target)
    os.link(manifest_partial, manifest_path)
    partial.unlink()
    manifest_partial.unlink()
    directory_fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)
    print('Backup complete; checksum and snapshot fingerprints recorded.')
    print('Backup file: ' + str(target))
    print('Tables: ' + str(len(before['tables'])) + '; Flyway migrations: ' + str(before['flyway']['total']))


if __name__ == '__main__':
    try:
        main()
    except (BackupError, OSError, ValueError, KeyError, TypeError):
        print('Backup refused or failed. No database was modified; .partial files are not valid backups.', file=sys.stderr)
        sys.exit(1)

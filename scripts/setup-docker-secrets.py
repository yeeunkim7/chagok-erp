#!/usr/bin/env python3
"""Create local Docker credentials without printing or overwriting any value."""
import os
from pathlib import Path
import secrets
import stat
import sys


def main() -> int:
    directory = Path(__file__).resolve().parents[1] / ".local" / "docker-secrets"
    directory.mkdir(parents=True, mode=0o700, exist_ok=True)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Secret directory must be a real directory.")
    os.chmod(directory, 0o700)
    paths = [directory / name for name in ("admin_dev", "app_dev", "admin_test", "app_test")]
    existing = [path for path in paths if path.exists() or path.is_symlink()]
    if existing:
        if len(existing) != len(paths):
            raise ValueError("Incomplete secret set exists; no existing file was overwritten.")
        values = []
        for path in paths:
            metadata = path.lstat()
            if not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o600:
                raise ValueError("Existing secrets must be regular files with mode 0600.")
            value = path.read_bytes().strip()
            if not value:
                raise ValueError("An existing secret is empty; no file was overwritten.")
            values.append(value)
        if len(set(values)) != len(paths):
            raise ValueError("Existing secrets must be distinct; no file was overwritten.")
        print("Existing Docker secrets kept unchanged.")
        return 0

    created = []
    values = set()
    try:
        for path in paths:
            value = secrets.token_urlsafe(48)
            while value in values:
                value = secrets.token_urlsafe(48)
            values.add(value)
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            created.append(path)
            with os.fdopen(descriptor, "w", encoding="ascii") as output:
                output.write(value + "\n")
    except BaseException:
        # Only remove files exclusively created by this invocation.
        for path in created:
            path.unlink()
        raise
    print("Created four distinct Docker secrets with mode 0600; values were not printed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as error:
        print(f"Docker secret setup failed: {error}", file=sys.stderr)
        sys.exit(1)

#!/usr/bin/env python3
"""Always produce a summary, including when Gradle failed before writing XML."""
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    files = sorted((root / 'backend-java/build/test-results/test').glob('TEST-*.xml'))
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    invalid = False
    for path in files:
        try:
            suite = ET.parse(path).getroot()
            if suite.tag != 'testsuite':
                raise ValueError('Unexpected test report root')
            for name in counts:
                count = int(suite.attrib.get(name, '0'))
                if count < 0:
                    raise ValueError('Negative report count')
                counts[name] += count
        except (ET.ParseError, ValueError, OSError):
            invalid = True
    build = os.environ.get('BUILD_OUTCOME', 'unknown')
    flyway = os.environ.get('FLYWAY_OUTCOME', 'unknown')
    exit_code = os.environ.get('BUILD_EXIT_CODE', '')
    exit_code = exit_code if exit_code.isascii() and exit_code.isdigit() else 'not run'
    # Outcomes come from the workflow, but keep summary text bounded and predictable.
    allowed = {'success', 'failure', 'cancelled', 'skipped', 'unknown', ''}
    build = build if build in allowed else 'unknown'
    flyway = flyway if flyway in allowed else 'unknown'
    lines = [f'Gradle build: {build or "not run"}', f'Gradle exit code: {exit_code}',
             f'Flyway verification: {flyway or "not run"}',
             f'JUnit XML files: {len(files)}', '',
             '| Tests | Failures | Errors | Skipped |', '| ---: | ---: | ---: | ---: |',
             '| {tests} | {failures} | {errors} | {skipped} |'.format(**counts)]
    if not files:
        lines += ['', 'No JUnit XML was produced; this is not a passing test result.']
    if invalid:
        lines += ['', 'One or more JUnit reports could not be parsed.']
    summary = '\n'.join(lines) + '\n'
    destination = root / 'backend-java/build/ci-summary.md'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(summary, encoding='utf-8')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as output:
            output.write(summary)
    print(summary, end='')
    return int(invalid or not files or counts['tests'] == 0 or counts['failures'] > 0 or counts['errors'] > 0)


if __name__ == '__main__':
    raise SystemExit(main())

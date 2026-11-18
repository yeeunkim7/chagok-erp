"""Restricted local backup drill helpers; never accepts a development DB target."""
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import stat
import subprocess

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / 'compose.backup-check.yaml'
PROJECT = 'chagok-backup-check'
DB = 'reconciliation_test'
ROLE = 'chagok_test'
TIMEOUT = 300
DOCKER = ['docker', '--host', 'unix:///var/run/docker.sock']


class BackupError(Exception):
    pass


def run(command, *, input=None, stdout=subprocess.PIPE):
    try:
        result = subprocess.run(command, input=input, stdout=stdout,
                                stderr=subprocess.DEVNULL, timeout=TIMEOUT, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise BackupError('Docker/database command unavailable or timed out.') from None
    if result.returncode:
        raise BackupError('Docker/database command failed; sensitive diagnostics suppressed.')
    return result.stdout


def compose(*args):
    # Explicit file and project prevent inherited COMPOSE_FILE/PROJECT_NAME redirects.
    return [*DOCKER, 'compose', '--project-directory', str(ROOT), '-f', str(COMPOSE),
            '-p', PROJECT, *args]


def inspect_service(service, *, required=True):
    ids = run(compose('ps', '-a', '-q', service)).decode().split()
    if not ids and not required:
        return None
    if len(ids) != 1:
        raise BackupError('Expected exactly one drill container.')
    obj = json.loads(run([*DOCKER, 'inspect', ids[0]]))[0]
    labels = obj['Config'].get('Labels', {})
    if labels.get('com.docker.compose.project') != PROJECT or labels.get('com.docker.compose.service') != service:
        raise BackupError('Unexpected Docker project or service identity.')
    return obj


def guard(service):
    if service not in ('db-source', 'db-restore'):
        raise BackupError('Only the isolated drill services are permitted.')
    obj = inspect_service(service)
    if not obj['State']['Running']:
        raise BackupError('Drill database must be running.')
    env = dict(item.split('=', 1) for item in obj['Config']['Env'] if '=' in item)
    if (env.get('APP_DB_NAME') != DB or env.get('APP_DB_USER') != ROLE
            or env.get('APP_DB_PASSWORD_FILE') != '/run/secrets/app_test'):
        raise BackupError('Unexpected drill database configuration.')
    if obj['HostConfig'].get('PortBindings'):
        raise BackupError('Drill databases must not publish host ports.')
    data = [m for m in obj['Mounts'] if m['Destination'] == '/var/lib/postgresql/data']
    if len(data) != 1 or data[0]['Type'] != 'volume':
        raise BackupError('A dedicated named drill volume is required.')
    volume = json.loads(run([*DOCKER, 'volume', 'inspect', data[0]['Name']]))[0]
    vlabels = volume.get('Labels') or {}
    expected = 'source-data' if service == 'db-source' else 'restore-data'
    if (vlabels.get('com.docker.compose.project') != PROJECT
            or vlabels.get('com.docker.compose.volume') != expected):
        raise BackupError('Unexpected database volume; existing development volumes refused.')
    network = 'source' if service == 'db-source' else 'restore'
    nets = obj['NetworkSettings']['Networks']
    if len(nets) != 1:
        raise BackupError('Drill database must use exactly one isolated network.')
    net = json.loads(run([*DOCKER, 'network', 'inspect', next(iter(nets))]))[0]
    if (not net.get('Internal') or net.get('Labels', {}).get('com.docker.compose.project') != PROJECT
            or net.get('Labels', {}).get('com.docker.compose.network') != network):
        raise BackupError('Unexpected drill network isolation.')
    api = inspect_service('source-api' if service == 'db-source' else 'restore-api', required=False)
    if api and api['State']['Running']:
        raise BackupError('Stop the corresponding drill API before backup or restore.')
    # Other attached containers can be independent writers; reject them too.
    if any(cid != obj['Id'] and entry.get('Name') != (api or {}).get('Name', '').lstrip('/')
           for cid, entry in net.get('Containers', {}).items()):
        raise BackupError('Unexpected container on the drill database network.')
    identity = query(service, "SELECT current_database(), current_user, current_setting('server_version_num')::int / 10000, (SELECT bool_or(rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit OR EXISTS (SELECT FROM pg_auth_members WHERE member=pg_roles.oid)) FROM pg_roles WHERE rolname=current_user);")
    if identity != [[DB, ROLE, 17, False]]:
        raise BackupError('Unexpected database identity or excessive role privileges.')
    active = query(service, "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid <> pg_backend_pid() AND backend_type='client backend' AND state IS DISTINCT FROM 'idle';")
    if active != [[0]]:
        raise BackupError('Source/target must be idle; active database sessions refused.')
    return obj, data[0]['Name']


def command(service, tool, *args):
    # Password only exists inside the container process; never argv or output.
    shell = ('set -eu; unset PGSERVICE PGSERVICEFILE PGHOSTADDR PGOPTIONS; '
             'export PGPASSWORD="$(cat /run/secrets/app_test)" PGCONNECT_TIMEOUT=5 PGPASSFILE=/dev/null; '
             'exec "$@"')
    return compose('exec', '-T', service, 'sh', '-c', shell, 'sh', tool,
                   '--host=127.0.0.1', '--username=' + ROLE, '--dbname=' + DB, *args)


def raw_query(service, sql, snapshot=None):
    if snapshot:
        if not re.fullmatch(r'[0-9A-Fa-f-]+', snapshot):
            raise BackupError('Invalid exported snapshot identifier.')
        sql = "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT '" + snapshot + "';\n" + sql + '\nROLLBACK;'
    return run(command(service, 'psql', '--no-psqlrc', '--no-password', '-qAt', '-v', 'ON_ERROR_STOP=1'), input=sql.encode()).decode().strip()


def query(service, sql, snapshot=None):
    sql = sql.strip().rstrip(';')
    return _rows(service, sql, snapshot)


def _rows(service, sql, snapshot):
    # Arrays preserve column order without exposing column names/values to logs.
    statement = "SELECT coalesce(json_agg(row_to_json(r)), '[]'::json) FROM (" + sql + ') r;'
    value = json.loads(raw_query(service, statement, snapshot))
    return [list(row.values()) for row in value]


class Snapshot:
    def __init__(self, service):
        self.service = service
        self.process = None

    def __enter__(self):
        self.process = subprocess.Popen(command(self.service, 'psql', '--no-psqlrc', '--no-password', '-qAt', '-v', 'ON_ERROR_STOP=1'), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.process.stdin.write(b"BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSET idle_in_transaction_session_timeout='300s';\nSELECT pg_export_snapshot();\n")
        self.process.stdin.flush()
        with selectors.DefaultSelector() as selector:
            selector.register(self.process.stdout, selectors.EVENT_READ)
            if not selector.select(15):
                self.close()
                raise BackupError('Snapshot export timed out.')
        snapshot = self.process.stdout.readline().decode().strip()
        if not re.fullmatch(r'[0-9A-Fa-f-]+', snapshot):
            self.close()
            raise BackupError('Snapshot export failed.')
        return snapshot

    def close(self):
        if self.process:
            try:
                self.process.communicate(b'ROLLBACK;\n\\q\n', timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                self.process.kill()
                self.process.communicate()
            self.process = None

    def __exit__(self, *args):
        self.close()


def canonical_checks(service, snapshot=None):
    # PostgreSQL can reparse equivalent CHECK trees differently on pg_restore
    # (array casts, nested AND). Use its planner's typed output, not regex edits.
    checks = query(service, "SELECT c.relname AS table_name,k.conname,k.convalidated,k.condeferrable,k.condeferred,k.connoinherit,pg_get_expr(k.conbin,k.conrelid) AS expression FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND k.contype='c' ORDER BY c.relname,k.conname", snapshot)
    if not checks:
        return []
    statements = []
    for row in checks:
        table = '"' + row[0].replace('"', '""') + '"'
        statements.append('EXPLAIN (VERBOSE, FORMAT JSON, COSTS OFF) SELECT (' + row[6] + ') FROM public.' + table + ';')
    # One psql invocation keeps all plans in the same imported snapshot and
    # avoids a Docker process per constraint. Decode successive JSON documents.
    output = raw_query(service, '\n'.join(statements), snapshot)
    decoder = json.JSONDecoder()
    normalized = []
    offset = 0
    for row in checks:
        while offset < len(output) and output[offset].isspace():
            offset += 1
        plan, offset = decoder.raw_decode(output, offset)
        expressions = plan[0]['Plan']['Output']
        if len(expressions) != 1:
            raise BackupError('Unexpected CHECK expression plan.')
        normalized.append({'table': row[0], 'name': row[1], 'validated': row[2],
                           'deferrable': row[3], 'deferred': row[4],
                           'no_inherit': row[5], 'expression': expressions[0]})
    if output[offset:].strip():
        raise BackupError('Unexpected extra CHECK expression plans.')
    return normalized


def fingerprint(service, snapshot=None):
    # SHA256 per row, sorted as a multiset, then SHA256 again: no raw row is retained.
    names = query(service, "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", snapshot)
    tables = {}
    for (name,) in names:
        quoted = '"' + name.replace('"', '""') + '"'
        rows = query(service, "SELECT count(*) AS rows, encode(sha256(convert_to(coalesce(string_agg(h,'' ORDER BY h),''),'UTF8')),'hex') AS sha256 FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex') h FROM public." + quoted + ' t) r', snapshot)
        tables[name] = {'rows': rows[0][0], 'sha256': rows[0][1]}
    schema = raw_query(service, "SELECT coalesce(json_agg(r ORDER BY kind,identity,definition),'[]'::json)::text FROM (SELECT 'column' kind, table_name||'.'||column_name identity, concat_ws('|',ordinal_position,data_type,udt_name,is_nullable,column_default,character_maximum_length,numeric_precision,numeric_scale,is_identity,identity_generation) definition FROM information_schema.columns WHERE table_schema='public' UNION ALL SELECT 'constraint', c.relname||'.'||conname, pg_get_constraintdef(k.oid) FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND k.contype <> 'c' UNION ALL SELECT 'index', tablename||'.'||indexname,indexdef FROM pg_indexes WHERE schemaname='public' UNION ALL SELECT 'sequence',sequencename,concat_ws('|',data_type,start_value,min_value,max_value,increment_by,cycle,cache_size) FROM pg_sequences WHERE schemaname='public') r;", snapshot)
    schema = json.dumps({'catalog': json.loads(schema), 'checks': canonical_checks(service, snapshot)},
                        sort_keys=True, separators=(',', ':'), ensure_ascii=False)
    sequences = {}
    for (name,) in query(service, "SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename", snapshot):
        quoted = '"' + name.replace('"', '""') + '"'
        sequences[name] = query(service, 'SELECT last_value,is_called FROM public.' + quoted)[0]
    if 'flyway_schema_history' not in tables:
        raise BackupError('A migrated source database with Flyway history is required.')
    flyway = query(service, 'SELECT count(*) AS total,count(*) FILTER (WHERE success) AS successful FROM public.flyway_schema_history', snapshot)[0]
    if flyway[0] == 0 or flyway[0] != flyway[1]:
        raise BackupError('Flyway history is empty or contains a failed migration.')
    return {'tables': tables, 'schema_sha256': hashlib.sha256(schema.encode()).hexdigest(),
            'sequences': sequences, 'flyway': {'total': flyway[0], 'successful': flyway[1]}}


def outside_repo(path, *, directory=False):
    path = Path(path).expanduser().absolute()
    # Reject symlinks in every existing path component, including the destination.
    if any(part.is_symlink() for part in (path, *path.parents)):
        raise BackupError('Symlink paths are not permitted.')
    resolved = path.resolve()
    # The cloud workspace can have its own administrative .git directory.
    # Enforce the actual application checkout boundary, not its parent workspace.
    if resolved == ROOT or ROOT in resolved.parents:
        raise BackupError('Backup files must be outside the application repository.')
    if directory:
        resolved.mkdir(parents=True, mode=0o700, exist_ok=True)
        if not resolved.is_dir():
            raise BackupError('Backup output must be a directory.')
        metadata = resolved.stat()
        if stat.S_IMODE(metadata.st_mode) != 0o700 or metadata.st_uid != os.geteuid():
            raise BackupError('Backup directory must already have owner-only mode 0700; existing permissions were not changed.')
    else:
        if not resolved.is_file() or stat.S_IMODE(resolved.stat().st_mode) != 0o600:
            raise BackupError('Backup files must be regular files with mode 0600.')
    return resolved


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def load_backup(path):
    path = outside_repo(path)
    manifest_path = outside_repo(Path(str(path) + '.manifest.json'))
    if manifest_path.stat().st_size > 2 * 1024 * 1024:
        raise BackupError('Backup manifest exceeds the local drill size limit.')
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if not isinstance(manifest, dict) or not isinstance(manifest.get('fingerprint'), dict):
        raise BackupError('Backup manifest has an invalid structure.')
    if (manifest.get('format') != 2 or manifest.get('database') != DB
            or manifest.get('role') != ROLE or manifest.get('project') != PROJECT
            or manifest.get('sha256') != digest(path)):
        raise BackupError('Backup identity or checksum does not match its manifest.')
    return path, manifest

"""Export the live database structure to backend/schema.sql using pg_dump (no table data)."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from urllib.parse import parse_qs, unquote, urlsplit

import javaproperties

BACKEND = Path(__file__).resolve().parents[1]


def export_schema(env_file, output, executable=None):
    settings = {}
    if env_file.exists():
        with env_file.open(encoding='utf-8-sig') as stream:
            settings.update(javaproperties.load(stream))
    settings.update(os.environ)
    connection_url = settings.get('DB_MIGRATION_URL') or settings.get('DB_URL', '')
    if connection_url.startswith('jdbc:'):
        connection_url = connection_url[5:]
    parsed = urlsplit(connection_url)
    if parsed.scheme not in ('postgresql', 'postgres') or not parsed.hostname or not parsed.path.strip('/'):
        raise ValueError('Set DB_MIGRATION_URL or DB_URL to a PostgreSQL URL in backend/.env')
    if '-pooler' in parsed.hostname:
        raise ValueError('Set DB_MIGRATION_URL to the direct URL of the same database (hostname without -pooler)')
    username = settings.get('DB_USERNAME') or (unquote(parsed.username) if parsed.username else None)
    password = settings.get('DB_PASSWORD') or (unquote(parsed.password) if parsed.password else None)
    if not username or not password:
        raise ValueError('Set DB_USERNAME and DB_PASSWORD in backend/.env or environment')
    binary = executable or shutil.which('pg_dump')
    if not binary:
        portable = BACKEND.parent / '.tools/postgresql18/pg_dump.exe'
        binary = str(portable) if portable.is_file() else None
    if not binary:
        raise ValueError('pg_dump is missing: install PostgreSQL client 18+ or supply --pg-dump PATH')

    query = parse_qs(parsed.query)
    process_env = dict(os.environ, PGHOST=parsed.hostname, PGPORT=str(parsed.port or 5432),
                       PGDATABASE=unquote(parsed.path[1:]), PGUSER=username, PGPASSWORD=password,
                       PGSSLMODE=query.get('sslmode', ['require'])[0],
                       PGCHANNELBINDING=query.get('channelBinding', ['require'])[0],
                       PGCONNECT_TIMEOUT='30', PGCLIENTENCODING='UTF8',
                       PGOPTIONS='-c default_transaction_read_only=on')
    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix='schema-export-', suffix='.sql', dir=output.parent, delete=False) as file:
        temporary = Path(file.name)
    try:
        command = [binary, '--schema-only', '--no-owner', '--no-privileges', '--no-tablespaces',
                   '--schema=asl', '--schema=public', '--extension=pg_trgm', '--encoding=UTF8', '--no-password',
                   '--file', str(temporary)]
        result = subprocess.run(command, env=process_env, capture_output=True, text=True,
                                encoding='utf-8', errors='replace', timeout=180)
        if result.returncode:
            # Never put connection credentials on the command line or in diagnostic output.
            detail = result.stderr.replace(password, '[redacted]').replace(connection_url, '[connection]')
            raise RuntimeError('pg_dump failed: ' + detail.strip())
        if temporary.stat().st_size == 0:
            raise RuntimeError('pg_dump returned an empty file')
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=BACKEND / '.env')
    parser.add_argument('--output', type=Path, default=BACKEND / 'schema.sql')
    parser.add_argument('--pg-dump', help='Path to pg_dump, version 18 or later')
    args = parser.parse_args()
    try:
        path = export_schema(args.env_file, args.output, args.pg_dump)
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print(str(error))
        raise SystemExit(1)
    print(f'Exported schema only: {path} ({path.stat().st_size:,} bytes)')

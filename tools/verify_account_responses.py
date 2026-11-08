"""Validate module 06-08 runtime captures, including actual PostgreSQL identity HTTP responses."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]

def verify():
    raw = (ROOT / '.api-contract/openapi.mobile.yaml').read_bytes()
    contract = yaml.safe_load(raw)
    ids = {'support_report', 'auth_legal', 'auth_bootstrap', 'account_profile',
           'account_updateProfile', 'account_stats', 'sync_snapshot', 'sync_changes', 'sync_push'}
    operations = {op['operationId']: op for item in contract['paths'].values() for op in item.values()
                  if isinstance(op, dict) and op.get('operationId') in ids}
    errors, captures, successful = [], [], set()
    for module in ('reporting', 'identity', 'sync'):
        for path in sorted((ROOT / f'backend/target/{module}-http').glob('*.json')):
            operation = next((name for name in ids if path.stem.startswith(name + '-')), None)
            status = re.search(r'-(\d{3})(?:-|$)', path.stem)
            response = operations.get(operation, {}).get('responses', {}).get(status[1] if status else '')
            if not response:
                errors.append(f'Undocumented response: {path.name}')
                continue
            body = json.loads(path.read_text(encoding='utf-8'))
            schema = dict(response['content']['application/json']['schema'], components=contract['components'])
            errors.extend(f'{path.name} {e.json_path}: {e.message}' for e in
                          Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(body))
            if status[1].startswith('2'):
                successful.add(operation)
            elif re.fullmatch(r'[A-Z_, ]+', response['description']):
                if body.get('error', {}).get('code') not in response['description'].split(', '):
                    errors.append(f'Unexpected error code: {path.name}')
            captures.append(f'{module}/{path.name}')
    errors.extend(f'Missing success capture: {operation}' for operation in ids - successful)
    return {'contractSha256': hashlib.sha256(raw).hexdigest(), 'operations': sorted(ids),
            'actualResponsesChecked': captures, 'errors': errors,
            'scope': 'HTTP serialization/schema and error envelopes; PostgreSQL integration tests separately prove transactions. Firebase external verifier is substituted only in test configuration; live providers remain unverified.'}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path)
    result = verify()
    output = json.dumps(result, ensure_ascii=False, indent=2)
    args = parser.parse_args()
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output + '\n', encoding='utf-8')
    print(output)
    raise SystemExit(bool(result['errors']))

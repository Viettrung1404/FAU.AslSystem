"""Check actual catalog HTTP captures against OpenAPI 3.1; missing required captures fail."""
import argparse
import hashlib
import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
REQUIRED = {
    'catalog_config': {'200', '400', '429', '503'},
    'catalog_home': {'200', '400', '429', '503'},
    'catalog_units': {'200', '400', '429', '503'},
    'catalog_unit': {'200', '400', '404', '429', '503'},
}


def verify(directory):
    raw = (ROOT / 'api-contract/openapi.mobile.yaml').read_bytes()
    contract = yaml.safe_load(raw)
    operations = {path['get']['operationId']: path['get'] for path in contract['paths'].values()
                  if 'get' in path and path['get']['operationId'] in REQUIRED}
    found, checked, errors = set(), [], []
    for path in sorted(directory.glob('*.json')):
        parts = path.stem.split('-')
        operation, status = parts[:2]
        response = operations.get(operation, {}).get('responses', {}).get(status)
        if response is None:
            errors.append(f'Unknown operation/status capture: {path.name}')
            continue
        found.add((operation, status))
        schema = dict(response['content']['application/json']['schema'], components=contract['components'])
        body = json.loads(path.read_text(encoding='utf-8'))
        errors.extend(f'{path.name} {error.json_path}: {error.message}' for error in
                      Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(body))
        if status != '200' and body.get('error', {}).get('code') != response['description']:
            errors.append(f'{path.name}: incorrect error code')
        checked.append(path.name)
    for operation, statuses in REQUIRED.items():
        for status in sorted(statuses):
            if (operation, status) not in found:
                errors.append(f'Missing actual HTTP capture: {operation}/{status}')
    return {'contractSha256': hashlib.sha256(raw).hexdigest(), 'operations': sorted(REQUIRED),
            'actualResponsesChecked': checked, 'errors': errors,
            'scope': 'MockMvc plus *-integration/*-dev real HTTP with PostgreSQL; no production configuration claim.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=ROOT / 'backend/target/catalog-http')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    result = verify(args.directory)
    output = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output, encoding='utf-8')
    print(output, end='')
    raise SystemExit(1 if result['errors'] else 0)

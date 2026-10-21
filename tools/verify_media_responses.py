"""Validate actual MediaHttpTest/MediaIT response bodies against the unchanged OpenAPI contract.

Run Maven tests first. Missing response captures are a failure, never a skip.
"""
import argparse
import hashlib
import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {'200', '400', '404', '410', '422', '429', '503'}


def verify(directory):
    raw = (ROOT / 'api-contract/openapi.mobile.yaml').read_bytes()
    contract = yaml.safe_load(raw)
    responses = contract['paths']['/api/v1/media/urls']['post']['responses']
    files = sorted(directory.glob('*.json'))
    found = {path.stem.split('-')[0] for path in files}
    errors = [f'Missing actual response capture: {status}' for status in sorted(EXPECTED - found)]
    checked = []
    for path in files:
        status = path.stem.split('-')[0]
        if status not in responses:
            errors.append(f'Undocumented response status: {status}')
            continue
        schema = dict(responses[status]['content']['application/json']['schema'], components=contract['components'])
        body = json.loads(path.read_text(encoding='utf-8'))
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        errors.extend(f'{path.name} {error.json_path}: {error.message}' for error in validator.iter_errors(body))
        if status != '200' and body.get('error', {}).get('code') != responses[status]['description']:
            errors.append(f'{path.name}: incorrect error code')
        checked.append(path.name)
    return {'contractSha256': hashlib.sha256(raw).hexdigest(), 'operationId': 'media_refresh',
            'actualResponsesChecked': checked, 'errors': errors,
            'scope': 'Actual MockMvc responses; *-integration captures come from HTTP with PostgreSQL. Not a production availability claim.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=ROOT / 'backend/target/media-http')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    result = verify(args.directory)
    output = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output, encoding='utf-8')
    print(output, end='')
    raise SystemExit(1 if result['errors'] else 0)

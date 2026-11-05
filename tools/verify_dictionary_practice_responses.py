"""Validate captured runtime dictionary/practice responses against the canonical OpenAPI."""
import argparse
import hashlib
import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]


def verify():
    raw = (ROOT / '.api-contract/openapi.mobile.yaml').read_bytes()
    contract = yaml.safe_load(raw)
    operations = {
        op['operationId']: op for path in contract['paths'].values()
        for op in path.values() if isinstance(op, dict)
        and op.get('operationId', '').startswith(('dictionary_', 'practice_'))
    }
    found, checked, errors = set(), [], []
    for module in ('dictionary', 'practice'):
        for path in sorted((ROOT / f'backend/target/{module}-http').glob('*.json')):
            operation, status = path.stem.split('-')[:2]
            response = operations.get(operation, {}).get('responses', {}).get(status)
            if response is None:
                errors.append(f'Unknown capture {path.name}')
                continue
            body = json.loads(path.read_text(encoding='utf-8'))
            schema = dict(response['content']['application/json']['schema'], components=contract['components'])
            errors.extend(f'{path.name} {e.json_path}: {e.message}' for e in
                          Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(body))
            if status != '200' and body.get('error', {}).get('code') not in response['description'].split(', '):
                errors.append(f'{path.name}: unexpected error code')
            if operation == 'practice_generate' and status == '200':
                semantic_checks(body, errors, path.name)
            found.add((operation, status))
            checked.append(path.name)
    for operation, op in operations.items():
        for status in op['responses']:
            if (operation, status) not in found:
                errors.append(f'Missing runtime response: {operation}/{status}')
    return {'contractSha256': hashlib.sha256(raw).hexdigest(), 'actualResponsesChecked': checked,
            'operations': sorted(operations), 'errors': errors,
            'scope': 'MockMvc error envelopes plus real PostgreSQL/HTTP success responses. Premium provider integration and verified domain association remain deployment dependencies.'}


def semantic_checks(body, errors, name):
    def require(condition, message):
        if not condition:
            errors.append(f'{name}: {message}')
    require(len({s['lessonId'] for s in body['sourceLessons']}) >= 3, 'not enough distinct source lessons')
    if body['mode'] == 'QUIZ':
        for item in body['items']:
            options = item['payload']['options']
            require(len({o['id'] for o in options}) == len(options), 'duplicate option IDs')
            require(len({o['signId'] for o in options}) == len(options), 'duplicate option signs')
            correct = [o for o in options if o['id'] == item['answerKey']['correctOptionId']]
            require(len(correct) == 1 and correct[0]['signId'] == item['signId'], 'answer does not resolve to target')
    elif body['mode'].endswith('MATCHING'):
        for item in body['items']:
            left = {o['id'] for o in item['left']}
            right = {o['id'] for o in item['right']}
            require(left == {p['leftId'] for p in item['correctPairs']}, 'incomplete left matching')
            require(right == {p['rightId'] for p in item['correctPairs']}, 'incomplete right matching')
            require(len(left) == len(right) == len(item['correctPairs']), 'ambiguous matching')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    result = verify()
    output = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output, encoding='utf-8')
    print(output, end='')
    raise SystemExit(bool(result['errors']))

"""Maintain backend/openapi.mobile.yaml as a byte-identical copy of the canonical contract."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='Copy api-contract/openapi.mobile.yaml into backend')
    args = parser.parse_args()
    source = ROOT / 'api-contract/openapi.mobile.yaml'
    target = ROOT / 'backend/openapi.mobile.yaml'
    if args.write:
        target.write_bytes(source.read_bytes())
    matches = target.exists() and source.read_bytes() == target.read_bytes()
    print('PASS: OpenAPI copies match' if matches else 'FAIL: OpenAPI copies differ; run with --write to sync from api-contract')
    raise SystemExit(0 if matches else 1)

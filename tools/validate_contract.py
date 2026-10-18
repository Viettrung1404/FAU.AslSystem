"""Validate the supplied contract and operation ownership; no backend conformance claim."""
import argparse
import hashlib
import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from contract_semantics import validate_fixture_semantics

ROOT = Path(__file__).resolve().parents[2]
METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}


def validate(contract_path, operations_path):
    raw = contract_path.read_bytes()
    contract = yaml.safe_load(raw)
    errors = []
    if not str(contract.get("openapi", "")).startswith("3.1."):
        errors.append("Expected OpenAPI 3.1")

    def walk(value):
        if isinstance(value, dict):
            ref = value.get("$ref")
            if ref:
                if not ref.startswith("#/"):
                    errors.append(f"External reference is not supported: {ref}")
                else:
                    current = contract
                    try:
                        for part in ref[2:].split("/"):
                            current = current[part.replace("~1", "/").replace("~0", "~")]
                    except (KeyError, TypeError):
                        errors.append(f"Unresolved reference: {ref}")
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    walk(contract)
    schemas = contract["components"]["schemas"]
    for name, schema in schemas.items():
        try:
            Draft202012Validator.check_schema(schema)
        except Exception as error:
            errors.append(f"Schema {name}: {error}")

    operations = []
    examples = 0
    for path, item in contract["paths"].items():
        for method, operation in item.items():
            if method not in METHODS:
                continue
            operation_id = operation.get("operationId")
            operations.append({"operationId": operation_id, "method": method.upper(), "path": path})
            bodies = [("request", operation.get("requestBody", {}))]
            bodies.extend(operation.get("responses", {}).items())
            for label, body in bodies:
                for media in body.get("content", {}).values():
                    if "schema" not in media:
                        continue
                    # Attach components at the document root to preserve local JSON pointers.
                    schema = dict(media["schema"], components=contract["components"])
                    validator = Draft202012Validator(schema, format_checker=FormatChecker())
                    samples = dict(media.get("examples", {}))
                    if "example" in media:
                        samples["inline"] = {"value": media["example"]}
                    for name, sample in samples.items():
                        if "value" not in sample:
                            continue
                        examples += 1
                        for error in validator.iter_errors(sample["value"]):
                            errors.append(f"{operation_id}/{label}/{name} {error.json_path}: {error.message}")

    owners = json.loads(operations_path.read_text(encoding="utf-8"))
    expected = {(op["operationId"], op["method"], op["path"]) for op in operations}
    actual = {(op["operationId"], op["method"], op["path"]) for op in owners}
    if expected != actual:
        errors.append(f"Operation mapping mismatch: missing={expected - actual}, extra={actual - expected}")
    if len(actual) != len(owners) or len({op['operationId'] for op in operations}) != len(operations):
        errors.append("Duplicate operations")
    for op in owners:
        if not (ROOT / "plan" / "modules" / (op["module"] + ".md")).is_file():
            errors.append(f"Missing module plan: {op['module']}")
    semantic_checks, semantic_errors = validate_fixture_semantics(contract)
    errors.extend(semantic_errors)
    mirror = ROOT / 'backend/openapi.mobile.yaml'
    if not mirror.exists() or mirror.read_bytes() != raw:
        errors.append('backend/openapi.mobile.yaml differs from canonical contract; run backend/tools/sync_contract.py --write')
    manifest = json.loads((ROOT / 'api-contract/examples/mock-media.json').read_text(encoding='utf-8'))
    assets = {asset['assetId']: asset for asset in manifest['assets']}
    media_examples = 0

    def check_media(value):
        nonlocal media_examples
        if isinstance(value, dict):
            if isinstance(value.get('assetId'), str) and {'url', 'mimeType', 'durationMs', 'expiresAt'} <= value.keys():
                media_examples += 1
                expected_asset = assets.get(value['assetId'])
                fields = ('url', 'mimeType', 'durationMs', 'expiresAt')
                if not expected_asset or any(value[field] != expected_asset[field] for field in fields):
                    errors.append('Media fixture differs from manifest: ' + value['assetId'])
            for child in value.values():
                check_media(child)
        elif isinstance(value, list):
            for child in value:
                check_media(child)

    check_media(contract)
    return {
        "contractSha256": hashlib.sha256(raw).hexdigest(),
        "paths": len(contract["paths"]), "operations": len(operations), "schemas": len(schemas),
        "httpExamplesChecked": examples, "semanticChecks": semantic_checks,
        "mediaExamplesChecked": media_examples, "backendCopyMatches": mirror.exists() and mirror.read_bytes() == raw,
        "errors": errors,
        "scope": "Schema, references, fixture semantics and operation ownership; not backend integration tests",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = validate(ROOT / "api-contract" / "openapi.mobile.yaml", ROOT / "plan" / "operations.json")
    output = json.dumps(result, ensure_ascii=False, indent=2)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output + "\n", encoding="utf-8")
    print(output)
    raise SystemExit(1 if result["errors"] else 0)

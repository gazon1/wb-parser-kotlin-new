#!/usr/bin/env bash
# Regenerate scripts/parser/catalog_models.py from the catalog contract.
#
# The one place the command lives: `just parser::models` calls this, and the CI
# `static` job calls this and then asserts `git diff --exit-code`. Two callers depend
# on it staying byte-for-byte reproducible, so the generator version is pinned here and
# nowhere else.
#
# `--openapi-scopes schemas` and not `paths`: the catalog's shapes are the components.
# The `/health`, `/ready` and `/contract` bodies are operations, and the admin
# endpoints are not in this document at all.
#
# `--type-overrides` makes cashback `Decimal`. The server sends `BigDecimal`, and the
# generator's default `float` would round the value the client only displays. The
# contract's `number` carries no `multipleOf`, so there is nothing else to hang it on.
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 1

GENERATOR=datamodel-code-generator==0.97.0
CONTRACT=contract/catalog-v1.openapi.yaml
OUTPUT=scripts/parser/catalog_models.py

uvx --from "$GENERATOR" datamodel-codegen \
    --input "$CONTRACT" \
    --input-file-type openapi \
    --output "$OUTPUT" \
    --openapi-scopes schemas \
    --output-model-type pydantic_v2.BaseModel \
    --target-python-version 3.11 \
    --snake-case-field \
    --disable-timestamp \
    --use-union-operator \
    --type-overrides '{"ProductCard.cashback_percent":"decimal.Decimal","ProductCard.cashback_rubles":"decimal.Decimal","PricePoint.cashback_percent":"decimal.Decimal","PricePoint.cashback_rubles":"decimal.Decimal"}'

# The contract's hash, so an offline check (`scripts/ci/check-catalog-models.py`) can
# see the drift a test that loads the file cannot: a contract edited after generation.
python3 - "$CONTRACT" >> "$OUTPUT" <<'PY'
import hashlib, sys
print("\n\n# source-sha256: " + hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest() + "\n", end="")
PY

# The repository's ruff is the formatter the gate checks the file with, so the
# generated file is brought to that shape here rather than left for a surprise.
ruff format "$OUTPUT"
ruff check --quiet --fix "$OUTPUT"

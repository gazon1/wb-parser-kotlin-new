# ==============================================================================
# 🕹️ PARSER — operate the local crawler from a terminal
#
# One entry point: scripts/parser/wbparser.py. There is no web UI in this
# repository — the catalog is a read API with its own storefront elsewhere, and
# `items` reads it through the same endpoints the storefront does.
#
# The script's dependencies live in `pyproject.toml`, so the only setup step is
# `uv sync` — every recipe below runs it through `uv run`.
# ==============================================================================

[group('parser')]
[doc('Start the stack (Postgres + the application) and wait until it can serve')]
[working-directory(justfile_directory())]
up *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py up {{ARGS}}

[group('parser')]
[doc('Stop the stack (the database volume is kept)')]
[working-directory(justfile_directory())]
down *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py down {{ARGS}}

[group('parser')]
[doc('Health, readiness, credentials, crawl counters, egress groups')]
[working-directory(justfile_directory())]
status *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py status {{ARGS}}

[group('parser')]
[doc('What was scraped, paged and filtered (the storefront own data)')]
[working-directory(justfile_directory())]
items *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py items {{ARGS}}

[group('parser')]
[doc('The journal of runs: which target ran, from which address, how it went')]
[working-directory(justfile_directory())]
runs *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py runs {{ARGS}}

[group('parser')]
[doc('The tick, the freshness window, and which targets are due')]
[working-directory(justfile_directory())]
schedule *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py schedule {{ARGS}}

[group('parser')]
[doc('Aggregate counters of the catalog')]
[working-directory(justfile_directory())]
stats *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py stats {{ARGS}}

[group('parser')]
[doc('Run exactly one crawl in the foreground (profile: once)')]
[working-directory(justfile_directory())]
crawl *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py crawl {{ARGS}}

[group('parser')]
[doc('Tail or follow the application log')]
[working-directory(justfile_directory())]
logs *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py logs {{ARGS}}

[group('parser')]
[doc('Last crawler runs, what started each, and when the next one is due')]
[working-directory(justfile_directory())]
history *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py history {{ARGS}}

[group('parser')]
[doc('Show the help of every command')]
[working-directory(justfile_directory())]
help:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py help

# The generator version is pinned: the output is committed and reviewed, and a
# different version rewrites the file for reasons that are not a change to the
# contract. `schemas` and not `paths` on purpose — the catalog's shapes are the
# components, and the health/ready/contract bodies are operations outside the
# generated models. Cashback is Decimal because the server sends BigDecimal and
# the generator's default `float` would discard the value it was given.
[group('parser')]
[doc('Regenerate scripts/parser/catalog_models.py from the OpenAPI contract')]
[working-directory(justfile_directory())]
models:
    #!/usr/bin/env bash
    set -euo pipefail
    uvx --from datamodel-code-generator==0.97.0 datamodel-codegen \
        --input contract/catalog-v1.openapi.yaml \
        --input-file-type openapi \
        --output scripts/parser/catalog_models.py \
        --openapi-scopes schemas \
        --output-model-type pydantic_v2.BaseModel \
        --target-python-version 3.11 \
        --snake-case-field \
        --disable-timestamp \
        --use-union-operator \
        --type-overrides '{"ProductCard.cashback_percent":"decimal.Decimal","ProductCard.cashback_rubles":"decimal.Decimal","PricePoint.cashback_percent":"decimal.Decimal","PricePoint.cashback_rubles":"decimal.Decimal"}'
    # The gate reads this marker, so a contract edit without a regeneration is a
    # finding rather than a file that merely looks old.
    python3 -c 'import hashlib, pathlib; print("\n\n# source-sha256: " + hashlib.sha256(pathlib.Path("contract/catalog-v1.openapi.yaml").read_bytes()).hexdigest() + "\n", end="")' >> scripts/parser/catalog_models.py
    # The repository's ruff is the formatter the gate checks the file with, so the
    # generated file is brought to that shape here rather than left for a surprise.
    ruff format scripts/parser/catalog_models.py
    ruff check --fix scripts/parser/catalog_models.py

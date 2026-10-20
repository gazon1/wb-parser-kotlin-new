# ==============================================================================
# 📦 CATALOG — WB category selector and load tools
# Wrappers around scripts/wb-catalog/
#
# DB connection uses the same env vars as docker-compose.yml:
#   DB_HOST  DB_PORT  DB_NAME  DB_USER  DB_PASSWORD
#   (defaults: localhost / 5433 / wbparser / postgres / postgres)
# ==============================================================================

# ── Shared env defaults ────────────────────────────────────────────────────────

DB_HOST    := env_var_or_default("DB_HOST", "localhost")
DB_PORT    := env_var_or_default("DB_PORT", "5433")
DB_NAME    := env_var_or_default("DB_NAME", "wbparser")
DB_USER    := env_var_or_default("DB_USER", "postgres")
DB_PASSWORD := env_var_or_default("DB_PASSWORD", "postgres")
CATALOG_YAML := "wb_catalog_targets.yaml"


# ==============================================================================
# 🌐 Web UI
# ==============================================================================

[group('catalog')]
[doc('Run the NiceGUI WB category selector web UI (port 8080)')]
[working-directory(justfile_directory())]
selector:
    #!/bin/bash
    set -euo pipefail
    uv run scripts/wb-catalog/catalog_selector.py

# ==============================================================================
# 💾 Load into DB
# ==============================================================================

[group('catalog')]
[doc('Dry-run: show what would be inserted (use load-confirm to actually insert)')]
[working-directory(justfile_directory())]
load yaml=CATALOG_YAML:
    #!/bin/bash
    set -euo pipefail
    uv run scripts/wb-catalog/load_catalog_to_pg.py {{yaml}} \
        --db-host {{DB_HOST}} \
        --db-port {{DB_PORT}} \
        --db-name {{DB_NAME}} \
        --db-user {{DB_USER}} \
        --db-password {{DB_PASSWORD}} \
        --dry-run

[group('catalog')]
[doc('Load categories from YAML into crawl_targets')]
[working-directory(justfile_directory())]
load-confirm yaml:
    #!/bin/bash
    set -euo pipefail
    uv run scripts/wb-catalog/load_catalog_to_pg.py {{yaml}} \
        --db-host {{DB_HOST}} \
        --db-port {{DB_PORT}} \
        --db-name {{DB_NAME}} \
        --db-user {{DB_USER}} \
        --db-password {{DB_PASSWORD}}

[group('catalog')]
[doc('Load categories only if crawl_targets is empty; reads wb_catalog_targets.yaml by default')]
[working-directory(justfile_directory())]
load-if-empty:
    #!/bin/bash
    set -euo pipefail
    uv run scripts/wb-catalog/load_catalog_if_empty.py \
        --db-host {{DB_HOST}} \
        --db-port {{DB_PORT}} \
        --db-name {{DB_NAME}} \
        --db-user {{DB_USER}} \
        --db-password {{DB_PASSWORD}}

[group('catalog')]
[doc('Backfill root_category_name in the YAML from WBs public category tree')]
[working-directory(justfile_directory())]
fill-roots yaml=CATALOG_YAML:
    #!/bin/bash
    set -euo pipefail
    uv run scripts/wb-catalog/fill_root_categories.py {{yaml}}

[group('catalog')]
[doc('Load WB product-subject names into wb_subjects')]
[working-directory(justfile_directory())]
load-subjects backfill="--backfill":
    #!/bin/bash
    set -euo pipefail
    uv run scripts/wb-catalog/load_subjects.py \
        --db-host {{DB_HOST}} \
        --db-port {{DB_PORT}} \
        --db-name {{DB_NAME}} \
        --db-user {{DB_USER}} \
        --db-password {{DB_PASSWORD}} \
        {{backfill}}

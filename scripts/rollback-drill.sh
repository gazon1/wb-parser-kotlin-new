#!/usr/bin/env bash
# ==============================================================================
# rollback-drill.sh — rehearse the way back, before the day it is needed.
#
# ## What it answers
#
# "If this deploy turns out to be wrong, can we go back?" — and the honest
# answer for a rollback path nobody has walked is "nobody knows". This script
# walks it, against containers on this machine, with the same steps the
# production rollback uses.
#
# ## What it does
#
#   1. starts a PostgreSQL and a crawler with the NEW image, and waits for the
#      catalog to become ready;
#   2. asks the catalog for a page of products and checks the answer is a page;
#   3. swaps the running container to the OLD image — the same command the
#      production rollback runs;
#   4. waits for the catalog to become ready again;
#   5. asks for a page again and checks the answer is a page;
#   6. reports what happened and leaves the containers in the state it found
#      them.
#
# ## Why the second image has to be a different one
#
# A rollback to the same image proves nothing, and a drill that reports success
# in that case is worse than no drill — it would be a green light for a way
# back that was never walked. The script refuses to run with one image twice.
#
# ## What it does not prove
#
# The production route and the production credentials. Those are the deploy
# workflow's business, and this script does not pretend to check them.
#
# ## How to run it
#
#   scripts/rollback-drill.sh
#   scripts/rollback-drill.sh --new-image wb-parser:local --old-image wb-parser:previous
#
# It needs Docker, `curl` and `awk`.
# ==============================================================================

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

NEW_IMAGE="${NEW_IMAGE:-wb-parser:local}"
OLD_IMAGE="${OLD_IMAGE:-}"
API_PORT=18090
NETWORK="mola-rollback-net"
PG_CONTAINER="mola-rollback-pg"
CRAWLER_CONTAINER="mola-rollback-crawler"
CATALOG_KEY="rollback-drill-key"
CATALOG_PASSWORD="rollback-drill-password"
DEMO_ROLE="wb_catalog_ro"

usage() {
    sed -n '3,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    cat <<'EOF'

Options:
  --new-image TAG    The image the deploy would put in place. Default wb-parser:local.
  --old-image TAG    The image the rollback would go back to. Required.
  --keep             Leave the containers running after the drill.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --new-image) NEW_IMAGE="$2"; shift 2 ;;
        --old-image) OLD_IMAGE="$2"; shift 2 ;;
        --keep) KEEP=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if [[ -z "$OLD_IMAGE" ]]; then
    echo "error: --old-image is required — a rollback to the same image proves nothing" >&2
    exit 2
fi

if [[ "$NEW_IMAGE" == "$OLD_IMAGE" ]]; then
    echo "error: the new and the old image are the same ('$NEW_IMAGE')." >&2
    echo "       A rollback to the image that is already running proves nothing." >&2
    exit 2
fi

cleanup() {
    if [[ "${KEEP:-false}" == "true" ]]; then
        echo "containers left running: $PG_CONTAINER, $CRAWLER_CONTAINER"
        return
    fi
    docker rm -f "$CRAWLER_CONTAINER" "$PG_CONTAINER" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

require() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "error: $1 is needed and is not installed" >&2
        exit 1
    }
}
require docker
require curl
require awk

for image in "$NEW_IMAGE" "$OLD_IMAGE"; do
    if ! docker image inspect "$image" >/dev/null 2>&1; then
        echo "error: the image $image does not exist locally." >&2
        echo "       Build it (just app::docker-build) or pull it (docker pull $image)." >&2
        exit 1
    fi
done

API="http://127.0.0.1:${API_PORT}/api/catalog/v1"

# ── The helpers ───────────────────────────────────────────────────────────────

# Start the crawler with a given image. This is the command the production
# rollback runs as well: a container replaced by another one.
start_crawler() {
    local image="$1"
    docker rm -f "$CRAWLER_CONTAINER" >/dev/null 2>&1 || true
    docker run -d --rm \
        --name "$CRAWLER_CONTAINER" \
        --network "$NETWORK" \
        --network-alias crawler \
        -p "${API_PORT}:8080" \
        -e DB_HOST=pg \
        -e DB_PORT=5432 \
        -e DB_NAME=wbparser \
        -e DB_USER=postgres \
        -e DB_PASSWORD=postgres \
        -e ADMIN_API_KEY=rollback-drill-admin-key \
        -e CATALOG_API_KEY="$CATALOG_KEY" \
        -e CATALOG_DB_USER="$DEMO_ROLE" \
        -e CATALOG_DB_PASSWORD="$CATALOG_PASSWORD" \
        -e CATALOG_RATE_LIMIT_ENABLED=false \
        "$image" >/dev/null
}

# Wait for the catalog's own readiness statement. The probe is the crawler's:
# its pool can run a statement, and the columns the catalog reads are there.
wait_for_ready() {
    local deadline=$(( $(date +%s) + 180 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -fsS -m 3 -H "X-Api-Key: $CATALOG_KEY" "$API/ready" 2>/dev/null | grep -q '"status":"UP"'; then
            return 0
        fi
        sleep 2
    done
    return 1
}

# Ask for a page and check the answer is a page of products. The check is
# deliberately about the *content*: an error body is a JSON object too, and a
# check that only looked for "a JSON document" would pass on a 500 — and an
# empty page would pass against a database that holds nothing.
ask_for_a_page() {
    local body
    body="$(curl -fsS -m 20 -H "X-Api-Key: $CATALOG_KEY" "$API/items?page=0&pageSize=24" 2>/dev/null)" || {
        echo "    the catalog did not answer"
        return 1
    }
    if ! echo "$body" | grep -q '"items"'; then
        echo "    the answer was not a page of items: ${body:0:200}"
        return 1
    fi
    local count
    count="$(echo "$body" | grep -o '"productUrl"' | wc -l | tr -d ' ')"
    if [ "$count" -eq 0 ]; then
        echo "    the page was empty — a rollback against an empty catalog proves nothing"
        return 1
    fi
    echo "    the catalog answered with a page of $count items"
    return 0
}

# ── The drill ─────────────────────────────────────────────────────────────────

echo "→ starting PostgreSQL"
docker network create "$NETWORK" >/dev/null
docker run -d --rm \
    --name "$PG_CONTAINER" \
    --network "$NETWORK" \
    --network-alias pg \
    -e POSTGRES_PASSWORD=postgres \
    -e POSTGRES_DB=wbparser \
    postgres:16-alpine >/dev/null

until docker exec "$PG_CONTAINER" pg_isready -U postgres -d wbparser >/dev/null 2>&1; do
    sleep 1
done

echo "→ starting the crawler with the new image ($NEW_IMAGE)"
start_crawler "$NEW_IMAGE"

until curl -fsS -m 2 "http://127.0.0.1:${API_PORT}/api/health/live" >/dev/null 2>&1; do
    sleep 1
done

# The role is provisioned after the crawler has started, and not before: the
# script refuses to grant rights on tables that do not exist, and the crawler
# is what creates them. The same order as `scripts/load-catalog.sh`.
echo "→ provisioning the catalog role"
docker exec "$PG_CONTAINER" mkdir -p /tmp/db/roles
docker cp "$ROOT/db/roles/provision-catalog-readonly-role.sql" "$PG_CONTAINER:/tmp/db/roles/provision-catalog-readonly-role.sql" >/dev/null
docker cp "$ROOT/db/roles/catalog-readonly-role.sql" "$PG_CONTAINER:/tmp/db/roles/catalog-readonly-role.sql" >/dev/null
docker exec "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -v "catalog_password=$CATALOG_PASSWORD" \
    -U postgres -d wbparser -f /tmp/db/roles/provision-catalog-readonly-role.sql >/dev/null

# A few observations, so that the drill asks for a page and gets one. An empty
# catalog would answer a page too, and a drill that only checked the shape would
# pass against a database that holds nothing — which is exactly the database a
# rollback must not be trusted with.
docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U postgres -d wbparser >/dev/null <<SQL
INSERT INTO crawl_targets (id, name, start_url, max_depth, is_active, root_category_name)
VALUES ('11111111-1111-1111-1111-111111111111', 'Откат', 'https://example.invalid/c', 1, true, 'Откат');

INSERT INTO scraped_items (
    id, target_id, catalog_url, product_url, brand, seller,
    price_kopecks, title, product_id, cashback, cashback_percent,
    data, content_hash, scraped_at, match_id,
    sale_price_kopecks, in_stock, image_url
)
SELECT
    gen_random_uuid(),
    '11111111-1111-1111-1111-111111111111',
    'https://example.invalid/catalog',
    'https://example.invalid/product/' || n,
    'Бренд ' || n,
    'Продавец ' || n,
    1000 + n,
    'Товар ' || n,
    n,
    10, 5,
    '{"demo": false}'::jsonb,
    md5('rollback:' || n),
    now() - (n || ' seconds')::interval,
    n,
    900 + n,
    (n % 4) <> 0,
    'https://images.example.invalid/' || n || '.jpg'
FROM generate_series(1, 12) AS n;
SQL

echo "→ waiting for the catalog to become ready"
if ! wait_for_ready; then
    echo "❌ the new image never became ready — the drill cannot go further" >&2
    exit 1
fi

echo "→ asking the new image for a page"
if ! ask_for_a_page; then
    echo "❌ the new image does not serve the catalog — the drill cannot go further" >&2
    exit 1
fi

echo "→ rolling back to the old image ($OLD_IMAGE)"
start_crawler "$OLD_IMAGE"

until curl -fsS -m 2 "http://127.0.0.1:${API_PORT}/api/health/live" >/dev/null 2>&1; do
    sleep 1
done

echo "→ waiting for the catalog to become ready again"
if ! wait_for_ready; then
    echo "❌ the old image never became ready — the rollback does not work" >&2
    exit 1
fi

echo "→ asking the old image for a page"
if ! ask_for_a_page; then
    echo "❌ the old image does not serve the catalog — the rollback does not work" >&2
    exit 1
fi

echo
echo "✅ the rollback drill passed: the old image ($OLD_IMAGE) serves the catalog again"
echo "   the storefront's contract still holds against it — it reads a page"

#!/usr/bin/env bash
#
# The catalog's load test.
#
# ## What it answers
#
# "How fast is the catalog API when the database holds a real catalog, and does it hold
# up when more than one request arrives at once?"
#
# The numbers the plan needs this for are concrete: the catalog pool's size (five
# connections), the statement timeout (eight seconds) and the connection budget of the
# database as a whole. All three are guesses until something measures them, and a guess
# that is too small makes the storefront slow while a guess that is too big takes the
# crawler's own connections away.
#
# ## Why the volume is filled in, not mocked
#
# A thousand rows of demo data is not a catalog. The query plans are different — the
# sequential scans of the demo data become index scans at a hundred thousand rows, and the
# count that is free at a thousand rows is a full pass over the table at a million. The
# script therefore fills the table with as many rows as it is told to, and the number is
# the first argument.
#
# ## What it prints
#
# The percentiles of the request time (p50, p90, p95, p99 and the maximum), the number of
# failed requests, and the catalog pool's gauges before and after the run — how many
# connections were in use, how many were waiting. A pool that is saturated shows up as
# pending connections, and pending connections are the shape of a slow API.
#
# ## How to run it
#
#   scripts/load-catalog.sh                       # 200k rows, 8 requests at a time
#   scripts/load-catalog.sh --rows 500000 --concurrency 32
#   scripts/load-catalog.sh --keep                # leave the containers running
#
# The script needs Docker, `curl` and `awk`. It starts its own PostgreSQL and its own
# crawler, and it removes both when it is done.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ── The settings, and why they are what they are ───────────────────────────────

ROWS=200000
CONCURRENCY=8
REQUESTS=400
PAGE_SIZE=24
IMAGE="wb-parser:local"
KEEP=false
SWEEP=false
API_PORT=18080
NETWORK="mola-load-net"
PG_CONTAINER="mola-load-pg"
CRAWLER_CONTAINER="mola-load-crawler"
CATALOG_KEY="load-test-catalog-key"
CATALOG_PASSWORD="load-test-catalog-password"
DEMO_ROLE="wb_catalog_ro"

usage() {
    sed -n '3,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    cat <<'EOF'

Options:
  --rows N         How many observations to fill the table with. Default 200000.
  --concurrency N  How many requests are in flight at once. Default 8.
  --requests N     How many requests to make in total. Default 400.
  --page-size N    The page size every request asks for. Default 24.
  --image TAG      The crawler image to measure. Default wb-parser:local.
  --keep           Leave the containers running after the run.
  --sweep          Measure the whole concurrency curve (1, 4, 8, 16, 32) instead of
                   a single point.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rows) ROWS="$2"; shift 2 ;;
        --concurrency) CONCURRENCY="$2"; shift 2 ;;
        --requests) REQUESTS="$2"; shift 2 ;;
        --page-size) PAGE_SIZE="$2"; shift 2 ;;
        --image) IMAGE="$2"; shift 2 ;;
        --keep) KEEP=true; shift ;;
        --sweep) SWEEP=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

# ── The housekeeping ────────────────────────────────────────────────────────────

cleanup() {
    if [[ "$KEEP" == "true" ]]; then
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

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "error: the image $IMAGE does not exist — build it with 'just app::docker-build'" >&2
    exit 1
fi

# ── The database and the crawler ────────────────────────────────────────────────

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

# The rate limit is off for the measurement: a limiter that answers 429 halfway through
# would make the measurement a statement about the limiter.
echo "→ starting the crawler ($IMAGE)"
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
    -e ADMIN_API_KEY=load-test-admin-key \
    -e CATALOG_API_KEY="$CATALOG_KEY" \
    -e CATALOG_DB_USER="$DEMO_ROLE" \
    -e CATALOG_DB_PASSWORD="$CATALOG_PASSWORD" \
    -e CATALOG_RATE_LIMIT_ENABLED=false \
    "$IMAGE" >/dev/null

API="http://127.0.0.1:${API_PORT}/api/catalog/v1"

echo "→ waiting for the crawler to migrate the schema"
until curl -fsS -m 2 "http://127.0.0.1:${API_PORT}/api/health/live" >/dev/null 2>&1; do
    sleep 1
done

# The catalog role is created by the operator's script, not by the application: the
# application's job is to read through it. The script that an operator runs is the script
# that is run here.
echo "→ provisioning the catalog role"
# The provisioning script includes the pure-SQL file that holds the rights, and psql
# resolves an include relative to the *including script's* directory. Both files have to
# keep their names and their place in the tree, which is why they are copied into a
# directory with the same shape rather than into /tmp.
docker exec "$PG_CONTAINER" mkdir -p /tmp/db/roles
docker cp "$ROOT/db/roles/provision-catalog-readonly-role.sql" "$PG_CONTAINER:/tmp/db/roles/provision-catalog-readonly-role.sql" >/dev/null
docker cp "$ROOT/db/roles/catalog-readonly-role.sql" "$PG_CONTAINER:/tmp/db/roles/catalog-readonly-role.sql" >/dev/null
docker exec "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -v "catalog_password=$CATALOG_PASSWORD" \
    -U postgres -d wbparser -f /tmp/db/roles/provision-catalog-readonly-role.sql >/dev/null

echo "→ filling the table with $ROWS observations"
docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U postgres -d wbparser >/dev/null <<SQL
INSERT INTO crawl_targets (id, name, start_url, max_depth, is_active, root_category_name, created_at, updated_at)
VALUES ('11111111-1111-1111-1111-111111111111', 'Нагрузка', 'https://example.invalid/c', 1, true, 'Нагрузка', now(), now());

INSERT INTO scraped_items (
    id, target_id, catalog_url, product_url, brand, seller,
    price_kopecks, title, product_id, cashback, cashback_percent,
    data, content_hash, scraped_at, match_id, catalog_name,
    sale_price_kopecks, in_stock, image_url
)
SELECT
    gen_random_uuid(),
    '11111111-1111-1111-1111-111111111111',
    'https://example.invalid/catalog',
    'https://example.invalid/product/' || n,
    'Бренд ' || (n % 500),
    'Продавец ' || (n % 200),
    1000 + (n % 90000),
    'Товар ' || n,
    n,
    round((n % 4000) / 100.0, 2),
    (n % 30),
    '{"demo": false}'::jsonb,
    md5('load:' || n),
    now() - (n || ' seconds')::interval,
    n,
    'Нагрузка',
    1000 + (n % 70000),
    (n % 4) <> 0,
    'https://images.example.invalid/' || (n % 1000) || '.jpg'
FROM generate_series(1, $ROWS) AS n;

ANALYZE scraped_items;
SQL

echo "→ waiting for the catalog to become ready"
until curl -fsS -m 2 -H "X-Api-Key: $CATALOG_KEY" "$API/ready" | grep -q '"status":"UP"'; do
    sleep 1
done

# ── The measurement ─────────────────────────────────────────────────────────────

pool_gauge() {
    # The actuator's answer is JSON: the gauge's value is the first "value" in it.
    curl -fsS -m 5 "http://127.0.0.1:${API_PORT}/actuator/metrics/$1" 2>/dev/null \
        | grep -o '"value":[0-9.eE+-]*' | head -1 | cut -d: -f2
}

ACTIVE_BEFORE="$(pool_gauge hikaricp.connections.active)"
PENDING_BEFORE="$(pool_gauge hikaricp.connections.pending)"

TOTAL_PAGES=$(( (ROWS + PAGE_SIZE - 1) / PAGE_SIZE ))
echo "→ $REQUESTS requests, $CONCURRENCY at a time, over $TOTAL_PAGES pages"

TIMES="$(mktemp)"
ERRORS="$(mktemp)"
GAUGES="$(mktemp)"
trap 'rm -f "$TIMES" "$ERRORS" "$GAUGES"; cleanup' EXIT

percentile() {
    local wanted="$1"
    local total
    total="$(wc -l < "$TIMES")"
    if [[ "$total" -eq 0 ]]; then
        echo "-"
        return
    fi
    local index
    index=$(( (wanted * total + 99) / 100 ))
    sort -n "$TIMES" | awk -v i="$index" 'NR==i {printf "%.0f ms", $1 * 1000}'
}

export API CATALOG_KEY PAGE_SIZE TOTAL_PAGES TIMES ERRORS GAUGES

# One measurement: `CONCURRENCY` requests at a time, `REQUESTS` in total.
#
# The pool's gauges are read *during* the run. Read before and after, they would say
# "idle" both times — the interesting moment is the one where requests are waiting for
# a connection, and it is over by the time the last request is finished.
run_load() {
    local concurrency="$1"
    local requests="$2"
    : > "$TIMES"
    : > "$ERRORS"
    : > "$GAUGES"

    (
        while true; do
            printf '%s %s\n' "$(pool_gauge hikaricp.connections.active)" "$(pool_gauge hikaricp.connections.pending)" >> "$GAUGES"
            sleep 1
        done
    ) &
    local gauge_pid=$!

    local started_at
    started_at="$(date +%s.%N)"

    seq 1 "$requests" | xargs -P "$concurrency" -I{} bash -c '
        page=$(( RANDOM % TOTAL_PAGES ))
        code=$(curl -sS -o /dev/null -m 30 -w "%{http_code} %{time_total}" \
            -H "X-Api-Key: ${CATALOG_KEY}" \
            "${API}/items?page=${page}&pageSize=${PAGE_SIZE}" 2>/dev/null || echo "000 0")
        status="${code%% *}"
        time="${code##* }"
        if [[ "$status" == "200" ]]; then
            echo "$time" >> "$TIMES"
        else
            echo "$status" >> "$ERRORS"
        fi
    '

    local finished_at
    finished_at="$(date +%s.%N)"
    kill "$gauge_pid" 2>/dev/null || true

    local active pending throughput
    active="$(awk '{if ($1 + 0 > max) max = $1 + 0} END {printf "%.0f", max}' "$GAUGES")"
    pending="$(awk '{if ($2 + 0 > max) max = $2 + 0} END {printf "%.0f", max}' "$GAUGES")"
    throughput="$(awk -v n="$requests" -v s="$started_at" -v e="$finished_at" 'BEGIN {printf "%.1f", n / (e - s)}')"

    printf '  %-4s %-8s %-10s %-8s %-8s %-8s %-10s %s\n' \
        "$concurrency" "$requests" "${throughput} req/s" \
        "$(percentile 50)" "$(percentile 95)" "$(percentile 99)" \
        "active ${active}" "pending ${pending}"
}

# ── The report ──────────────────────────────────────────────────────────────────

echo
echo "catalog load test"
echo "  rows            $ROWS"
echo "  page size       $PAGE_SIZE"
echo
printf '  %-4s %-9s %-11s %-9s %-9s %-9s %-12s %s\n' "conc" "requests" "throughput" "p50" "p95" "p99" "pool active" "pool pending"
printf '  %-4s %-9s %-11s %-9s %-9s %-9s %-12s %s\n' "----" "---------" "-----------" "---------" "---------" "---------" "------------" "------------"
if [[ "$SWEEP" == "true" ]]; then
    # The curve, not a point: the interesting shape is where the throughput stops
    # growing while the latency keeps growing, and one measurement cannot show it.
    for level in 1 4 8 16 32; do
        run_load "$level" $(( level * 25 ))
    done
else
    run_load "$CONCURRENCY" "$REQUESTS"
fi
echo
echo "The pool's size, the statement timeout and the database's connection budget are"
echo "read off this: a run with pending connections and a p99 near the timeout is a pool"
echo "that is too small, and a run with an idle pool is a pool that is larger than the"
echo "work needs."
echo
echo "The throughput usually stops growing while the latency keeps growing — that is the"
echo "pool running out of connections, and it is where its size becomes a decision"
echo "rather than a default."

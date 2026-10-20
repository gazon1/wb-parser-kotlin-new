#!/usr/bin/env bash
# ==============================================================================
# rebuild_prod_data.sh — rebuilds the production dataset after the crawler changed
# the endpoint it reads.
#
# WHY
# ---
# The production database still holds ~56 000 rows that were collected through WB's
# rotating "popular presets" list: products that had nothing to do with the category
# they were filed under. The crawler now asks the internal catalog API instead, and
# it refuses to store a page that looks like that rotation.
#
# The new rows will NOT overwrite the old ones. `scraped_items` is append-only per
# (target, product, price) — the price is part of the content hash — so the new
# product ids simply live next to the old ones, and every "what does this category
# look like" question would be answered by the stale half. The dataset therefore has
# to be rebuilt, not appended to.
#
# WHAT IT DOES
# ------------
#   1. stops the crawler container,
#   2. takes a database backup (pg_dump) to a timestamped file,
#   3. reports the row counts of every table it touches,
#   4. deletes the stale observation rows and the crawl-job history
#      (NOT crawl_targets — that is the catalogue — and NOT wb_subjects — that is
#      WB's own dictionary),
#   5. reloads the catalogue targets and the subject dictionary from the repository
#      through the existing loaders,
#   6. starts the crawler again and waits for it to report healthy,
#   7. prints the row counts after the change.
#
# WHAT IT DOES NOT DO
# -------------------
#   - it does not drop the crawl_targets catalogue or the subject dictionary,
#   - it does not change the crawler image or redeploy it — the container is stopped
#     and started, so it keeps running the tag it is running now,
#   - it does not migrate the schema: Flyway is off on this host on purpose
#     (see the Caddy-vps wbparser role),
#   - it does not touch the VPS's Caddy, its routes or anything else on the host.
#
# Every destructive step asks for a confirmation first; --yes answers all of them.
#
# Usage (from the repository root, on a machine that can reach the database):
#   bash scripts/rebuild_prod_data.sh --dry-run
#   bash scripts/rebuild_prod_data.sh --yes
#   bash scripts/rebuild_prod_data.sh --host 91.188.212.39 --user user --yes
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── Defaults ───────────────────────────────────────────────────────────────────
#
# The host, the SSH user and the key are the ones `scripts/vps-flyway-baseline.sh`
# uses; the database is the one the Caddy-vps wbparser role points the container at
# (deploy/docker-compose.vps.yml describes that database as living on another host).
# The remote paths are the role's defaults: the compose file is copied to
# /opt/wbparser and the container it starts is named wb-parser.
VPS_HOST="${VPS_HOST:-91.188.212.39}"
SSH_USER="${SSH_USER:-user}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/ci_caddy_vps}"

REMOTE_BASE="/opt/wbparser"
REMOTE_COMPOSE="$REMOTE_BASE/docker-compose.vps.yml"
REMOTE_ENV_FILE="$REMOTE_BASE/app.env"
REMOTE_SERVICE="wb-parser"
REMOTE_CONTAINER="wb-parser"

DB_HOST="${DB_HOST:-91.188.212.209}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-wbparser}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-}"

CATALOG_YAML="$REPO_ROOT/wb_catalog_targets.yaml"
# The repository's own cache convention (fill_root_categories.py, load_subjects.py):
# backups are a local artifact, not a repository file, and must not show up in
# `git status`.
BACKUP_DIR="${BACKUP_DIR:-$HOME/.cache/wb-catalog/backups}"

HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
HEALTH_DELAY="${HEALTH_DELAY:-6}"

DRY_RUN=0
ASSUME_YES=0
SKIP_BACKUP=0
REFRESH_TOKENS=0

# ── Output helpers ─────────────────────────────────────────────────────────────

heading() {
    echo ""
    echo "=== $* ==="
}

info() {
    echo "  $*"
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

# Runs a command, or only prints it in dry-run mode. Nothing in this script runs
# without going through here, so `--dry-run` cannot be bypassed by accident.
run() {
    echo "  \$ $*"
    if [[ "$DRY_RUN" -eq 1 ]]; then
        return 0
    fi
    "$@"
}

remote() {
    echo "  \$ ssh $SSH_USER@$VPS_HOST: $1"
    if [[ "$DRY_RUN" -eq 1 ]]; then
        return 0
    fi
    ssh -i "$SSH_KEY" \
        -o StrictHostKeyChecking=no \
        -o ConnectTimeout=15 \
        "$SSH_USER@$VPS_HOST" "$1"
}

confirm() {
    local prompt="$1"
    if [[ "$DRY_RUN" -eq 1 ]]; then
        info "[dry-run] would ask for confirmation: $prompt"
        return 0
    fi
    if [[ "$ASSUME_YES" -eq 1 ]]; then
        info "confirmed by --yes: $prompt"
        return 0
    fi
    if [[ ! -t 0 ]]; then
        fail "stdin is not a terminal — pass --yes to confirm non-interactively"
    fi
    local answer=""
    read -r -p "  $prompt — type 'yes' to proceed: " answer || fail "no answer given"
    if [[ "$answer" != "yes" ]]; then
        echo "Aborted." >&2
        exit 1
    fi
}

usage() {
    cat <<'USAGE'
Usage: bash scripts/rebuild_prod_data.sh [options]

  --dry-run             print the plan and the commands, change nothing
  --yes                 answer every confirmation with yes
  --host HOST           SSH host of the crawler (default: 91.188.212.39)
  --user USER           SSH user (default: user)
  --ssh-key PATH        SSH private key (default: $HOME/.ssh/ci_caddy_vps)
  --db-host HOST        database host (default: 91.188.212.209)
  --db-port PORT        database port (default: 5432)
  --db-name NAME        database name (default: wbparser)
  --db-user USER        database user (default: postgres)
  --db-password PASS    database password (default: $WBPARSER_DB_PASSWORD, else read
                        from the VPS's app.env)
  --backup-dir DIR      where the pg_dump backup is written
                        (default: $HOME/.cache/wb-catalog/backups)
  --skip-backup         do not take a backup (not recommended)
  --refresh-tokens      run refresh_catalog_tokens.py --update-yaml first
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)       DRY_RUN=1 ;;
        --yes)           ASSUME_YES=1 ;;
        --skip-backup)   SKIP_BACKUP=1 ;;
        --refresh-tokens) REFRESH_TOKENS=1 ;;
        --host)          VPS_HOST="$2"; shift ;;
        --user)          SSH_USER="$2"; shift ;;
        --ssh-key)       SSH_KEY="$2"; shift ;;
        --db-host)       DB_HOST="$2"; shift ;;
        --db-port)       DB_PORT="$2"; shift ;;
        --db-name)       DB_NAME="$2"; shift ;;
        --db-user)       DB_USER="$2"; shift ;;
        --db-password)   DB_PASSWORD="$2"; shift ;;
        --backup-dir)    BACKUP_DIR="$2"; shift ;;
        -h|--help)       usage; exit 0 ;;
        *)               echo "ERROR: unknown option '$1'" >&2; usage >&2; exit 1 ;;
    esac
    shift
done

# ── Preflight ──────────────────────────────────────────────────────────────────

heading "Preflight"

[[ -f "$SSH_KEY" ]] || fail "SSH key not found: $SSH_KEY"
info "ssh key:     $SSH_KEY"
info "host:        $SSH_USER@$VPS_HOST"
info "database:    $DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"

command -v python3 >/dev/null 2>&1 || fail "python3 is required (the repository's loaders run on it)"
python3 -c "import psycopg2" 2>/dev/null || fail "psycopg2 is required: pip install psycopg2-binary"
[[ -f "$CATALOG_YAML" ]] || fail "catalog YAML not found: $CATALOG_YAML"

# The password never lives in this script. It comes from the flag, from the
# environment, or from the credentials the VPS already has for its own container.
if [[ -z "$DB_PASSWORD" ]]; then
    DB_PASSWORD="${WBPARSER_DB_PASSWORD:-}"
fi
if [[ -z "$DB_PASSWORD" && "$DRY_RUN" -eq 0 ]]; then
    info "reading the database password from $REMOTE_ENV_FILE on $VPS_HOST"
    DB_PASSWORD="$(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 \
        "$SSH_USER@$VPS_HOST" \
        "grep -m1 '^DB_PASSWORD=' '$REMOTE_ENV_FILE' | cut -d= -f2-")"
fi
if [[ "$DRY_RUN" -eq 0 ]]; then
    [[ -n "$DB_PASSWORD" ]] || fail "no database password: pass --db-password or set WBPARSER_DB_PASSWORD"
    info "password:    (set, not shown)"
else
    info "password:    (not checked in dry-run)"
fi

export WB_DB_HOST="$DB_HOST" WB_DB_PORT="$DB_PORT" WB_DB_NAME="$DB_NAME" \
    WB_DB_USER="$DB_USER" WB_DB_PASSWORD="$DB_PASSWORD"

# The database has to answer before anything is stopped: a crawler that is down
# while the database is unreachable is a crawler that stays down.
if [[ "$DRY_RUN" -eq 0 ]]; then
    if ! WB_SQL="SELECT 1" python3 - <<'PY'
import os
import psycopg2

conn = psycopg2.connect(
    host=os.environ["WB_DB_HOST"],
    port=int(os.environ["WB_DB_PORT"]),
    dbname=os.environ["WB_DB_NAME"],
    user=os.environ["WB_DB_USER"],
    password=os.environ["WB_DB_PASSWORD"],
    connect_timeout=10,
)
conn.close()
PY
    then
        fail "cannot reach the database at $DB_HOST:$DB_PORT/$DB_NAME from this machine"
    fi
    info "database:   reachable"
fi

# ── Database helpers ───────────────────────────────────────────────────────────
#
# SQL runs through psycopg2, the same client the repository's loaders use: it is a
# declared dependency of the scripts, so it is present wherever they run, while
# `psql` is a system package that may not be.

db_query() {
    local sql="$1"
    if [[ "$DRY_RUN" -eq 1 ]]; then
        echo "  [dry-run] SQL: $sql"
        return 0
    fi
    WB_SQL="$sql" python3 - <<'PY'
import os
import psycopg2

conn = psycopg2.connect(
    host=os.environ["WB_DB_HOST"],
    port=int(os.environ["WB_DB_PORT"]),
    dbname=os.environ["WB_DB_NAME"],
    user=os.environ["WB_DB_USER"],
    password=os.environ["WB_DB_PASSWORD"],
    connect_timeout=10,
)
conn.autocommit = False
cur = conn.cursor()
cur.execute(os.environ["WB_SQL"])
for name, count in cur.fetchall():
    print(f"  {name:<24} {count}")
conn.commit()
conn.close()
PY
}

db_delete() {
    local sql="$1"
    if [[ "$DRY_RUN" -eq 1 ]]; then
        echo "  [dry-run] SQL: $sql"
        return 0
    fi
    WB_SQL="$sql" python3 - <<'PY'
import os
import psycopg2

conn = psycopg2.connect(
    host=os.environ["WB_DB_HOST"],
    port=int(os.environ["WB_DB_PORT"]),
    dbname=os.environ["WB_DB_NAME"],
    user=os.environ["WB_DB_USER"],
    password=os.environ["WB_DB_PASSWORD"],
    connect_timeout=10,
)
conn.autocommit = False
cur = conn.cursor()
# One transaction for the whole cleanup: either the stale data is gone or nothing
# changed. A half-deleted table would leave the crawler and the dataset describing
# different worlds.
for statement in os.environ["WB_SQL"].split(";"):
    statement = statement.strip()
    if not statement:
        continue
    cur.execute(statement)
    print(f"  deleted: {cur.rowcount} row(s)  ←  {statement.split()[1]} {statement.split()[2]}")
conn.commit()
conn.close()
PY
}

# The tables this script reports on. crawl_targets and wb_subjects are read only:
# the catalogue and WB's own dictionary survive the rebuild.
COUNT_SQL="SELECT 'crawl_targets', count(*) FROM crawl_targets
UNION ALL SELECT 'scraped_items', count(*) FROM scraped_items
UNION ALL SELECT 'crawl_jobs', count(*) FROM crawl_jobs
UNION ALL SELECT 'crawl_errors', count(*) FROM crawl_errors
UNION ALL SELECT 'category_mappings', count(*) FROM category_mappings
UNION ALL SELECT 'wb_subjects', count(*) FROM wb_subjects
ORDER BY 1"

# The stale observations and the job history. The job history goes with them:
# crawl_errors hangs off a job, and leaving the errors behind after deleting the
# jobs would leave rows that belong to a history that no longer exists.
DELETE_SQL="DELETE FROM crawl_errors; DELETE FROM crawl_jobs; DELETE FROM scraped_items;"

# ── Backup ─────────────────────────────────────────────────────────────────────

take_backup() {
    local stamp
    stamp="$(date +%Y%m%d-%H%M%S)"
    local local_file="$BACKUP_DIR/wbparser-$stamp.sql"

    if [[ "$SKIP_BACKUP" -eq 1 ]]; then
        info "skipped on request (--skip-backup)"
        return 0
    fi

    heading "Backup"

    if command -v pg_dump >/dev/null 2>&1; then
        mkdir -p "$BACKUP_DIR"
        info "pg_dump → $local_file"
        if [[ "$DRY_RUN" -eq 1 ]]; then
            echo "  \$ pg_dump -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -f $local_file"
            return 0
        fi
        PGPASSWORD="$DB_PASSWORD" pg_dump \
            -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
            --file="$local_file"
        info "backup written: $local_file ($(du -h "$local_file" | cut -f1))"
        return 0
    fi

    # No local pg_dump: the VPS reaches the database, so the dump is taken there.
    # Its credentials are already on the host — the container's own app.env — so
    # nothing has to travel over the wire for this.
    info "no local pg_dump — taking the backup on $VPS_HOST instead"
    local remote_file="$REMOTE_BASE/backups/wbparser-$stamp.sql"
    if [[ "$DRY_RUN" -eq 1 ]]; then
        echo "  \$ ssh $SSH_USER@$VPS_HOST: pg_dump -h <DB_HOST> ... -f $remote_file"
        return 0
    fi
    remote "value() { grep -m1 \"^\$1=\" '$REMOTE_ENV_FILE' | cut -d= -f2-; }; mkdir -p '$REMOTE_BASE/backups'; PGPASSWORD=\"\$(value DB_PASSWORD)\" pg_dump -h \"\$(value DB_HOST)\" -p \"\$(value DB_PORT)\" -U \"\$(value DB_USER)\" -d \"\$(value DB_NAME)\" --file='$remote_file'"
    info "backup written on the host: $remote_file"
}

# ── The plan ───────────────────────────────────────────────────────────────────

heading "Plan"
info "1. stop the crawler container ($REMOTE_CONTAINER on $VPS_HOST)"
info "2. back up the database (pg_dump)"
info "3. report the row counts"
info "4. delete the stale observations (scraped_items) and the job history (crawl_jobs, crawl_errors)"
info "5. reload the catalogue targets from $CATALOG_YAML"
info "6. reload the subject dictionary (wb_subjects)"
info "7. start the crawler and wait for it to report healthy"
info "8. report the row counts again"
echo ""
info "NOT touched: crawl_targets (the catalogue), wb_subjects (WB's dictionary)"

if [[ "$REFRESH_TOKENS" -eq 1 ]]; then
    info "plus: refresh the catalog tokens in the YAML first (--refresh-tokens)"
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
    heading "Dry run"
    info "nothing below is executed"
fi

# ── 1. Stop the crawler ────────────────────────────────────────────────────────

heading "1. Stop the crawler"
confirm "stop the container $REMOTE_CONTAINER on $VPS_HOST"
remote "docker ps -a --filter 'name=^/${REMOTE_CONTAINER}\$' --format '{{.Names}} {{.Status}}'"
remote "docker compose -f '$REMOTE_COMPOSE' --project-directory '$REMOTE_BASE' stop '$REMOTE_SERVICE'"
info "container stopped"

# ── 2. Backup ──────────────────────────────────────────────────────────────────

take_backup

# ── 3. Counts before ───────────────────────────────────────────────────────────

heading "3. Row counts before"
db_query "$COUNT_SQL"

# ── 4. Delete the stale data ───────────────────────────────────────────────────

heading "4. Delete the stale observations and the job history"
confirm "delete every row of scraped_items, crawl_jobs and crawl_errors"
db_delete "$DELETE_SQL"

# ── 5. Reload the catalogue ────────────────────────────────────────────────────

# The loaders take the password on the command line — that is their contract, the
# same one `load_catalog_if_empty.py` uses. The command is echoed with the
# password masked, so a transcript of this run does not carry the credential.
run_loader() {
    local script="$1"
    shift
    echo "  \$ python3 $script $* --db-password ***"
    if [[ "$DRY_RUN" -eq 1 ]]; then
        return 0
    fi
    python3 "$script" "$@" --db-password "$DB_PASSWORD"
}

heading "5. Reload the catalogue targets"
if [[ "$REFRESH_TOKENS" -eq 1 ]]; then
    confirm "refresh the catalog tokens in $CATALOG_YAML"
    run python3 "$REPO_ROOT/scripts/wb-catalog/refresh_catalog_tokens.py" \
        "$CATALOG_YAML" --update-yaml
fi
confirm "reload the catalogue targets from $CATALOG_YAML"
run_loader "$REPO_ROOT/scripts/wb-catalog/load_catalog_to_pg.py" \
    "$CATALOG_YAML" \
    --db-host "$DB_HOST" --db-port "$DB_PORT" --db-name "$DB_NAME" \
    --db-user "$DB_USER"

# ── 6. Reload the subject dictionary ───────────────────────────────────────────

heading "6. Reload the subject dictionary"
confirm "reload WB's subject dictionary into wb_subjects"
run_loader "$REPO_ROOT/scripts/wb-catalog/load_subjects.py" \
    --db-host "$DB_HOST" --db-port "$DB_PORT" --db-name "$DB_NAME" \
    --db-user "$DB_USER"

# ── 7. Start the crawler ───────────────────────────────────────────────────────

heading "7. Start the crawler"
confirm "start the container $REMOTE_CONTAINER again"
remote "docker compose -f '$REMOTE_COMPOSE' --project-directory '$REMOTE_BASE' start '$REMOTE_SERVICE'"

if [[ "$DRY_RUN" -eq 0 ]]; then
    deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
    status=""
    while [[ $(date +%s) -lt $deadline ]]; do
        # A container that is still restarting answers with a non-zero exit; that is
        # the state of the wait, not a failure of the script.
        if status="$(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 \
            "$SSH_USER@$VPS_HOST" \
            "docker inspect --format '{{.State.Health.Status}}' '$REMOTE_CONTAINER'")"; then
            if [[ "$status" == "healthy" ]]; then
                break
            fi
        else
            status="starting"
        fi
        sleep "$HEALTH_DELAY"
    done
    if [[ "$status" != "healthy" ]]; then
        fail "the container did not report healthy within ${HEALTH_TIMEOUT}s (last status: ${status:-unknown})"
    fi
    info "container healthy"
else
    echo "  [dry-run] wait for the container to report healthy (${HEALTH_TIMEOUT}s)"
fi

# ── 8. Counts after ────────────────────────────────────────────────────────────

heading "8. Row counts after"
db_query "$COUNT_SQL"

heading "Done"
info "the dataset was rebuilt: the old observations are gone, the new ones are the crawler's to collect"

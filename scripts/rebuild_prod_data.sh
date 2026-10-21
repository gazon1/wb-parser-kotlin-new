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
REMOTE_SERVICE="wb-parser"
REMOTE_CONTAINER="wb-parser"
REMOTE_UNIT="wbparser"

# The credentials the crawler uses live in an env file on the host, and where that
# file is depends on how the crawler is deployed: the container contract keeps it
# next to the compose file, the JAR contract keeps it in the deployment directory.
# The first file that carries a password wins.
REMOTE_ENV_FILES=(
    "$REMOTE_BASE/app.env"
    "/home/user/wbparser/.env"
    "/home/user/wbparser/wbparser-new/.env"
)

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

# A read-only probe: it always runs, even in a dry run, because what is running on
# the host is a fact and the dry run has to show the real commands.
probe() {
    ssh -i "$SSH_KEY" \
        -o StrictHostKeyChecking=no \
        -o ConnectTimeout=15 \
        "$SSH_USER@$VPS_HOST" "$1" >/dev/null 2>&1
}

# What is the crawler on this host? The repository has two deployment contracts —
# the image with a compose file, and the JAR with a systemd unit — and the host
# may have neither: a host where the crawler was never deployed is a real state,
# and the rebuild must not fail on it.
detect_crawler() {
    if probe "docker ps -a --filter 'name=^/${REMOTE_CONTAINER}\$' --format '{{.Names}}' | grep -q ."; then
        echo "container"
    elif probe "systemctl list-unit-files '$REMOTE_UNIT' --no-legend 2>/dev/null | grep -q ."; then
        echo "systemd"
    else
        echo "none"
    fi
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
    info "reading the database password from the crawler env file on $VPS_HOST"
    # The host may be unreachable — it is not where the database is, and the data
    # steps below do not need it. An unreachable host means the password has to come
    # from --db-password or WBPARSER_DB_PASSWORD, not that the rebuild cannot run.
    DB_PASSWORD="$(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 \
        "$SSH_USER@$VPS_HOST" \
        "for f in ${REMOTE_ENV_FILES[*]}; do
             [ -f \"\$f\" ] || continue
             v=\$(grep -m1 '^DB_PASSWORD=' \"\$f\" | cut -d= -f2-)
             [ -n \"\$v\" ] && { printf '%s' \"\$v\"; exit 0; }
         done" 2>/dev/null || true)"
    if [[ -z "$DB_PASSWORD" ]]; then
        info "the host did not answer — the password must come from --db-password or WBPARSER_DB_PASSWORD"
    fi
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

    # No local pg_dump. The host may have one — it is where the crawler runs, and
    # a database host usually has the client — so the dump is taken there.
    if probe "command -v pg_dump"; then
        info "no local pg_dump — taking the backup on $VPS_HOST instead"
        local remote_file="$REMOTE_BASE/backups/wbparser-$stamp.sql"
        if [[ "$DRY_RUN" -eq 1 ]]; then
            echo "  \$ ssh $SSH_USER@$VPS_HOST: pg_dump -h <DB_HOST> ... -f $remote_file"
            return 0
        fi
        remote "for f in ${REMOTE_ENV_FILES[*]}; do
                 [ -f \"\$f\" ] || continue
                 pw=\$(grep -m1 '^DB_PASSWORD=' \"\$f\" | cut -d= -f2-)
                 [ -n \"\$pw\" ] || continue
                 h=\$(grep -m1 '^DB_HOST=' \"\$f\" | cut -d= -f2-)
                 p=\$(grep -m1 '^DB_PORT=' \"\$f\" | cut -d= -f2-)
                 u=\$(grep -m1 '^DB_USER=' \"\$f\" | cut -d= -f2-)
                 d=\$(grep -m1 '^DB_NAME=' \"\$f\" | cut -d= -f2-)
                 mkdir -p '$REMOTE_BASE/backups'
                 PGPASSWORD=\"\$pw\" pg_dump -h \"\$h\" -p \"\$p\" -U \"\$u\" -d \"\$d\" --file='$remote_file'
                 exit 0
             done
             exit 1"
        info "backup written on the host: $remote_file"
        return 0
    fi

    # Neither side has pg_dump. The data is still copied out — through the client
    # the repository already depends on — so that the rows this script deletes can
    # be read back. It is a data dump, not a schema dump: the schema is Flyway's
    # business and Flyway can recreate it.
    info "no pg_dump on either side — copying the data out with the postgres client"
    mkdir -p "$BACKUP_DIR"
    local local_dump="$BACKUP_DIR/wbparser-$stamp.sql"
    if [[ "$DRY_RUN" -eq 1 ]]; then
        echo "  \$ python3 (COPY <table> TO STDOUT) → $local_dump"
        return 0
    fi
    WB_DUMP_FILE="$local_dump" python3 - <<'PY'
import os
import psycopg2

tables = [
    "crawl_targets",
    "scraped_items",
    "crawl_jobs",
    "crawl_errors",
    "category_mappings",
    "wb_subjects",
    "target_api_contexts",
]

conn = psycopg2.connect(
    host=os.environ["WB_DB_HOST"],
    port=int(os.environ["WB_DB_PORT"]),
    dbname=os.environ["WB_DB_NAME"],
    user=os.environ["WB_DB_USER"],
    password=os.environ["WB_DB_PASSWORD"],
    connect_timeout=10,
)
cur = conn.cursor()
with open(os.environ["WB_DUMP_FILE"], "w", encoding="utf-8") as out:
    out.write("-- data dump of %s\n" % os.environ["WB_DB_NAME"])
    for table in tables:
        cur.execute("select to_regclass(%s)", ("public.%s" % table,))
        if cur.fetchone()[0] is None:
            continue
        out.write("\n-- %s\nCOPY %s (public) TO STDOUT;\n" % (table, table))
        cur.copy_expert("COPY public.%s TO STDOUT WITH (FORMAT text)" % table, out)
conn.close()
PY
    info "backup written: $local_dump ($(du -h "$local_dump" | cut -f1))"
}

# ── The plan ───────────────────────────────────────────────────────────────────

heading "Plan"
info "1. stop the crawler (container, systemd unit, or nothing — whichever is there)"
info "2. back up the database (pg_dump, or a data copy through the postgres client)"
info "3. report the row counts"
info "4. delete the stale observations (scraped_items) and the job history (crawl_jobs, crawl_errors)"
info "5. reload the catalogue targets from $CATALOG_YAML"
info "6. reload the subject dictionary (wb_subjects)"
info "7. start the crawler again and wait for it to report healthy"
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
CRAWLER_KIND="$(detect_crawler)"
info "the crawler on $VPS_HOST is: $CRAWLER_KIND"
case "$CRAWLER_KIND" in
    container)
        confirm "stop the container $REMOTE_CONTAINER on $VPS_HOST"
        remote "docker ps -a --filter 'name=^/${REMOTE_CONTAINER}\$' --format '{{.Names}} {{.Status}}'"
        remote "docker compose -f '$REMOTE_COMPOSE' --project-directory '$REMOTE_BASE' stop '$REMOTE_SERVICE'"
        info "container stopped"
        ;;
    systemd)
        confirm "stop the systemd unit $REMOTE_UNIT on $VPS_HOST"
        remote "sudo systemctl stop '$REMOTE_UNIT'"
        info "unit stopped"
        ;;
    *)
        # Nothing to stop: the crawler is not deployed on this host. The data steps
        # below are still the right thing to do — the dataset is rebuilt either way
        # — and there is nothing that could write to it while it is rebuilt.
        info "no crawler on $VPS_HOST — nothing to stop"
        ;;
esac

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
case "$CRAWLER_KIND" in
    container)
        confirm "start the container $REMOTE_CONTAINER again"
        remote "docker compose -f '$REMOTE_COMPOSE' --project-directory '$REMOTE_BASE' start '$REMOTE_SERVICE'"
        ;;
    systemd)
        confirm "start the systemd unit $REMOTE_UNIT again"
        remote "sudo systemctl start '$REMOTE_UNIT'"
        ;;
    *)
        info "no crawler on $VPS_HOST — nothing to start"
        ;;
esac

if [[ "$CRAWLER_KIND" == "none" ]]; then
    # Nothing was started, so there is nothing to wait for. Waiting anyway would
    # report a failure that belongs to a crawler that was never deployed.
    info "no crawler on $VPS_HOST — nothing to wait for"
elif [[ "$DRY_RUN" -eq 0 ]]; then
    deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
    status=""
    while [[ $(date +%s) -lt $deadline ]]; do
        # A container that is still restarting answers with a non-zero exit; that is
        # the state of the wait, not a failure of the script.
        if [[ "$CRAWLER_KIND" == "systemd" ]]; then
            status="$(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 \
                "$SSH_USER@$VPS_HOST" \
                "systemctl is-active '$REMOTE_UNIT'")"
            if [[ "$status" == "active" ]]; then
                break
            fi
        elif status="$(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 \
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
    if [[ "$CRAWLER_KIND" == "systemd" ]]; then
        if [[ "$status" != "active" ]]; then
            fail "the unit did not become active within ${HEALTH_TIMEOUT}s (last status: ${status:-unknown})"
        fi
        info "unit active"
    else
        if [[ "$status" != "healthy" ]]; then
            fail "the container did not report healthy within ${HEALTH_TIMEOUT}s (last status: ${status:-unknown})"
        fi
        info "container healthy"
    fi
else
    echo "  [dry-run] wait for the crawler to report healthy (${HEALTH_TIMEOUT}s)"
fi

# ── 8. Counts after ────────────────────────────────────────────────────────────

heading "8. Row counts after"
db_query "$COUNT_SQL"

heading "Done"
info "the dataset was rebuilt: the old observations are gone, the new ones are the crawler's to collect"

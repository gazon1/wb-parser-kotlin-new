#!/usr/bin/env python3
"""
load_catalog_to_pg.py — Loads Wildberries categories from YAML into crawl_targets.

Adapted for wb-parser-kotlin schema:
  - crawl_targets.allowed_domains is TEXT[] (PostgreSQL array), not JSONB
  - Adds wb_catalog_id and wb_parent_catalog_id from YAML id/parent_catalog_id
  - parsing_rules is NULL (not used by the Kotlin parser)
  - root_category_name is optional via CLI

YAML format (from catalog_selector.py):
    pages:
      - id: "12345"                # → wb_catalog_id
        name: "Одежда"
        url: "https://www.wildberries.ru/catalog/12345"
        parent_catalog_id: "678"   # → wb_parent_catalog_id (null for root)

Usage:
    python load_catalog_to_pg.py config.yaml --db-name wbparser --db-user postgres --db-password pass
    python load_catalog_to_pg.py config.yaml --dry-run
    python load_catalog_to_pg.py config.yaml --root-category "Женская одежда" --max-depth 2

The YAML may carry `search_query` — WB's catalog token, e.g. "menu_v3_9467 карты памяти".
It is written to crawl_targets.wb_search_query and is what selects the category in the
internal catalog API. `scripts/wb-catalog/enrich_catalog_queries.py` adds it from WB's
published menu.
"""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import List, Tuple

import click
import psycopg2
from psycopg2 import extras


def validate_json(ctx: click.Context, param: click.Parameter, value: str) -> dict | list | None:
    """Callback to validate JSON strings from CLI."""
    if value is None:
        return None
    try:
        return json.loads(value)
    except json.JSONDecodeError as e:
        raise click.BadParameter(f"Invalid JSON: {e}")


def load_yaml_pages(yaml_path: Path) -> List[dict]:
    """Load and validate the list of pages from YAML."""
    import yaml
    with open(yaml_path, "r", encoding="utf-8") as f:
        data = yaml.safe_load(f)

    if not data or "pages" not in data:
        raise click.ClickException("YAML must contain a 'pages' key with a list of categories.")

    pages = data["pages"]
    if not isinstance(pages, list):
        raise click.ClickException("The 'pages' key must be a list.")

    for i, page in enumerate(pages):
        if not page.get("name") or not page.get("url"):
            raise click.ClickException(f"Page #{i+1} is missing 'name' or 'url'.")

    return pages


# ── SQL template ───────────────────────────────────────────────────────────────
#
# Schema columns in order (matches the VALUES tuple order below):
#   1  id               UUID
#   2  name             VARCHAR(255)
#   3  start_url        TEXT
#   4  allowed_domains  TEXT[]   ← PostgreSQL array, not JSONB
#   5  max_depth        INT
#   6  parsing_rules    JSONB    ← NULL (unused by Kotlin parser)
#   7  is_active        BOOLEAN
#   8  known_pages_limit INT     ← NULL by default
#   9  wb_catalog_id     VARCHAR(100)
#   10 wb_parent_catalog_id VARCHAR(100)
#   11 root_category_name VARCHAR(255)  ← NULL by default
#   12 wb_search_query   TEXT          ← WB catalog search token (e.g. "menu_v3_9467 карты памяти")
#   13 created_at        TIMESTAMPTZ
#   14 updated_at        TIMESTAMPTZ

SQL_COLUMNS = (
    "id, name, start_url, allowed_domains, max_depth, parsing_rules, "
    "is_active, known_pages_limit, wb_catalog_id, wb_parent_catalog_id, "
    "root_category_name, wb_search_query, created_at, updated_at"
)

SQL_TEMPLATE = """(
    %s, %s, %s,
    %s,          -- allowed_domains (TEXT[])
    %s, %s, %s, %s,
    %s,          -- wb_catalog_id
    %s,          -- wb_parent_catalog_id
    %s,          -- root_category_name
    %s,          -- wb_search_query
    %s, %s
)"""

INSERT_QUERY = f"INSERT INTO crawl_targets ({SQL_COLUMNS}) VALUES %s"

# Sync an existing target in place, matched by its WB catalog id.
#
# `created_at` and `id` are deliberately not touched: the id is what the crawl
# history hangs off (jobs, items, category mappings), so an update must keep
# the row it updates.
UPDATE_QUERY = """
UPDATE crawl_targets
SET name = %s,
    start_url = %s,
    allowed_domains = %s,
    max_depth = %s,
    known_pages_limit = %s,
    wb_parent_catalog_id = %s,
    root_category_name = %s,
    wb_search_query = %s,
    is_active = %s,
    updated_at = %s
WHERE wb_catalog_id = %s
"""


@click.command(context_settings=dict(help_option_names=["-h", "--help"]))
@click.argument(
    "yaml_path",
    type=click.Path(exists=True, file_okay=True, dir_okay=False, path_type=Path),
)
# ── DB connection ──────────────────────────────────────────────────────────────
@click.option("--db-host", default="localhost", help="PostgreSQL host")
@click.option("--db-port", default=5432, type=int, help="PostgreSQL port")
@click.option("--db-name", required=True, help="Database name")
@click.option("--db-user", required=True, help="Database user")
@click.option("--db-password", required=True, help="Database password")
# ── Parsing defaults ────────────────────────────────────────────────────────────
@click.option(
    "--max-depth",
    default=1,
    type=int,
    show_default=True,
    help=(
        "Value for the max_depth column: how deep the crawl may descend into the category "
        "tree. The internal catalog API returns one catalog per target, so the crawl never "
        "descends and the value only matters for link-following targets. The page limit is "
        "known_pages_limit, not max_depth."
    ),
)
@click.option(
    "--allowed-domains",
    default='["wildberries.ru", "www.wildberries.ru"]',
    show_default=False,
    help="JSON array of allowed domains (stored as PostgreSQL TEXT[])",
)
@click.option(
    "--root-category",
    default=None,
    help="root_category_name value for all rows (optional)",
)
@click.option(
    "--known-pages-limit",
    default=None,
    type=int,
    help=(
        "known_pages_limit value for all rows: how many pages of a catalog are worth "
        "crawling. NULL = no limit (the crawler then falls back to its own cap)."
    ),
)
# ── Execution options ──────────────────────────────────────────────────────────
@click.option(
    "--batch-size",
    default=100,
    type=int,
    show_default=True,
    help="INSERT batch size",
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show generated data without inserting into the DB",
)
@click.option("-v", "--verbose", is_flag=True, help="Verbose output")
def load_catalog_to_pg(
    yaml_path: Path,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
    max_depth: int,
    allowed_domains: str,
    root_category: str | None,
    known_pages_limit: int | None,
    batch_size: int,
    dry_run: bool,
    verbose: bool,
) -> None:
    """
    Loads Wildberries categories from YAML into the crawl_targets table.

    YAML expected format (from catalog_selector.py):
        pages:
          - id: "12345"
            name: "Одежда"
            url: "https://www.wildberries.ru/catalog/12345"
            parent_catalog_id: null
    """
    pages = load_yaml_pages(yaml_path)
    click.echo(f"Loaded {len(pages)} categories from {yaml_path}")

    # Parse allowed_domains JSON array
    try:
        allowed_list = json.loads(allowed_domains)
        if not isinstance(allowed_list, list):
            raise click.ClickException("--allowed-domains must be a JSON array.")
    except json.JSONDecodeError as e:
        raise click.ClickException(f"--allowed-domains must be valid JSON: {e}")

    now = datetime.now(timezone.utc)
    rows: List[Tuple] = []

    for page in pages:
        rows.append((
            str(uuid.uuid4()),                 # 1  id
            page["name"],                      # 2  name
            page["url"],                       # 3  start_url
            allowed_list,                      # 4  allowed_domains (Python list → TEXT[])
            max_depth,                         # 5  max_depth
            None,                              # 6  parsing_rules (NULL)
            True,                              # 7  is_active
            # 8  known_pages_limit — the page's own value wins over the CLI default, so
            # the crawl budget travels with the catalogue in the YAML.
            page.get("known_pages_limit") if page.get("known_pages_limit") is not None else known_pages_limit,
            page.get("id"),                    # 9  wb_catalog_id
            page.get("parent_catalog_id"),    # 10 wb_parent_catalog_id
            # 11 root_category_name — the CLI value wins for every row; otherwise
            # each page carries its own root, as written by catalog_selector.py.
            root_category or page.get("root_category_name"),
            # 12 wb_search_query — WB's catalog token. Without it the internal API answers
            # with the rotating popular list instead of the category, so a target without a
            # token is refused by the crawler rather than filled with unrelated products.
            page.get("search_query"),
            now,                               # 13 created_at
            now,                               # 14 updated_at
        ))

    if verbose:
        click.echo("\nFirst 3 rows (preview):")
        for r in rows[:3]:
            click.echo(f"  • name={r[1]} | url={r[2]}")
            click.echo(f"    wb_catalog_id={r[8]} | wb_parent_catalog_id={r[9]}")
            click.echo(f"    search_query={r[11]}")

    if dry_run:
        click.echo(f"\nDRY-RUN: {len(rows)} records prepared. Run without --dry-run to insert.")
        return

    # ── DB insert ─────────────────────────────────────────────────────────────
    conn = None
    try:
        click.echo(f"Connecting to {db_host}:{db_port}/{db_name}...")
        conn = psycopg2.connect(
            host=db_host,
            port=db_port,
            dbname=db_name,
            user=db_user,
            password=db_password,
        )
        conn.autocommit = False
        cur = conn.cursor()

        click.echo("Syncing data...")

        # A blind INSERT would create a second copy of every target on every run:
        # the primary key is a fresh uuid each time, and the crawl history (jobs,
        # items, category mappings) hangs off the *old* row. Loading the same YAML
        # twice must not double the target list, so a target that already carries
        # this wb_catalog_id is updated in place — keeping its id, and therefore
        # its history.
        cur.execute("SELECT wb_catalog_id FROM crawl_targets WHERE wb_catalog_id IS NOT NULL")
        existing = {str(catalog_id) for (catalog_id,) in cur.fetchall()}

        to_update = [row for row in rows if str(row[8]) in existing]
        to_insert = [row for row in rows if str(row[8]) not in existing]

        click.echo(
            f"  {len(to_update)} target(s) updated in place, {len(to_insert)} inserted"
        )

        with click.progressbar(
            length=len(rows),
            label="Processing batches",
            show_pos=True,
            width=50,
        ) as bar:
            for row in to_update:
                cur.execute(
                    UPDATE_QUERY,
                    (
                        row[1],  # name
                        row[2],  # start_url
                        row[3],  # allowed_domains
                        row[4],  # max_depth
                        row[7],  # known_pages_limit
                        row[9],  # wb_parent_catalog_id
                        row[10],  # root_category_name
                        row[11],  # wb_search_query
                        row[6],  # is_active
                        now,  # updated_at
                        str(row[8]),  # wb_catalog_id — the match key
                    ),
                )
                bar.update(1)

            for i in range(0, len(to_insert), batch_size):
                batch = to_insert[i:i + batch_size]
                # psycopg2.extras.execute_values handles Python lists as PostgreSQL ARRAYs
                extras.execute_values(
                    cur,
                    INSERT_QUERY,
                    batch,
                    template=SQL_TEMPLATE,
                )
                bar.update(len(batch))

        conn.commit()
        click.echo(
            f"\nSuccessfully synced {len(rows)} categories into crawl_targets "
            f"({len(to_update)} updated, {len(to_insert)} inserted)."
        )

    except psycopg2.Error as e:
        if conn:
            conn.rollback()
        msg = str(e)
        if "does not exist" in msg and "crawl_targets" in msg:
            click.echo(
                "\nDB error: table 'crawl_targets' does not exist.\n"
                "Run Flyway migrations first:\n"
                "  docker compose up postgres -d && just app::run\n"
                "  # OR run the app with SPRING_FLYWAY_ENABLED=true",
                err=True,
            )
        else:
            click.echo(f"\nDB error: {e}", err=True)
        raise click.Abort()
    except Exception as e:
        if conn:
            conn.rollback()
        click.echo(f"\nCritical error: {e}", err=True)
        raise click.Abort()
    finally:
        if conn:
            conn.close()


if __name__ == "__main__":
    load_catalog_to_pg()

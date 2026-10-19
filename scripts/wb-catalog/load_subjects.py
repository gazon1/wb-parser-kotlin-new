"""Load WB's product-subject dictionary into `wb_subjects`.

## Why this exists

The WB catalog API tells each product where it sits in the category tree by id
only: `subjectId` and `subjectParentId`. The names of those ids are published
by WB in a separate static file, so a crawler that only talks to the API can
record the tree's shape but not the names that go with it.

This script fetches that dictionary and stores it in the `wb_subjects` table
(migration `V4__wb_subjects.sql`). The crawler reads the table and writes the
name next to the id — it never invents one.

## Usage

    python load_subjects.py --db-name wbparser --db-user postgres --db-password pass
    python load_subjects.py --db-name wbparser --db-user postgres --db-password pass --dry-run
"""

from __future__ import annotations

import gzip
import json
from pathlib import Path
from typing import Any

import click
import psycopg2
import psycopg2.extras

DEFAULT_SUBJECTS_URL = "https://static-basket-01.wbbasket.ru/vol0/data/subjects.json"
CACHE_DIR = Path.home() / ".cache" / "wb-catalog"
CACHE_FILE = CACHE_DIR / "subjects.json"


def fetch_subjects(url: str = DEFAULT_SUBJECTS_URL, timeout: float = 60.0) -> list[dict[str, Any]]:
    """Fetch WB's subject dictionary, using the local cache when it is present."""
    import httpx

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    if CACHE_FILE.exists():
        raw = CACHE_FILE.read_bytes()
    else:
        with httpx.Client(timeout=timeout, follow_redirects=True) as client:
            response = client.get(url)
            response.raise_for_status()
            raw = response.content
        CACHE_FILE.write_bytes(raw)

    # The file is served gzip-encoded; httpx may or may not have decoded it.
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)

    subjects = json.loads(raw.decode("utf-8"))
    if not isinstance(subjects, list):
        raise click.ClickException("subjects.json is not a list")
    return subjects


def collect_rows(subjects: list[dict[str, Any]]) -> list[tuple[int, str]]:
    """Turn the dictionary into `(id, name)` rows, skipping entries without a name."""
    rows: list[tuple[int, str]] = []
    for entry in subjects:
        subject_id = entry.get("id")
        name = entry.get("name")
        if subject_id is None or not name:
            continue
        rows.append((int(subject_id), str(name)))
    return rows


@click.command(context_settings=dict(help_option_names=["-h", "--help"]))
@click.option("--db-host", default="localhost", help="PostgreSQL host")
@click.option("--db-port", default=5432, type=int, help="PostgreSQL port")
@click.option("--db-name", required=True, help="Database name")
@click.option("--db-user", required=True, help="Database user")
@click.option("--db-password", required=True, help="Database password")
@click.option("--url", default=DEFAULT_SUBJECTS_URL, show_default=True, help="WB subjects file URL")
@click.option(
    "--backfill",
    is_flag=True,
    help="Also fill the name columns of rows that were scraped before the dictionary existed",
)
@click.option("--dry-run", is_flag=True, help="Report the rows without writing them")
def load_subjects(
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
    url: str,
    backfill: bool,
    dry_run: bool,
) -> None:
    """Load WB's product-subject dictionary into the wb_subjects table."""
    subjects = fetch_subjects(url)
    rows = collect_rows(subjects)
    click.echo(f"Fetched {len(subjects)} subjects, {len(rows)} with a name")

    if dry_run:
        click.echo("DRY-RUN: nothing written")
        return

    conn = None
    try:
        conn = psycopg2.connect(
            host=db_host,
            port=db_port,
            dbname=db_name,
            user=db_user,
            password=db_password,
        )
        conn.autocommit = False
        cur = conn.cursor()

        cur.execute("SELECT to_regclass('public.wb_subjects')")
        if cur.fetchone()[0] is None:
            click.echo(
                "DB error: table 'wb_subjects' does not exist.\n"
                "Run the Flyway migrations first (the app applies them on start).",
                err=True,
            )
            raise click.Abort()

        psycopg2.extras.execute_values(
            cur,
            """
            INSERT INTO wb_subjects (id, name) VALUES %s
            ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name
            """,
            rows,
            page_size=1000,
        )
        conn.commit()
        click.echo(f"Loaded {len(rows)} subjects into wb_subjects")

        if backfill:
            backfill_names(cur)
            conn.commit()
    except psycopg2.Error as e:
        if conn:
            conn.rollback()
        click.echo(f"\nDB error: {e}", err=True)
        raise click.Abort()
    finally:
        if conn:
            conn.close()


# Rows scraped before the dictionary existed carry an id and no name. Filling the
# name in afterwards is the same value the write path would have written, so the
# table ends up in one state rather than two.
BACKFILL_QUERIES = (
    (
        "scraped_items.catalog_name",
        """
        UPDATE scraped_items s
        SET catalog_name = w.name
        FROM wb_subjects w
        WHERE s.subject_id = w.id AND s.catalog_name IS NULL
        """,
    ),
    (
        "category_mappings.catalog_name",
        """
        UPDATE category_mappings c
        SET catalog_name = w.name
        FROM wb_subjects w
        WHERE c.catalog_id = w.id::text AND c.catalog_name IS NULL
        """,
    ),
    (
        "category_mappings.node_name",
        """
        UPDATE category_mappings c
        SET node_name = w.name
        FROM wb_subjects w
        WHERE c.parent_node_id = w.id::text AND c.node_name IS NULL
        """,
    ),
)


def backfill_names(cur) -> None:
    """Fill the name columns of rows that were scraped without a dictionary."""
    for label, query in BACKFILL_QUERIES:
        cur.execute(query)
        click.echo(f"  {label}: {cur.rowcount} row(s) named")


if __name__ == "__main__":
    load_subjects()

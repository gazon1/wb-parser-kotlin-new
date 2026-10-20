#!/usr/bin/env python3
"""
refresh_catalog_tokens.py — refreshes the WB catalog tokens of a catalog YAML and of the database.

Why this exists
---------------
WB's internal catalog API (`__internal/u-search/exactmatch/ru/common/v18/search`) does not
take the catalog id as a parameter. The category is selected by the `query` parameter, and
the value that has to go there is WB's own menu token — the `searchQuery` field of the node
in `main-menu-by-ru-v3.json`, e.g. `menu_v3_9467 карты памяти`.

Without a token the API answers with its rotating "popular presets" list: about 1 200
products that have nothing to do with the requested category and differ from request to
request. The crawler detects that and refuses to store such a page — correct, but the
target then silently produces nothing at all.

WB republishes and renames menu nodes, so a token that was right last month stops
selecting the category today. Until now the refresh was a manual loop: download the menu,
look every id up, edit the YAML, reload the database. This script closes that loop.

What it does
------------
- loads WB's menu (a local file or the published URL),
- looks every page of the catalog YAML up in the menu and computes its token,
- reports the pages whose id the menu does not publish — those keep their current token,
  because inventing one is exactly the popular-list mistake this whole mechanism exists
  to avoid,
- writes the refreshed tokens back into the YAML (`--update-yaml`),
- writes them into `crawl_targets.wb_search_query` (`--update-db`).

What it does NOT do
-------------------
- it does not crawl WB and does not touch the observed data,
- it does not invent a token for a page the menu does not publish,
- it does not delete or deactivate targets, and it does not change `known_pages_limit`
  (that is `measure_catalog_length.py`'s job),
- it does not create the tables: the schema belongs to the Flyway migrations.

Usage:
    python3 scripts/wb-catalog/refresh_catalog_tokens.py --dry-run
    python3 scripts/wb-catalog/refresh_catalog_tokens.py --update-yaml
    python3 scripts/wb-catalog/refresh_catalog_tokens.py --update-db \
        --db-name wbparser --db-user postgres --db-password "$WBPARSER_DB_PASSWORD"
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional

import click
import httpx
import yaml

DEFAULT_MENU_URL = "https://static-basket-01.wbbasket.ru/vol0/data/main-menu-by-ru-v3.json"
DEFAULT_CATALOG_YAML = Path(__file__).resolve().parent.parent.parent / "wb_catalog_targets.yaml"

STATUS_REFRESHED = "refreshed"
STATUS_UNCHANGED = "unchanged"
STATUS_NOT_IN_MENU = "not in menu"
STATUS_NO_TOKEN = "no token in menu"

# Only a token that differs from the stored one is written: the database update has to
# touch the rows that really change, so a refresh that finds nothing new leaves the
# table exactly as it was.
UPDATE_QUERY = """
UPDATE crawl_targets
SET wb_search_query = %s,
    updated_at = now()
WHERE wb_catalog_id = %s
  AND wb_search_query IS DISTINCT FROM %s
"""


@dataclass
class TokenPlan:
    """What the menu says about one page of the catalog, and what that would change."""

    page_id: str
    name: str
    old_token: str
    new_token: str
    status: str

    @property
    def effective_token(self) -> str:
        """The token the page should carry: the menu's, or the one it already has."""
        return self.new_token or self.old_token


def fetch_menu(menu: Optional[Path], menu_url: str, timeout: float) -> List[dict]:
    """Load WB's menu from a local file or from the public static basket.

    A menu that cannot be fetched is a hard error: half a menu would silently strip
    the tokens of half the catalog.
    """
    if menu is not None:
        try:
            with open(menu, "r", encoding="utf-8") as f:
                nodes = json.load(f)
        except (OSError, json.JSONDecodeError) as e:
            raise click.ClickException(f"Cannot read the menu file {menu}: {e}") from e
    else:
        try:
            with httpx.Client(timeout=timeout, follow_redirects=True) as client:
                response = client.get(menu_url)
                response.raise_for_status()
                nodes = response.json()
        except (httpx.HTTPError, json.JSONDecodeError) as e:
            raise click.ClickException(f"Cannot fetch the menu from {menu_url}: {e}") from e

    if not isinstance(nodes, list):
        raise click.ClickException(f"The menu at {menu or menu_url} is not a list of nodes")
    return nodes


def index_menu(nodes: List[dict]) -> Dict[str, dict]:
    """Flatten the menu tree into `id -> node`."""

    index: Dict[str, dict] = {}

    def walk(items: List[dict]) -> None:
        for node in items:
            node_id = node.get("id")
            if node_id is not None:
                index[str(node_id)] = node
            walk(node.get("childs") or [])

    walk(nodes)
    return index


def plan_tokens(pages: List[dict], menu_index: Dict[str, dict]) -> List[TokenPlan]:
    """Compute, for every page of the catalog, the token the menu gives it."""
    plans: List[TokenPlan] = []

    for page in pages:
        page_id = str(page.get("id", ""))
        name = str(page.get("name", ""))
        old_token = (page.get("search_query") or "").strip()

        node = menu_index.get(page_id)
        if node is None:
            # WB does not publish this id. The token it already has stays: a page the
            # menu dropped is not the same as a page that never had a token.
            plans.append(TokenPlan(page_id, name, old_token, "", STATUS_NOT_IN_MENU))
            continue

        new_token = (node.get("searchQuery") or "").strip()
        if not new_token:
            plans.append(TokenPlan(page_id, name, old_token, "", STATUS_NO_TOKEN))
            continue

        status = STATUS_UNCHANGED if new_token == old_token else STATUS_REFRESHED
        plans.append(TokenPlan(page_id, name, old_token, new_token, status))

    return plans


def render_table(plans: List[TokenPlan]) -> str:
    """Render the refresh plan as a table: what each page has, and what it would get."""
    header = f"{'id':<10} {'name':<28} {'old token':<38} {'new token':<38} status"
    lines = [header, "-" * len(header)]

    for plan in plans:
        lines.append(
            f"{plan.page_id:<10} {plan.name[:27]:<28} "
            f"{(plan.old_token or '—')[:37]:<38} "
            f"{(plan.new_token or '—')[:37]:<38} {plan.status}"
        )

    return "\n".join(lines)


def set_token(page: dict, token: str) -> None:
    """Put the token into the page, in the place the catalog YAML keeps it.

    A page that already has a token keeps its key where it was. A page that gains
    one gets it in the canonical position — before `shard` — so that the file
    stays in the shape the other pages have.
    """
    if "search_query" in page:
        page["search_query"] = token
        return

    reordered: dict = {}
    for key, value in page.items():
        if key in ("shard", "known_pages_limit"):
            reordered["search_query"] = token
        reordered[key] = value
    if "search_query" not in reordered:
        reordered["search_query"] = token
    page.clear()
    page.update(reordered)


def write_yaml(path: Path, pages: List[dict]) -> None:
    """Write the catalog back in the file's own style.

    The catalog YAML in the repository was produced by `yaml.dump(..., sort_keys=False)`,
    and writing it back the same way keeps the formatting and the key order of every key
    this script did not touch — the token is the only thing that changes.
    """
    path.write_text(
        yaml.dump({"pages": pages}, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )


def update_db_tokens(plans: List[TokenPlan], db_host: str, db_port: int, db_name: str,
                    db_user: str, db_password: str) -> int:
    """Write the effective tokens into `crawl_targets.wb_search_query`.

    Only the rows that really change are touched, and everything is committed as one
    transaction: a refresh that fails halfway must not leave the table with half of the
    catalog on the new token and half on the old.
    """
    import psycopg2

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

        updated = 0
        for plan in plans:
            token = plan.effective_token
            if not token:
                continue
            cur.execute(UPDATE_QUERY, (token, plan.page_id, token))
            updated += cur.rowcount

        conn.commit()
        return updated
    except psycopg2.Error as e:
        if conn:
            conn.rollback()
        raise click.ClickException(f"DB error: {e}") from e
    finally:
        if conn:
            conn.close()


@click.command(context_settings=dict(help_option_names=["-h", "--help"]))
@click.argument(
    "yaml_path",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=DEFAULT_CATALOG_YAML,
    required=False,
)
@click.option(
    "--menu",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=None,
    help="Use a local copy of the menu tree instead of fetching it",
)
@click.option("--menu-url", default=DEFAULT_MENU_URL, show_default=True, help="WB menu tree URL")
@click.option("--timeout", default=30.0, type=float, show_default=True, help="Menu fetch timeout (s)")
@click.option("--dry-run", is_flag=True, help="Print the plan and write nothing")
@click.option("--update-yaml", is_flag=True, help="Write the refreshed tokens back into the YAML")
@click.option("--update-db", is_flag=True, help="Write the refreshed tokens into crawl_targets")
# ── DB connection ──────────────────────────────────────────────────────────────
@click.option("--db-host", default="localhost", help="PostgreSQL host")
@click.option("--db-port", default=5432, type=int, help="PostgreSQL port")
@click.option("--db-name", default=None, help="Database name")
@click.option("--db-user", default=None, help="Database user")
@click.option("--db-password", default=None, help="Database password")
def refresh_catalog_tokens(
    yaml_path: Path,
    menu: Optional[Path],
    menu_url: str,
    timeout: float,
    dry_run: bool,
    update_yaml: bool,
    update_db: bool,
    db_host: str,
    db_port: int,
    db_name: Optional[str],
    db_user: Optional[str],
    db_password: Optional[str],
) -> None:
    """Refresh the WB catalog tokens of a catalog YAML and of the database."""
    if not update_yaml and not update_db and not dry_run:
        # Neither a write nor an explicit dry-run: the safe reading is a dry-run.
        dry_run = True

    try:
        with open(yaml_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
    except (OSError, yaml.YAMLError) as e:
        raise click.ClickException(f"Cannot parse {yaml_path}: {e}") from e

    pages = data.get("pages") if isinstance(data, dict) else None
    if not isinstance(pages, list) or not pages:
        raise click.ClickException(f"{yaml_path} has no 'pages' list")

    nodes = fetch_menu(menu, menu_url, timeout)
    menu_index = index_menu(nodes)
    plans = plan_tokens(pages, menu_index)

    click.echo(f"Menu nodes: {len(menu_index)}")
    click.echo(f"Catalog pages: {len(plans)}")
    click.echo("")
    click.echo(render_table(plans))

    refreshed = [p for p in plans if p.status == STATUS_REFRESHED]
    unchanged = [p for p in plans if p.status == STATUS_UNCHANGED]
    without_token = [p for p in plans if not p.effective_token]
    not_published = [p for p in plans if p.status in (STATUS_NOT_IN_MENU, STATUS_NO_TOKEN)]

    click.echo("")
    click.echo(f"Tokens refreshed:      {len(refreshed)}")
    click.echo(f"Tokens unchanged:     {len(unchanged)}")
    click.echo(f"Targets without token: {len(without_token)}")
    if not_published:
        click.echo("")
        click.echo("The menu does not publish these ids — their current token is kept:")
        for plan in not_published:
            click.echo(f"  [{plan.status}] {plan.page_id} {plan.name}")

    if dry_run:
        click.echo("")
        click.echo("DRY-RUN: nothing written")
        return

    if update_yaml:
        for plan, page in zip(plans, pages):
            if plan.new_token:
                set_token(page, plan.new_token)
        write_yaml(yaml_path, pages)
        click.echo("")
        click.echo(f"Wrote {yaml_path}")

    if update_db:
        password = db_password or _password_from_env()
        if not password:
            raise click.ClickException(
                "--update-db needs a password: pass --db-password or set WBPARSER_DB_PASSWORD"
            )
        if not db_name or not db_user:
            raise click.ClickException("--update-db needs --db-name and --db-user")

        updated = update_db_tokens(
            plans,
            db_host=db_host,
            db_port=db_port,
            db_name=db_name,
            db_user=db_user,
            db_password=password,
        )
        click.echo(f"crawl_targets rows updated: {updated}")
        if not update_yaml:
            click.echo(
                "note: the YAML was not rewritten — the database now holds the menu's "
                "token. Run again with --update-yaml to bring the file into step."
            )


def _password_from_env() -> Optional[str]:
    """Read the database password from the environment.

    The password never lives in the source: a literal in a script leaks into every
    clone and every CI log.
    """
    import os

    return os.environ.get("WBPARSER_DB_PASSWORD") or None


if __name__ == "__main__":
    refresh_catalog_tokens()

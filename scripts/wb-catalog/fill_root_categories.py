"""Backfill `root_category_name` in a catalog targets YAML.

## Why this exists

`catalog_selector.py` writes `root_category_name` per page — the label of the
page's top-level ancestor ("Электроника", "Одежда"). A YAML produced *before*
that field existed has none of it, and `load_catalog_to_pg.py` then leaves
`crawl_targets.root_category_name` NULL for every row: the column exists, the
admin API returns it, and it is empty.

The names are not in the YAML and they are not in the WB product API either. They
*are* in WB's own public menu tree — a static JSON file on WB's CDN that carries
the whole category hierarchy with names. Reading it needs no browser, no
anti-bot token and no crawl, so backfilling is a local, offline-cheap operation
over a file that is already on disk.

## Usage

    python fill_root_categories.py wb_catalog_targets.yaml
    python fill_root_categories.py wb_catalog_targets.yaml --menu /tmp/menu.json
    python fill_root_categories.py wb_catalog_targets.yaml --dry-run

The tree is fetched once and cached under `~/.cache/wb-catalog/` so repeated
runs do not hit the CDN again.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable

import click
import yaml

DEFAULT_MENU_URL = "https://static-basket-01.wbbasket.ru/vol0/data/main-menu-by-ru-v3.json"
CACHE_DIR = Path.home() / ".cache" / "wb-catalog"
CACHE_FILE = CACHE_DIR / "main-menu-by-ru-v3.json"


def fetch_menu(url: str = DEFAULT_MENU_URL, timeout: float = 30.0) -> list[dict]:
    """Fetch WB's public category tree, using the local cache when it is present."""
    import httpx

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    if CACHE_FILE.exists():
        return json.loads(CACHE_FILE.read_text(encoding="utf-8"))

    with httpx.Client(timeout=timeout, follow_redirects=True) as client:
        response = client.get(url)
        response.raise_for_status()
        nodes = response.json()

    CACHE_FILE.write_text(json.dumps(nodes, ensure_ascii=False), encoding="utf-8")
    return nodes


def index_menu(nodes: Iterable[dict]) -> tuple[dict[str, str], dict[str, str]]:
    """Build the two maps the root lookup needs: `id → name` and `child → parent`."""
    labels: dict[str, str] = {}
    parents: dict[str, str] = {}

    def walk(children: list[dict], parent_id: str | None) -> None:
        for node in children:
            node_id = str(node["id"])
            labels[node_id] = node.get("name", node_id)
            if parent_id is not None:
                parents[node_id] = parent_id
            walk(node.get("childs") or [], node_id)

    walk(list(nodes), None)
    return labels, parents


def root_label(
    node_id: str,
    labels: dict[str, str],
    parents: dict[str, str],
) -> str | None:
    """Walk up the parent chain to the top-level ancestor and return its name."""
    current = node_id
    seen: set[str] = set()
    while current in parents and current not in seen:
        seen.add(current)
        current = parents[current]
    return labels.get(current)


@click.command(context_settings=dict(help_option_names=["-h", "--help"]))
@click.argument(
    "yaml_path",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
)
@click.option("--menu-url", default=DEFAULT_MENU_URL, show_default=True, help="WB menu tree URL")
@click.option(
    "--menu",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=None,
    help="Use a local copy of the menu tree instead of fetching it",
)
@click.option("--dry-run", is_flag=True, help="Report the changes without writing the file")
def fill_root_categories(yaml_path: Path, menu_url: str, menu: Path | None, dry_run: bool) -> None:
    """Fill `root_category_name` for every page of a catalog targets YAML."""
    nodes = json.loads(menu.read_text(encoding="utf-8")) if menu else fetch_menu(menu_url)
    labels, parents = index_menu(nodes)

    data = yaml.safe_load(yaml_path.read_text(encoding="utf-8"))
    pages = data.get("pages") if isinstance(data, dict) else None
    if not isinstance(pages, list):
        raise click.ClickException(f"{yaml_path} has no 'pages' list")

    filled = 0
    unresolved: list[str] = []
    for page in pages:
        page_id = str(page.get("id", ""))
        label = root_label(page_id, labels, parents)
        if label is None:
            unresolved.append(page_id)
            continue
        if page.get("root_category_name") != label:
            page["root_category_name"] = label
            filled += 1

    if unresolved:
        click.echo(
            f"warning: {len(unresolved)} page(s) are not in the menu tree: "
            f"{', '.join(unresolved[:10])}"
            + ("…" if len(unresolved) > 10 else "")
        )

    click.echo(f"pages: {len(pages)}, root_category_name filled: {filled}")
    if dry_run:
        return

    yaml_path.write_text(
        yaml.safe_dump(data, allow_unicode=True, sort_keys=False, width=4096),
        encoding="utf-8",
    )
    click.echo(f"written: {yaml_path}")


if __name__ == "__main__":
    fill_root_categories()

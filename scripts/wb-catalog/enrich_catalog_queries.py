#!/usr/bin/env python3
"""
enrich_catalog_queries.py — adds WB's catalog search token to a catalog YAML.

Why this exists
---------------
WB's internal catalog API (`__internal/u-search/exactmatch/ru/common/v18/search`) does not
take the catalog id as a parameter. The category is selected by the `query` parameter, and
the value that has to go there is WB's own menu token — the `searchQuery` field of the node
in `main-menu-by-ru-v3.json`, e.g. `menu_v3_9467 карты памяти`.

Without the token the API answers with its rotating "popular presets" list: a pool of about
1 200 products that has nothing to do with the requested category and differs from request to
request. Every catalog then returned nearly the same goods, all of them stored under the name
of a category they were not from.

So the token is not a nice-to-have — it is the difference between "the crawl ran" and "the
crawl collected this category".

What it does
------------
Reads the catalog YAML (the output of `catalog_selector.py`), looks every page's `id` up in
WB's published menu and writes two fields back:

    search_query   the `query` parameter value   → crawl_targets.wb_search_query
    menu_query     the node's raw `query` field  (e.g. "subject=515") — for reference only
    shard          the node's shard               (e.g. "electronic84") — for reference only

Pages whose id is not in the menu are reported and left untouched: a catalog WB does not
publish has no token, and inventing one would silently turn into the popular list again.

Usage:
    python3 scripts/wb-catalog/enrich_catalog_queries.py wb_catalog_targets.yaml
    python3 scripts/wb-catalog/enrich_catalog_queries.py wb_catalog_targets.yaml --dry-run
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Dict, List

import httpx
import yaml

DEFAULT_MENU_URL = "https://static-basket-01.wbbasket.ru/vol0/data/main-menu-by-ru-v3.json"


def fetch_menu(menu_path: Path | None, menu_url: str, timeout: float = 30.0) -> List[dict]:
    """Load WB's menu from a local file or from the public static basket."""
    if menu_path is not None:
        with open(menu_path, "r", encoding="utf-8") as f:
            return json.load(f)
    with httpx.Client(timeout=timeout, follow_redirects=True) as client:
        response = client.get(menu_url)
        response.raise_for_status()
        return response.json()


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


def enrich(pages: List[dict], menu_index: Dict[str, dict]) -> Dict[str, int]:
    """Write the menu fields into every page that has a token. Returns counters."""
    stats = {"enriched": 0, "missing": 0, "no_token": 0}

    for page in pages:
        node = menu_index.get(str(page.get("id")))
        if node is None:
            stats["missing"] += 1
            continue

        search_query = (node.get("searchQuery") or "").strip()
        if not search_query:
            stats["no_token"] += 1
            continue

        page["search_query"] = search_query
        stats["enriched"] += 1

        # Kept for reference and for future sharded endpoints — not used by the crawler.
        menu_query = (node.get("query") or "").strip()
        if menu_query:
            page["menu_query"] = menu_query
        shard = node.get("shard")
        if shard:
            page["shard"] = shard

    return stats


def main(argv: List[str]) -> int:
    yaml_path = Path(argv[1]) if len(argv) > 1 else Path("wb_catalog_targets.yaml")
    dry_run = "--dry-run" in argv
    menu_path: Path | None = None
    if "--menu" in argv:
        menu_path = Path(argv[argv.index("--menu") + 1])

    if not yaml_path.exists():
        print(f"Catalog YAML not found: {yaml_path}", file=sys.stderr)
        return 1

    with open(yaml_path, "r", encoding="utf-8") as f:
        data = yaml.safe_load(f) or {}

    pages = data.get("pages")
    if not isinstance(pages, list) or not pages:
        print(f"{yaml_path} has no 'pages' list", file=sys.stderr)
        return 1

    menu = fetch_menu(menu_path, DEFAULT_MENU_URL)
    menu_index = index_menu(menu)
    stats = enrich(pages, menu_index)

    print(f"Menu nodes: {len(menu_index)}")
    print(f"Enriched with a search token: {stats['enriched']}")
    print(f"No token in the menu:         {stats['no_token']}")
    print(f"Not in the menu at all:        {stats['missing']}")

    if stats["missing"] or stats["no_token"]:
        for page in pages:
            node = menu_index.get(str(page.get("id")))
            if node is None:
                print(f"  [missing] {page.get('id')} {page.get('name')}")
            elif not (node.get("searchQuery") or "").strip():
                print(f"  [no token] {page.get('id')} {page.get('name')}")

    if dry_run:
        print("\nDRY-RUN: file not written")
        return 0

    with open(yaml_path, "w", encoding="utf-8") as f:
        yaml.dump({"pages": pages}, f, allow_unicode=True, sort_keys=False)
    print(f"\nWrote {yaml_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

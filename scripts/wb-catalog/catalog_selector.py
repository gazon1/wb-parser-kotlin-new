#!/usr/bin/env python3
"""
catalog_selector.py — Interactive Wildberries category selector (adapted for wb-parser-kotlin).

Fetches the full WB catalog tree, renders it in a NiceGUI checkbox tree,
and saves selected leaf categories to a YAML config file.

The YAML output matches the schema expected by load_catalog_to_pg.py:
    pages:
      - id: "12345"                # wb_catalog_id
        name: "Одежда"
        url: "https://www.wildberries.ru/catalog/12345"
        parent_catalog_id: "678"    # wb_parent_catalog_id (null for root)

Usage (standalone):
    python scripts/wb-catalog/catalog_selector.py
    python scripts/wb-catalog/catalog_selector.py --port 8090 --host 0.0.0.0
    python scripts/wb-catalog/catalog_selector.py --output my_config.yaml
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Optional

import click
import httpx
import yaml
from nicegui import ui

try:
    from loguru import logger
except ImportError:
    import logging
    logger = logging.getLogger(__name__)

# ── Defaults ──────────────────────────────────────────────────────────────────

DEFAULT_CATALOG_URL = (
    "https://static-basket-01.wbbasket.ru/vol0/data/main-menu-by-ru-v3.json"
)
DEFAULT_BASE_URL = "https://www.wildberries.ru"
DEFAULT_CONFIG_PATH = Path("wb_catalog_targets.yaml")
DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8089


# ── Data helpers ──────────────────────────────────────────────────────────────

def fetch_catalog_nodes(
    catalog_url: str = DEFAULT_CATALOG_URL,
    timeout: float = 15.0,
) -> list[dict]:
    """Fetch full Wildberries catalog as JSON using httpx (sync)."""
    with httpx.Client(timeout=timeout, follow_redirects=True) as client:
        resp = client.get(catalog_url)
        resp.raise_for_status()
        return resp.json()


def convert_to_tree_nodes(
    parent_node: dict | None = None,
    child_nodes: list | None = None,
    base_url: str = DEFAULT_BASE_URL,
) -> list[dict]:
    """
    Recursively convert WB catalog JSON → NiceGUI ui.tree node format.

    Preserves id and parent_catalog_id so we can write them to the YAML output
    as wb_catalog_id / wb_parent_catalog_id.
    """
    if child_nodes is None:
        return []

    parent_id: Optional[str] = (
        str(parent_node["id"])
        if parent_node and isinstance(parent_node, dict) and "id" in parent_node
        else None
    )

    result: list[dict] = []
    for node in child_nodes:
        children = convert_to_tree_nodes(
            parent_node=node,
            child_nodes=node.get("childs", []),
            base_url=base_url,
        )
        result.append(
            {
                "id": str(node["id"]),
                "label": node.get("name", str(node["id"])),
                "url": base_url.rstrip("/") + node.get("url", ""),
                "parent_catalog_id": parent_id,
                "children": children,
            }
        )
    return result


def save_config(
    selected_nodes: dict[str, dict],
    parent_nodes_ids: set[str],
    config_path: Path = DEFAULT_CONFIG_PATH,
) -> None:
    """
    Persist selected leaf nodes (non-parent) to YAML.

    Filters out any node whose id appears in parent_nodes_ids, so only
    actionable leaf categories are stored.
    """
    filtered = [
        data
        for node_id, data in selected_nodes.items()
        if node_id not in parent_nodes_ids
    ]
    config_path.parent.mkdir(parents=True, exist_ok=True)
    with open(config_path, "w", encoding="utf-8") as f:
        yaml.dump({"pages": filtered}, f, allow_unicode=True, sort_keys=False)
    ui.notify(f"Config saved → {config_path}", type="positive")
    logger.info("Config saved to %s (%d entries)", config_path, len(filtered))


# ── NiceGUI page ──────────────────────────────────────────────────────────────

def build_ui(
    catalog_url: str = DEFAULT_CATALOG_URL,
    base_url: str = DEFAULT_BASE_URL,
    config_path: Path = DEFAULT_CONFIG_PATH,
) -> None:
    """
    Render the catalog selector page.
    Called once per page load by NiceGUI.
    """

    # ── Load data ─────────────────────────────────────────────────────────────
    try:
        catalog_nodes = fetch_catalog_nodes(catalog_url)
    except httpx.HTTPError as exc:
        ui.label(f"Failed to fetch catalog: {exc}").classes("text-red-500 text-lg")
        return

    tree_data = convert_to_tree_nodes(child_nodes=catalog_nodes, base_url=base_url)

    # ── Build parent ↔ child maps ─────────────────────────────────────────────
    parent_map: dict[str, list[str]] = {}
    child_map: dict[str, str] = {}
    parent_nodes_ids: set[str] = set()

    def _build_maps(nodes: list[dict], parent: str | None = None) -> None:
        for n in nodes:
            nid = n["id"]
            if parent:
                parent_map.setdefault(parent, []).append(nid)
                child_map[nid] = parent
            if n.get("children"):
                parent_nodes_ids.add(nid)
                _build_maps(n["children"], nid)

    _build_maps(tree_data)

    # ── Mutable selection state ───────────────────────────────────────────────
    checked_ids: set[str] = set()
    selected_nodes: dict[str, dict] = {}

    def _collect_selected() -> None:
        """Rebuild selected_nodes dict from current checked_ids."""
        selected_nodes.clear()

        def _recurse(nodes: list[dict]) -> None:
            for n in nodes:
                if n["id"] in checked_ids:
                    selected_nodes[n["id"]] = {
                        "id": n["id"],
                        "name": n["label"],
                        "url": n.get("url", ""),
                        "parent_catalog_id": n.get("parent_catalog_id"),
                    }
                if n.get("children"):
                    _recurse(n["children"])

        _recurse(tree_data)

    def _on_tick(e: Any) -> None:
        """
        Handle checkbox tick events from ui.tree.

        NiceGUI passes partial state in e.value, so we compute the real diff
        manually and propagate parent/child selection accordingly.
        """
        clicked_ids: set[str] = set(e.value)

        added = clicked_ids - checked_ids
        removed = checked_ids - clicked_ids

        for nid in added:
            checked_ids.add(nid)
            for child_id in parent_map.get(nid, []):
                checked_ids.add(child_id)
            if nid in child_map:
                checked_ids.add(child_map[nid])

        for nid in removed:
            checked_ids.discard(nid)
            for child_id in parent_map.get(nid, []):
                checked_ids.discard(child_id)
            if nid in child_map:
                parent_id = child_map[nid]
                siblings = parent_map.get(parent_id, [])
                if not any(s in checked_ids for s in siblings):
                    checked_ids.discard(parent_id)

        tree.value = list(checked_ids)
        tree.update()
        _collect_selected()

    # ── Layout ───────────────────────────────────────────────────────────────
    with ui.column().classes("w-full max-w-4xl mx-auto p-4 gap-4"):
        ui.label("Wildberries Category Selector").classes("text-2xl font-bold")
        ui.label(
            "Select categories to scrape.  "
            "Only leaf nodes (no sub-categories) will be written to the config."
        ).classes("text-gray-500 text-sm")

        with ui.card().classes("w-full"):
            tree = ui.tree(
                tree_data,
                tick_strategy="leaf",
                on_tick=_on_tick,
            ).classes("w-full")

        with ui.row().classes("gap-2"):
            ui.button(
                "Save config",
                on_click=lambda: save_config(selected_nodes, parent_nodes_ids, config_path),
            ).props("color=primary")

            ui.button(
                "Clear selection",
                on_click=lambda: (
                    checked_ids.clear()
                    or selected_nodes.clear()
                    or setattr(tree, "value", [])
                    or tree.update()
                ),
            ).props("color=secondary outline")

            ui.label("").bind_text_from(
                selected_nodes,
                backward=lambda d: f"{len(d)} categories selected"
                if d
                else "No selection",
            ).classes("text-sm text-gray-500 self-center")


# ── Click CLI entry point ──────────────────────────────────────────────────────

@click.command(name="catalog-selector", context_settings={"help_option_names": ["-h", "--help"]})
@click.option(
    "--catalog-url",
    default=DEFAULT_CATALOG_URL,
    show_default=False,
    help="WB catalog JSON endpoint URL.",
)
@click.option(
    "--base-url",
    default=DEFAULT_BASE_URL,
    show_default=True,
    help="Wildberries base URL prepended to category paths.",
)
@click.option(
    "-o", "--output",
    "config_path",
    type=click.Path(path_type=Path),
    default=DEFAULT_CONFIG_PATH,
    show_default=True,
    help="Output YAML config path.",
)
@click.option(
    "--host",
    default=DEFAULT_HOST,
    show_default=True,
    help="Host to bind the NiceGUI server.",
)
@click.option(
    "--port",
    default=DEFAULT_PORT,
    show_default=True,
    type=int,
    help="Port for the NiceGUI server.",
)
@click.option(
    "--reload/--no-reload",
    default=False,
    show_default=True,
    help="Enable NiceGUI auto-reload (dev mode).",
)
def catalog_selector_cmd(
    catalog_url: str,
    base_url: str,
    config_path: Path,
    host: str,
    port: int,
    reload: bool,
) -> None:
    """
    Interactive Wildberries category selector (NiceGUI).

    Opens a web UI where you can browse and tick WB categories.
    Selected leaf categories are saved to a YAML file for load_catalog_to_pg.py.
    """
    click.echo(f"Starting NiceGUI on  http://{host}:{port}")
    click.echo(f"Config output: {config_path.resolve()}")

    @ui.page("/")
    def index() -> None:
        build_ui(
            catalog_url=catalog_url,
            base_url=base_url,
            config_path=config_path,
        )

    ui.run(
        host=host,
        port=port,
        title="WB Category Selector",
        dark=True,
        reload=reload,
        show=False,
        favicon=None,
    )


if __name__ == "__main__":
    catalog_selector_cmd()

#!/usr/bin/env python3
"""
measure_catalog_length.py — measures how many pages a WB catalog really has.

Why this exists
---------------
Every target in the catalog carries `known_pages_limit: 5`. That number was a blind
guess, and it is the reason the crawl only ever saw the first five pages of a catalog:
a catalog with 40 pages of goods is stored as if it had five, and the price history
the dataset is built on only ever describes the head of the category.

The number has to come from evidence, and the only honest evidence is the catalog
itself: page through the internal catalog API until WB answers with an empty
`products` array — that is where the catalog ends — and count.

What it does
------------
- takes one or more catalog ids and pages through them through a real AdsPower
  Chrome (the same browser path `wb_browser_fetch.py` uses),
- reads the token from the catalog YAML, or takes it from `--query`,
- reports how many pages the catalog has, how many products were seen, how many
  distinct products those were, whether the end of the catalog was reached, and the
  `known_pages_limit` to write into the YAML.

What it does NOT do
-------------------
- it does not measure a catalog without a token: without one the API answers with
  its rotating "popular presets" list, and the number would be the length of that
  list, not of the catalog,
- it does not write anything — not the YAML, not the database. It prints the number
  and leaves the decision to the operator,
- it does not crawl the catalog pages themselves: it only asks the API for the
  product list of each page,
- it does not report a number it did not measure: a browser that will not start or
  a profile WB has blocked is an error, not a catalog length.

Usage:
    python3 scripts/wb-catalog/measure_catalog_length.py --catalog 9467
    python3 scripts/wb-catalog/measure_catalog_length.py --catalog 9467 --catalog 9477 --max-pages 100
    python3 scripts/wb-catalog/measure_catalog_length.py --catalog 9467 --query "menu_v3_9467 карты памяти"
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from typing import Dict, List, Optional

import click
import yaml

# The browser path lives in scripts/wb_browser_fetch.py: the AdsPower profile, the
# warm-up, the request headers and the exact URL WB expects are all part of that
# script's contract, and a second copy of them here would drift from it.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from adspower_utils import get_cdp_ws_url  # noqa: E402
from wb_browser_fetch import (  # noqa: E402
    DEFAULT_HEADERS,
    DEFAULT_WARMUP_DELAY,
    default_warmup_url,
    fetch_products_page,
    resolve_cdp_ws_url,
)

DEFAULT_CATALOG_YAML = Path(__file__).resolve().parent.parent.parent / "wb_catalog_targets.yaml"
DEFAULT_PROFILE = "k18a6v75"
DEFAULT_MAX_PAGES = 50
DEFAULT_MARGIN = 1


class MeasureError(RuntimeError):
    """Something went wrong that makes the measurement meaningless."""


def load_catalog(yaml_path: Path) -> Dict[str, dict]:
    """Read the catalog YAML into `id -> page`."""
    try:
        with open(yaml_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
    except (OSError, yaml.YAMLError) as e:
        raise click.ClickException(f"Cannot parse {yaml_path}: {e}") from e

    pages = data.get("pages") if isinstance(data, dict) else None
    if not isinstance(pages, list):
        raise click.ClickException(f"{yaml_path} has no 'pages' list")

    return {str(page.get("id")): page for page in pages}


def resolve_token(catalog_id: str, page: Optional[dict], query: Optional[str],
                  yaml_path: Path) -> str:
    """The token this measurement will use, and where it came from."""
    if query:
        return query

    token = (page or {}).get("search_query") or ""
    if not token:
        # Measuring without a token measures WB's popular list. That is the one
        # mistake this whole tooling exists to prevent, so it is refused here rather
        # than reported as a number later.
        raise click.ClickException(
            f"catalog {catalog_id} has no search_query in {yaml_path}. "
            "Without a token the API answers with WB's rotating popular list, and the "
            "measurement would be the length of that list, not of the catalog. "
            "Run enrich_catalog_queries.py / refresh_catalog_tokens.py first, or pass "
            "--query explicitly."
        )
    return token


async def measure_catalog(
    ws_url: str,
    query: str,
    max_pages: int,
    delay: float,
    warmup_url: Optional[str],
    warmup_delay: float,
) -> dict:
    """Page through the catalog until WB runs out of products.

    The end of a catalog is an empty `products` array. A response without a
    `products` key is NOT the end — it is WB answering with an error envelope
    (a blocked profile, a challenge), and treating it as "the catalog is over"
    would report a short catalog where the truth is "the measurement failed".
    """
    from playwright.async_api import async_playwright

    pages_read = 0
    products_seen = 0
    product_ids: set = set()
    end_reached = False

    async with async_playwright() as p:
        browser = await p.chromium.connect_over_cdp(ws_url)
        if not browser.contexts:
            raise MeasureError("the browser has no context to fetch from")
        ctx = browser.contexts[0]
        page = await ctx.new_page()

        try:
            target = warmup_url or default_warmup_url(query)
            click.echo(f"  warm-up: {target}", err=True)
            try:
                await page.goto(target, timeout=30000)
            except Exception as e:  # noqa: BLE001 — the warm-up is best effort
                click.echo(f"  warm-up did not complete ({e}), continuing", err=True)
            await asyncio.sleep(warmup_delay)

            for page_num in range(1, max_pages + 1):
                data = await fetch_products_page(page, query, page_num, DEFAULT_HEADERS)

                if not isinstance(data, dict) or "products" not in data:
                    reason = (data or {}).get("message") or (data or {}).get("msg") or data
                    raise MeasureError(
                        f"unexpected WB response on page {page_num}: {str(reason)[:200]}"
                    )

                products = data.get("products") or []
                if not products:
                    end_reached = True
                    break

                pages_read += 1
                products_seen += len(products)
                for prod in products:
                    product_id = prod.get("id")
                    if product_id is not None:
                        product_ids.add(product_id)

                click.echo(f"  page {page_num}: {len(products)} products", err=True)

                if page_num < max_pages:
                    await asyncio.sleep(delay)
        finally:
            # Only our own tab is closed, and only when the context has others:
            # closing the last one would kill the AdsPower browser.
            try:
                if len(ctx.pages) > 1:
                    await page.close()
            except Exception:  # noqa: BLE001 — closing must not mask the real error
                pass

    return {
        "pages_read": pages_read,
        "products_seen": products_seen,
        "distinct_product_ids": len(product_ids),
        "end_reached": end_reached,
    }


def report(catalog_id: str, name: str, token: str, source: str, result: dict,
           max_pages: int, margin: int) -> None:
    """Print what was measured and what to write into the YAML."""
    pages_read = result["pages_read"]

    if result["end_reached"]:
        limit = pages_read + margin
        recommendation = str(limit)
        note = "the end of the catalog was reached"
    else:
        limit = max_pages
        recommendation = f"at least {max_pages}"
        note = f"the safety cap of {max_pages} pages was reached — the catalog is longer"

    click.echo("")
    click.echo(f"=== catalog {catalog_id} — {name} ===")
    click.echo(f"  token:                {token} (from {source})")
    click.echo(f"  pages read:           {pages_read}")
    click.echo(f"  products seen:        {result['products_seen']}")
    click.echo(f"  distinct product ids: {result['distinct_product_ids']}")
    click.echo(f"  end of catalog:       {'yes' if result['end_reached'] else 'no'} — {note}")
    click.echo(f"  known_pages_limit:    {recommendation}")


@click.command(context_settings=dict(help_option_names=["-h", "--help"]))
@click.option(
    "--catalog",
    "catalog_ids",
    multiple=True,
    required=True,
    help="WB catalog id to measure (repeat for several catalogs)",
)
@click.option("--query", default=None, help="WB search token, e.g. 'menu_v3_9467 карты памяти'")
@click.option(
    "--yaml",
    "yaml_path",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=DEFAULT_CATALOG_YAML,
    show_default=True,
    help="Catalog YAML the token and the name are read from",
)
@click.option("--profile", default=DEFAULT_PROFILE, show_default=True, help="AdsPower profile id")
@click.option(
    "--max-pages",
    default=DEFAULT_MAX_PAGES,
    type=int,
    show_default=True,
    help="Safety cap: the measurement stops here and reports 'at least N'",
)
@click.option("--delay", default=2.0, type=float, show_default=True, help="Delay between pages (s)")
@click.option(
    "--warmup-delay",
    default=DEFAULT_WARMUP_DELAY,
    type=float,
    show_default=True,
    help="Seconds to wait for the anti-bot challenge (default: %(default)s)",
)
@click.option("--warmup-url", default=None, help="URL used to warm the WB session")
@click.option(
    "--margin",
    default=DEFAULT_MARGIN,
    type=int,
    show_default=True,
    help="Pages added to the measured length when recommending known_pages_limit",
)
def measure_catalog_length(
    catalog_ids: List[str],
    query: Optional[str],
    yaml_path: Path,
    profile: str,
    max_pages: int,
    delay: float,
    warmup_delay: float,
    warmup_url: Optional[str],
    margin: int,
) -> None:
    """Measure how many pages a WB catalog has, through a real browser."""
    if max_pages < 1:
        raise click.ClickException("--max-pages must be at least 1")

    catalog = load_catalog(yaml_path)

    # The tokens are resolved before the browser is touched: a catalog without a
    # token is a configuration error, and it must be reported as such even on a
    # machine where the browser would have failed too.
    targets = []
    for catalog_id in catalog_ids:
        page = catalog.get(str(catalog_id))
        name = (page or {}).get("name") or "(not in the catalog YAML)"
        token = resolve_token(str(catalog_id), page, query, yaml_path)
        source = "--query" if query else str(yaml_path)
        targets.append((str(catalog_id), name, token, source))

    try:
        ws_url = asyncio.run(resolve_cdp_ws_url(profile))
        if not ws_url:
            ws_url = get_cdp_ws_url(profile)
    except Exception as e:  # noqa: BLE001 — an unreachable AdsPower is an error, not a number
        raise click.ClickException(
            f"cannot reach the AdsPower Local API for profile '{profile}': {e}. "
            "Without a browser there is nothing to measure."
        ) from e
    if not ws_url:
        raise click.ClickException(
            f"cannot get the CDP URL of the AdsPower profile '{profile}'. "
            "Open it in AdsPower or check its Local API — without a browser there is "
            "nothing to measure."
        )

    click.echo(f"Connecting to AdsPower Chrome (profile {profile})...", err=True)

    for catalog_id, name, token, source in targets:
        click.echo("")
        click.echo(f"Measuring catalog {catalog_id} ({name})...", err=True)

        try:
            result = asyncio.run(
                measure_catalog(
                    ws_url=ws_url,
                    query=token,
                    max_pages=max_pages,
                    delay=delay,
                    warmup_url=warmup_url,
                    warmup_delay=warmup_delay,
                )
            )
        except MeasureError as e:
            raise click.ClickException(f"catalog {catalog_id}: {e}") from e
        except Exception as e:  # noqa: BLE001 — a browser failure is an error, not a number
            raise click.ClickException(
                f"catalog {catalog_id}: the measurement failed ({e}). "
                "No length is reported: a browser that does not start or a blocked "
                "profile has no answer to give."
            ) from e

        report(str(catalog_id), name, token, source, result, max_pages, margin)


if __name__ == "__main__":
    measure_catalog_length()

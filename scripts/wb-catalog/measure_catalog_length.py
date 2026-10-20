#!/usr/bin/env python3
"""
measure_catalog_length.py — measures how many pages a WB catalog really has.

Why this exists
---------------
Every target in the catalog carries `known_pages_limit: 5`. That number was a blind
guess, and it is the reason the crawl only ever saw the first five pages of a catalog:
a catalog with thousands of goods is stored as if it had five, and the price history
the dataset is built on only ever describes the head of the category.

The number has to come from evidence, and the only honest evidence is the catalog
itself: page through the internal catalog API until WB answers with an empty
`products` array — that is where the catalog ends — and count.

How it measures
---------------
Reading every page of every catalog would take hours and would earn a block from the
anti-bot on the way. WB itself answers the length: the catalog response carries
`total`, the number of products in the catalog. One request gives the length, and
then the boundary is verified with a second request — the page where the pagination
actually ends.

Two boundaries are possible, and they are different numbers:

- **The catalog ends.** WB answers with an empty `products` array. The crawl needs
  one page past the last product page, so that it reads the empty page and ends
  naturally instead of stopping at the cap and looking truncated.
- **The API cap cuts the pagination.** WB refuses the page number with
  `{"error": "binding request: page param malformed"}`. There is no empty page to
  read, and asking for one would fail the target. The cap is the last readable page.

The report records which of the two happened, so the number in the YAML can be read
back with the reason it is that number.

What it does NOT do
-------------------
- it does not measure a catalog without a token: without one the API answers with
  its rotating "popular presets" list, and the number would be the length of that
  list, not of the catalog,
- it does not write anything — not the YAML, not the database. It writes a report;
  `apply_measured_limits.py` turns that report into `known_pages_limit`,
- it does not report a number it did not measure: a browser that will not start or
  a profile WB has blocked is an error, not a catalog length.

Usage:
    python3 scripts/wb-catalog/measure_catalog_length.py --catalog 9467
    python3 scripts/wb-catalog/measure_catalog_length.py --catalog 9467 --catalog 9477
    python3 scripts/wb-catalog/measure_catalog_length.py --all --report /tmp/lengths.json
    python3 scripts/wb-catalog/measure_catalog_length.py --catalog 9467 --query "menu_v3_9467 карты памяти"
"""

from __future__ import annotations

import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import click

# The browser path lives in scripts/wb_browser_fetch.py: the AdsPower profile, the
# warm-up, the request headers and the exact URL WB expects are all part of that
# script's contract, and a second copy of them here would drift from it.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from adspower_utils import get_cdp_ws_url  # noqa: E402
from wb_browser_fetch import (  # noqa: E402
    DEFAULT_HEADERS,
    DEFAULT_WARMUP_DELAY,
    default_warmup_url,
    fetch_products_page,
    resolve_cdp_ws_url,
)

from catalog_length import (  # noqa: E402
    BOUNDARY_API_CAP,
    BOUNDARY_CATALOG_END,
    is_page_refusal,
    plan_limit,
    read_total,
)
from catalog_report import (  # noqa: E402
    STATUS_BLOCKED,
    STATUS_ERROR,
    STATUS_MEASURED,
    merge_report,
)

DEFAULT_CATALOG_YAML = Path(__file__).resolve().parent.parent.parent / "wb_catalog_targets.yaml"
DEFAULT_PROFILE = "k18a6v75"
DEFAULT_MAX_PAGES = 50
DEFAULT_PAGE_CAP = 60
DEFAULT_DELAY = 3.0
REPORT_VERSION = 1


class MeasureError(RuntimeError):
    """Something went wrong that makes the measurement meaningless."""


class BlockedError(MeasureError):
    """WB refused to answer — an anti-bot challenge, not a measurement."""


def load_catalog(yaml_path: Path) -> Dict[str, dict]:
    """Read the catalog YAML into `id -> page`."""
    import yaml

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


async def fetch_page(page, query: str, page_num: int) -> Tuple[str, object]:
    """Ask WB for one page and say what came back.

    Three answers are possible and they are not interchangeable:

    - `("ok", data)` — a page with a `products` array, possibly empty. An empty
      array is the end of the catalog.
    - `("refused", data)` — WB rejected the page *number*. The pagination is capped.
    - `("blocked", reason)` — WB served something that is not an API answer at all
      (the anti-bot challenge page). There is no measurement in that.
    """
    try:
        data = await fetch_products_page(page, query, page_num, DEFAULT_HEADERS)
    except Exception as e:  # noqa: BLE001 — the failure mode decides what it means
        message = str(e)
        if "not valid JSON" in message or "<!doctype" in message.lower():
            return "blocked", message
        raise MeasureError(f"page {page_num} could not be fetched: {message}") from e

    if not isinstance(data, dict) or "products" not in data:
        if is_page_refusal(data):
            return "refused", data
        reason = (data or {}).get("message") or (data or {}).get("msg") or data
        raise BlockedError(f"unexpected WB response on page {page_num}: {str(reason)[:200]}")

    return "ok", data


async def find_last_full_page(fetch, low: int, high: int, delay: float) -> Tuple[int, str]:
    """The last page WB still fills with products, and the boundary that follows it.

    Pages are full up to the end of the catalog and then stop being full: either the
    answer is an empty product list (the catalog ended) or the page number is refused
    (the API cap). Both answers are "not full", and both are monotone — a page that
    came back empty or refused is followed by pages that are empty or refused too.
    That monotonicity is what makes the boundary findable by bisection instead of by
    reading every page of every catalog.

    `low` is a page known to be full, `high` a page known not to be. The kind of
    `high` is the boundary: refused means the API cap cut the pagination, empty
    means the catalog ended by itself.
    """
    boundary = BOUNDARY_CATALOG_END
    while high - low > 1:
        middle = (low + high) // 2
        kind, _ = await fetch(middle)
        if kind == "refused":
            high = middle
            boundary = BOUNDARY_API_CAP
        elif kind == "blocked":
            raise BlockedError(f"WB served a challenge instead of an answer on page {middle}")
        else:
            low = middle
        await asyncio.sleep(delay)
    return low, boundary


async def measure_catalog(
    page,
    query: str,
    page_cap: int,
    delay: float,
    max_pages: int,
) -> dict:
    """Measure one catalog: how many pages it has and where its pagination ends.

    The number WB reports as `total` is *not* the length of the catalog: for
    `menu_v3_9463 смартфон` it says 113 786 products, while the pagination ends after
    59 pages. The count describes the search, the pagination describes the catalog,
    and only the pagination is what the crawler walks. So the length is found by
    asking where the pages stop being full.
    """
    kind, first = await fetch_page(page, query, 1)
    if kind == "refused":
        raise MeasureError("WB refused page 1 — the request itself is wrong")
    if kind == "blocked":
        raise BlockedError("WB served a challenge instead of an API answer on page 1")

    products = first.get("products") or []
    total = read_total(first)
    page_size = len(products)

    if page_size == 0:
        # The catalog is empty: page 1 is the last page.
        return plan_limit(total, page_size, BOUNDARY_CATALOG_END, 0, page_cap)

    if total is None:
        # WB did not say how long the catalog is. Then the only way to know is to
        # read it — which is slow, but honest.
        return await measure_by_reading(page, query, delay, max_pages, page_cap)

    async def fetch(number: int) -> Tuple[str, object]:
        return await fetch_page(page, query, number)

    # The highest page WB is willing to serve is the best first guess: a catalog
    # that reaches it is a catalog that ends at the API cap.
    kind, data = await fetch(page_cap)
    if kind == "blocked":
        raise BlockedError(f"WB served a challenge instead of an answer on page {page_cap}")

    if kind == "refused":
        # The cap is lower than the default: find where the pages stop being full.
        pages_available, boundary = await find_last_full_page(fetch, 1, page_cap, delay)
        return plan_limit(total, page_size, boundary, pages_available, page_cap)

    if data.get("products"):
        # The last page WB serves is full. One page further tells whether the
        # catalog ended there or the API refuses to go further.
        kind_after, _ = await fetch(page_cap + 1)
        await asyncio.sleep(delay)
        if kind_after == "ok":
            # WB served a page beyond the cap that was assumed. The number written
            # to the YAML would be a number about the assumption, not about the
            # catalog, so the measurement is refused instead.
            raise MeasureError(
                f"WB served page {page_cap + 1} — the page cap of {page_cap} is wrong "
                f"for this catalog. Re-run with a larger --page-cap."
            )
        boundary = BOUNDARY_API_CAP if kind_after == "refused" else BOUNDARY_CATALOG_END
        return plan_limit(total, page_size, boundary, page_cap, page_cap)

    # The page at the cap came back empty: the catalog ended somewhere before it.
    pages_available, boundary = await find_last_full_page(fetch, 1, page_cap, delay)
    return plan_limit(total, page_size, boundary, pages_available, page_cap)


async def measure_by_reading(page, query: str, delay: float, max_pages: int, page_cap: int) -> dict:
    """Fallback for a catalog whose length WB does not report: read it page by page."""
    pages_available = 0
    products_seen = 0
    for page_num in range(1, max_pages + 1):
        kind, data = await fetch_page(page, query, page_num)
        if kind == "blocked":
            raise BlockedError(f"WB served a challenge instead of an answer on page {page_num}")
        if kind == "refused":
            return plan_limit(None, 0, BOUNDARY_API_CAP, pages_available, page_cap)
        products = data.get("products") or []
        if not products:
            return plan_limit(None, products_seen, BOUNDARY_CATALOG_END, pages_available, page_cap)
        pages_available = page_num
        products_seen += len(products)
        await asyncio.sleep(delay)
    return plan_limit(None, products_seen, BOUNDARY_API_CAP, pages_available, page_cap)


async def measure_one(
    ws_url: str,
    query: str,
    page_cap: int,
    delay: float,
    max_pages: int,
    warmup_url: Optional[str],
    warmup_delay: float,
) -> dict:
    """Warm the session up, measure the catalog, close only our own tab."""
    from playwright.async_api import async_playwright

    async with async_playwright() as p:
        browser = await p.chromium.connect_over_cdp(ws_url)
        if not browser.contexts:
            raise MeasureError("the browser has no context to fetch from")
        ctx = browser.contexts[0]
        page = await ctx.new_page()

        try:
            target = warmup_url or default_warmup_url(query)
            try:
                await page.goto(target, timeout=30000)
            except Exception as e:  # noqa: BLE001 — the warm-up is best effort
                click.echo(f"  warm-up did not complete ({e}), continuing", err=True)
            await asyncio.sleep(warmup_delay)

            return await measure_catalog(page, query, page_cap, delay, max_pages)
        finally:
            # Only our own tab is closed, and only when the context has others:
            # closing the last one would kill the AdsPower browser.
            try:
                if len(ctx.pages) > 1:
                    await page.close()
            except Exception:  # noqa: BLE001 — closing must not mask the real error
                pass


def report_for(
    catalog_id: str,
    name: str,
    token: str,
    profile: str,
    status: str,
    plan: Optional[dict] = None,
    error: Optional[str] = None,
) -> dict:
    """One row of the report: what was measured, and how it ended."""
    record = {
        "id": catalog_id,
        "name": name,
        "search_query": token,
        "profile": profile,
        "status": status,
        "measured_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "error": error,
    }
    if plan:
        record.update(plan)
    return record


def write_report(path: Path, report: dict) -> dict:
    """Write the report atomically — a killed run must not leave a half-written file.

    An existing report of the same version is merged into, so that a repeat of the
    catalogs the anti-bot blocked adds to the evidence instead of replacing it.
    """
    if path.exists():
        try:
            with open(path, "r", encoding="utf-8") as f:
                existing = json.load(f)
        except (OSError, json.JSONDecodeError):
            existing = {}
        if existing.get("version") == report.get("version"):
            report = merge_report(existing, report)

    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
        f.write("\n")
    tmp.replace(path)
    return report


@click.command(context_settings=dict(help_option_names=["-h", "--help"]))
@click.option(
    "--catalog",
    "catalog_ids",
    multiple=True,
    help="WB catalog id to measure (repeat for several catalogs)",
)
@click.option("--all", "measure_all", is_flag=True, help="Measure every catalog in the YAML")
@click.option("--query", default=None, help="WB search token, e.g. 'menu_v3_9467 карты памяти'")
@click.option(
    "--yaml",
    "yaml_path",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=DEFAULT_CATALOG_YAML,
    show_default=True,
    help="Catalog YAML the token and the name are read from",
)
@click.option(
    "--profile",
    "profiles",
    multiple=True,
    help="AdsPower profile id (repeat to rotate profiles between catalogs)",
)
@click.option(
    "--page-cap",
    default=DEFAULT_PAGE_CAP,
    type=int,
    show_default=True,
    help="Highest page number WB serves; verified per catalog",
)
@click.option(
    "--max-pages",
    default=DEFAULT_MAX_PAGES,
    type=int,
    show_default=True,
    help="Safety cap for the read-every-page fallback",
)
@click.option("--delay", default=DEFAULT_DELAY, type=float, show_default=True, help="Delay between pages (s)")
@click.option(
    "--warmup-delay",
    default=DEFAULT_WARMUP_DELAY,
    type=float,
    show_default=True,
    help="Seconds to wait for the anti-bot challenge (default: %(default)s)",
)
@click.option("--warmup-url", default=None, help="URL used to warm the WB session")
@click.option(
    "--report",
    "report_path",
    type=click.Path(dir_okay=False, path_type=Path),
    default=None,
    help="JSON report to write (written after every catalog)",
)
@click.option(
    "--keep-going",
    is_flag=True,
    help="Measure the remaining catalogs after a failure instead of stopping",
)
def measure_catalog_length(
    catalog_ids: List[str],
    measure_all: bool,
    query: Optional[str],
    yaml_path: Path,
    profiles: List[str],
    page_cap: int,
    max_pages: int,
    delay: float,
    warmup_delay: float,
    warmup_url: Optional[str],
    report_path: Optional[Path],
    keep_going: bool,
) -> None:
    """Measure how many pages a WB catalog has, through a real browser."""
    if page_cap < 1:
        raise click.ClickException("--page-cap must be at least 1")
    if max_pages < 1:
        raise click.ClickException("--max-pages must be at least 1")
    if not catalog_ids and not measure_all:
        raise click.ClickException("nothing to measure: pass --catalog or --all")

    catalog = load_catalog(yaml_path)

    if measure_all:
        wanted = list(catalog.keys())
    else:
        wanted = [str(c) for c in catalog_ids]
    if query and len(wanted) > 1:
        raise click.ClickException("--query applies to a single catalog")

    # The tokens are resolved before the browser is touched: a catalog without a
    # token is a configuration error, and it must be reported as such even on a
    # machine where the browser would have failed too.
    targets = []
    for catalog_id in wanted:
        page = catalog.get(str(catalog_id))
        name = (page or {}).get("name") or "(not in the catalog YAML)"
        token = resolve_token(str(catalog_id), page, query, yaml_path)
        source = "--query" if query else str(yaml_path)
        targets.append((str(catalog_id), name, token, source))

    profile_list = list(profiles) if profiles else [DEFAULT_PROFILE]
    ws_urls: Dict[str, str] = {}

    report = {
        "version": REPORT_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "settings": {
            "page_cap": page_cap,
            "delay": delay,
            "max_pages": max_pages,
            "profiles": profile_list,
            "yaml": str(yaml_path),
        },
        "catalogs": [],
    }

    failures = 0
    # A report that is already there was written by an earlier run: this run is the
    # next one, and the report says how many there were.
    run_number = 1
    if report_path and report_path.exists():
        try:
            with open(report_path, "r", encoding="utf-8") as f:
                run_number = int(json.load(f).get("runs") or 1) + 1
        except (OSError, json.JSONDecodeError, TypeError, ValueError):
            run_number = 1
    report["runs"] = run_number

    for index, (catalog_id, name, token, source) in enumerate(targets):
        profile = profile_list[index % len(profile_list)]
        click.echo("")
        click.echo(f"Measuring catalog {catalog_id} ({name}) with profile {profile}...", err=True)

        if profile not in ws_urls:
            try:
                ws_url = asyncio.run(resolve_cdp_ws_url(profile))
                if not ws_url:
                    ws_url = get_cdp_ws_url(profile)
            except Exception as e:  # noqa: BLE001 — an unreachable AdsPower is an error
                raise click.ClickException(
                    f"cannot reach the AdsPower Local API for profile '{profile}': {e}. "
                    "Without a browser there is nothing to measure."
                ) from e
            if not ws_url:
                raise click.ClickException(
                    f"cannot get the CDP URL of the AdsPower profile '{profile}'. "
                    "Open it in AdsPower or check its Local API — without a browser "
                    "there is nothing to measure."
                )
            ws_urls[profile] = ws_url

        try:
            plan = asyncio.run(
                measure_one(
                    ws_url=ws_urls[profile],
                    query=token,
                    page_cap=page_cap,
                    delay=delay,
                    max_pages=max_pages,
                    warmup_url=warmup_url,
                    warmup_delay=warmup_delay,
                )
            )
            status = STATUS_MEASURED
            record = report_for(catalog_id, name, token, profile, status, plan=plan)
        except BlockedError as e:
            failures += 1
            status = STATUS_BLOCKED
            record = report_for(catalog_id, name, token, profile, status, error=str(e))
        except Exception as e:  # noqa: BLE001 — a browser failure is an error, not a number
            failures += 1
            status = STATUS_ERROR
            record = report_for(catalog_id, name, token, profile, status, error=str(e))

        report["catalogs"].append(record)
        if report_path:
            report = write_report(report_path, report)

        if status == STATUS_MEASURED:
            click.echo(
                f"  pages available: {record['pages_available']} "
                f"(boundary: {record['boundary']}, wb_total: {record['wb_total']}) "
                f"→ known_pages_limit: {record['known_pages_limit']}",
                err=True,
            )
        else:
            click.echo(f"  {status}: {record['error']}", err=True)
            if not keep_going:
                break

    if report_path:
        report = write_report(report_path, report)
        click.echo(f"\nReport written to {report_path}", err=True)

    measured = sum(1 for c in report["catalogs"] if c["status"] == STATUS_MEASURED)
    click.echo(
        f"Measured {measured} of {len(report['catalogs'])} catalog(s); {failures} failed.",
        err=True,
    )
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    measure_catalog_length()

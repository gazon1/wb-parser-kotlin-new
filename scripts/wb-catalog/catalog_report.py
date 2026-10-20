#!/usr/bin/env python3
"""
The report of a catalog-length measurement, and what it says about the YAML.

Why this module exists
-----------------------
The measurement writes a report; a second script writes the numbers into
`wb_catalog_targets.yaml`. The rule between them is simple and worth keeping in
one place: **a number reaches the YAML only if the report carries it**, and a
report record that says "blocked" or "failed" carries no number at all.

That rule is pure logic — it reads a dict and produces a plan — so it lives here,
in a module with no third-party imports. The gate that runs the script tests
(`python3 -m unittest discover -s scripts/tests`) executes on a bare Python
without `click` or `PyYAML` installed, and a test that cannot run there is a
test that does not protect anything.

The CLI scripts (`measure_catalog_length.py`, `apply_measured_limits.py`) import
this module and do the talking to the browser, the file and the terminal.
"""

from __future__ import annotations

from typing import Dict, List, Optional, Tuple

# The measurement statuses a report record can carry. Only the first one carries
# a number; the others carry the reason there is none.
STATUS_MEASURED = "measured"
STATUS_BLOCKED = "blocked"
STATUS_ERROR = "error"


class Change:
    """One target whose `known_pages_limit` changes."""

    def __init__(self, catalog_id: str, name: str, old: Optional[int], new: int,
                 boundary: str, pages_available: int) -> None:
        self.catalog_id = catalog_id
        self.name = name
        self.old = old
        self.new = new
        self.boundary = boundary
        self.pages_available = pages_available

    def __str__(self) -> str:
        return (
            f"  {self.catalog_id:>8} {self.name[:40]:<40} "
            f"{str(self.old):>6} → {self.new:<6} ({self.boundary}, "
            f"{self.pages_available} pages)"
        )


def measured_limits(report: dict) -> Dict[str, dict]:
    """The catalogs the report actually measured, `id -> record`.

    A record that was not measured is not here: the report of a failed or blocked
    measurement carries no number, and a number that was not measured must not reach
    the YAML.
    """
    limits: Dict[str, dict] = {}
    for record in report.get("catalogs") or []:
        if record.get("status") != STATUS_MEASURED:
            continue
        limit = record.get("known_pages_limit")
        if not isinstance(limit, int) or limit < 1:
            continue
        limits[str(record.get("id"))] = record
    return limits


def plan_changes(pages: List[dict], limits: Dict[str, dict]) -> Tuple[List[Change], List[str]]:
    """What would change in the YAML, and which measured catalogs are not in it."""
    changes: List[Change] = []
    seen = set()

    for page in pages:
        catalog_id = str(page.get("id"))
        record = limits.get(catalog_id)
        if record is None:
            continue
        seen.add(catalog_id)
        new = int(record["known_pages_limit"])
        old = page.get("known_pages_limit")
        old = old if isinstance(old, int) else None
        if old == new:
            continue
        changes.append(
            Change(
                catalog_id=catalog_id,
                name=str(page.get("name") or ""),
                old=old,
                new=new,
                boundary=str(record.get("boundary")),
                pages_available=int(record.get("pages_available") or 0),
            )
        )

    missing = [cid for cid in limits if cid not in seen]
    return changes, missing


def merge_report(existing: dict, fresh: dict) -> dict:
    """Fold a new run into the report of the previous one.

    A run that was blocked by the anti-bot has to be repeated, and a repeat must not
    throw away what the first run measured: the catalog that was blocked keeps its
    place in the report and is replaced only when a new run says something about it.
    """
    records = {str(record.get("id")): record for record in existing.get("catalogs") or []}
    order = [str(record.get("id")) for record in existing.get("catalogs") or []]

    for record in fresh.get("catalogs") or []:
        catalog_id = str(record.get("id"))
        if catalog_id not in records:
            order.append(catalog_id)
        records[catalog_id] = record

    merged = dict(fresh)
    merged["catalogs"] = [records[catalog_id] for catalog_id in order]
    merged["runs"] = int(fresh.get("runs") or 1)
    return merged

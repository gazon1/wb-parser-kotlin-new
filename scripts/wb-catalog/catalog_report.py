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

The policy between two measurements
----------------------------------
When a catalog was measured more than once, the number that goes into the YAML is
the **maximum** over the measurements, never the last one. WB's pagination shifts
between runs — the same catalog measured 59 and 60 available pages on different
passes — so "the last measurement" would make the YAML depend on which run
happened to be written last, and a re-measurement could silently shrink a catalog
that had grown. The maximum is the number that is true of every measurement.

The CLI scripts (`measure_catalog_length.py`, `apply_measured_limits.py`,
`sync_measurement_metadata.py`) import this module and do the talking to the
browser, the file and the terminal.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple

# The measurement statuses a report record can carry. Only the first one carries
# a number; the others carry the reason there is none.
STATUS_MEASURED = "measured"
STATUS_BLOCKED = "blocked"
STATUS_ERROR = "error"


def parse_measured_at(value: object) -> Optional[datetime]:
    """The instant a measurement was taken, or None.

    The report writes ISO-8601 with an offset (`2026-10-20T13:34:31+00:00`). A `Z`
    suffix is the same instant written differently and must not look like another
    measurement. A naive timestamp is a wall time rather than an instant — it means
    different moments in different zones — so it is not an instant at all.
    """
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith(("Z", "z")):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(timezone.utc)


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

    When a catalog was measured more than once, the record kept is the **best** one,
    and "best" means the largest limit — never the last one. WB's pagination shifts
    between runs: the same catalog measured 59 and 60 available pages on different
    passes, and the boundary moved with it. "The last measurement" is therefore a coin
    flip, and the number in the YAML would depend on which run happened to be written
    last. The maximum is the number that is true of every measurement: it never
    under-reads a catalog that grew, and it never asks WB for a page the API refuses
    (the limit is clamped to the API cap anyway).

    Ties are broken by the measurement date — the newer pass wins — and a tie that
    survives that is broken by the order of the report, so the result never depends on
    a dictionary's iteration order.
    """
    limits: Dict[str, dict] = {}
    for record in report.get("catalogs") or []:
        if record.get("status") != STATUS_MEASURED:
            continue
        limit = record.get("known_pages_limit")
        if not isinstance(limit, int) or limit < 1:
            continue
        catalog_id = str(record.get("id"))
        current = limits.get(catalog_id)
        if current is None or _is_better(record, current):
            limits[catalog_id] = record
    return limits


def _is_better(candidate: dict, current: dict) -> bool:
    """Whether `candidate` is a better measurement of a catalog than `current`.

    The rule is the maximum, then the newer pass, then the later record.
    """
    candidate_limit = candidate.get("known_pages_limit")
    current_limit = current.get("known_pages_limit")
    if candidate_limit != current_limit:
        return bool(candidate_limit) and candidate_limit > current_limit

    candidate_at = parse_measured_at(candidate.get("measured_at"))
    current_at = parse_measured_at(current.get("measured_at"))
    if candidate_at != current_at:
        if candidate_at is None:
            return False
        if current_at is None:
            return True
        return candidate_at > current_at

    return True


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

    The merge is per field, not per record: a field the fresh record does not carry
    keeps the value the old one had. A pass that observed only the subject id of a
    catalog therefore does not erase the length measured by an earlier pass — the two
    are different facts, and one run is not always able to answer both.
    """
    records = {str(record.get("id")): record for record in existing.get("catalogs") or []}
    order = [str(record.get("id")) for record in existing.get("catalogs") or []]

    for record in fresh.get("catalogs") or []:
        catalog_id = str(record.get("id"))
        if catalog_id not in records:
            order.append(catalog_id)
            records[catalog_id] = dict(record)
            continue
        merged_record = {**records[catalog_id], **record}
        if merged_record.get("status") == STATUS_MEASURED:
            # A measurement that succeeded has no error to report any more.
            merged_record.pop("error", None)
        records[catalog_id] = merged_record

    merged = dict(fresh)
    merged["catalogs"] = [records[catalog_id] for catalog_id in order]
    merged["runs"] = int(fresh.get("runs") or 1)
    return merged

#!/usr/bin/env python3
"""
The measurement metadata of a target: the number, and the evidence behind it.

Why this module exists
-----------------------
`known_pages_limit` used to be a number in the YAML with nothing behind it: no date,
no boundary, no source. A number like that cannot be checked. In a month nobody can
tell whether "60" is still true, and nobody can tell *which* of the two boundaries
produced it — a catalog that ended by itself, or one that the WB API cap cut short.
The second case means the crawl stores a slice of the category rather than the
category, and the truncation counter fires honestly about it.

So the number travels with its evidence: when it was measured, where the pagination
ended, where the number came from, and which WB subject the catalog's products carry.

This module is the pure part of that. It decides what a target's measurement metadata
is, reduces a run of measurements to the artifact the repository keeps, and compares
the YAML with the artifact. It imports nothing outside the standard library: the gate
that runs the tests (`python3 -m unittest discover -s scripts/tests`) runs on a bare
Python without `click` or `PyYAML`, and the CI `static` job runs the gate itself on
such a Python too.

The policy: the maximum, never the last
----------------------------------------
**The limit that goes into the YAML is the MAXIMUM over the measurements, never the
last one.**

WB's pagination shifts between runs: the same catalog measured 59 and 60 available
pages on different passes, and the boundary moved with it. "The last measurement" is
therefore a coin flip — the number in the YAML would depend on which run happened to
be written last, and a re-measurement could silently shrink a catalog that had grown.

The maximum is the one number that is true of every measurement: it never under-reads
a catalog that grew, and it never asks WB for a page the API refuses (the limit is
clamped to the API cap anyway). The rule itself lives in
`catalog_report.measured_limits`, which every consumer of a report goes through —
`apply_measured_limits.py`, the metadata sync and the gate all inherit it from there
rather than each deciding on its own.

The metadata of a report record
-------------------------------
| field                      | where it comes from                        |
|----------------------------|--------------------------------------------|
| `known_pages_limit`        | the measurement (the boundary decides it)  |
| `known_pages_limit_measured_at` | the moment the measurement was taken |
| `known_pages_limit_boundary`     | `catalog_end` or `api_cap`          |
| `known_pages_limit_source`       | `measurement` — the number was measured |
| `wb_subject_id`            | the subject id the catalog's products carry |
"""

from __future__ import annotations

from typing import Dict, List

from catalog_report import measured_limits, parse_measured_at  # noqa: F401  (re-exported)

# The YAML field names. They are also the database column names: the loader writes
# the YAML into `crawl_targets`, and a second name for the same thing is a second
# thing that can drift.
FIELD_LIMIT = "known_pages_limit"
FIELD_MEASURED_AT = "known_pages_limit_measured_at"
FIELD_BOUNDARY = "known_pages_limit_boundary"
FIELD_SOURCE = "known_pages_limit_source"
FIELD_SUBJECT_ID = "wb_subject_id"

# Where the number came from. Free-form in the database, but the one value the
# tooling writes is this one.
SOURCE_MEASUREMENT = "measurement"

# The version of the artifact format. Bumped when the shape of a record changes.
ARTIFACT_VERSION = 1

# The fields the artifact keeps, per target. The full report carries more (the
# profile, the estimated pages, the observed page size); the artifact carries what
# the gate compares and what a reader needs to judge the number.
ARTIFACT_FIELDS = (
    "id",
    "name",
    "boundary",
    "measured_at",
    "pages_available",
    FIELD_LIMIT,
    "wb_total",
    FIELD_SUBJECT_ID,
)


def same_instant(left: object, right: object) -> bool:
    """Whether two timestamps describe the same moment.

    Two timestamps that cannot be parsed are compared as text — a value that is not a
    timestamp at all is still a value, and two different ones are still different.
    """
    left_at = parse_measured_at(left)
    right_at = parse_measured_at(right)
    if left_at is not None and right_at is not None:
        return left_at == right_at
    return str(left or "") == str(right or "")


def metadata_of(record: dict) -> dict:
    """The measurement metadata of a report record, as the YAML fields."""
    metadata = {
        FIELD_MEASURED_AT: record.get("measured_at"),
        FIELD_BOUNDARY: record.get("boundary"),
        FIELD_SOURCE: SOURCE_MEASUREMENT,
    }
    subject_id = record.get(FIELD_SUBJECT_ID)
    if isinstance(subject_id, int) and not isinstance(subject_id, bool):
        metadata[FIELD_SUBJECT_ID] = subject_id
    return metadata


def metadata_for_catalog(limits: Dict[str, dict], catalog_id: object) -> dict:
    """The measurement metadata of one catalog, or an empty dict.

    An empty dict means "this catalog was not measured" — and a target that was not
    measured says so: its metadata columns stay NULL rather than carrying a date
    that nobody observed.
    """
    record = limits.get(str(catalog_id))
    if record is None:
        return {}
    return metadata_of(record)


def best_measurements(records: List[dict]) -> Dict[str, dict]:
    """The best record per catalog, plus the subject id any pass observed.

    The limit, its boundary and its date belong to one measurement, and the best
    measurement is the one with the largest limit (see `catalog_report`). The subject
    id is a different fact: it is a property of the catalog's products, and a pass
    that observed it must not lose it because another pass measured a bigger limit.
    """
    limits = measured_limits({"catalogs": records})
    for record in records:
        subject_id = record.get(FIELD_SUBJECT_ID)
        if not isinstance(subject_id, int) or isinstance(subject_id, bool):
            continue
        catalog_id = str(record.get("id"))
        best = limits.get(catalog_id)
        if best is not None and FIELD_SUBJECT_ID not in best:
            limits[catalog_id] = {**best, FIELD_SUBJECT_ID: subject_id}
    return limits


def measured_at_datetime(value: object):
    """The instant of a measurement, as a value the database can store.

    `timestamptz` holds an instant, so the value has to carry a zone. A measurement
    without one is a wall time and is stored as nothing rather than as a moment that
    means different things in different places.
    """
    return parse_measured_at(value)


def page_with_metadata(page: dict, record: dict) -> dict:
    """The YAML page with the measurement of `record` applied to it.

    The metadata is written right after the limit it belongs to: the limit and its
    evidence are one fact, and a file that puts them apart invites reading one
    without the other.
    """
    limit = record.get(FIELD_LIMIT)
    metadata = metadata_of(record)
    updated: dict = {}
    written = False

    for key, value in page.items():
        if key == FIELD_LIMIT:
            updated[key] = limit
            updated.update(metadata)
            written = True
            continue
        if key in (FIELD_MEASURED_AT, FIELD_BOUNDARY, FIELD_SOURCE, FIELD_SUBJECT_ID):
            # The old metadata is replaced, not kept: a subject id the new
            # measurement did not observe is no longer known.
            continue
        updated[key] = value

    if not written:
        updated[FIELD_LIMIT] = limit
        updated.update(metadata)
    return updated


def artifact_record(record: dict) -> dict:
    """One target of the artifact: what the gate compares and a reader needs."""
    reduced = {"id": str(record.get("id"))}
    for field in ARTIFACT_FIELDS:
        if field == "id":
            continue
        value = record.get(field)
        if value is None:
            continue
        reduced[field] = value
    return reduced


def artifact_records(limits: Dict[str, dict]) -> List[dict]:
    """The artifact of a measurement run: one reduced record per measured target.

    The targets are ordered by their numeric id, so that regenerating the artifact
    from the same report produces the same file — a diff of the artifact is then a
    diff of the measurements, not of the order they happened to be written in.
    """
    records = [artifact_record(record) for record in limits.values()]
    records.sort(key=_catalog_sort_key)
    return records


def _catalog_sort_key(record: dict) -> tuple:
    catalog_id = str(record.get("id") or "")
    return (0, int(catalog_id), "") if catalog_id.isdigit() else (1, 0, catalog_id)


def has_metadata(page: dict) -> bool:
    """Whether a YAML page carries measurement metadata at all."""
    return any(
        page.get(field) is not None
        for field in (FIELD_MEASURED_AT, FIELD_BOUNDARY, FIELD_SOURCE, FIELD_SUBJECT_ID)
    )


def compare_page(catalog_id: str, page: dict, record: dict) -> List[str]:
    """The findings about one target: what the YAML says and what was measured."""
    findings: List[str] = []

    yaml_limit = page.get(FIELD_LIMIT)
    measured_limit = record.get(FIELD_LIMIT)
    if yaml_limit != measured_limit:
        findings.append(
            f"catalog {catalog_id}: the YAML limit is {yaml_limit!r}, the measurement says "
            f"{measured_limit!r} — the number was edited by hand"
        )

    yaml_boundary = page.get(FIELD_BOUNDARY)
    measured_boundary = record.get("boundary")
    if yaml_boundary != measured_boundary:
        findings.append(
            f"catalog {catalog_id}: the YAML boundary is {yaml_boundary!r}, the measurement "
            f"ended on {measured_boundary!r}"
        )

    yaml_at = page.get(FIELD_MEASURED_AT)
    measured_at = record.get("measured_at")
    if not same_instant(yaml_at, measured_at):
        findings.append(
            f"catalog {catalog_id}: the YAML says the limit was measured at {yaml_at!r}, the "
            f"measurement says {measured_at!r}"
        )

    yaml_source = page.get(FIELD_SOURCE)
    if yaml_source != SOURCE_MEASUREMENT:
        findings.append(
            f"catalog {catalog_id}: the YAML source is {yaml_source!r}, a measured limit must "
            f"say where it came from ({SOURCE_MEASUREMENT!r})"
        )

    measured_subject = record.get(FIELD_SUBJECT_ID)
    if measured_subject is not None and page.get(FIELD_SUBJECT_ID) != measured_subject:
        findings.append(
            f"catalog {catalog_id}: the YAML subject id is {page.get(FIELD_SUBJECT_ID)!r}, the "
            f"measurement observed {measured_subject!r}"
        )

    return findings


def compare(pages: List[dict], artifact: dict) -> List[str]:
    """The findings about the whole catalog: the YAML against the measurement artifact.

    Three kinds of disagreement are a finding:

    - a measured target whose YAML limit, boundary or date differs from the
      measurement — this is the hand-edited number,
    - a measured target that is not in the YAML at all,
    - a YAML target that carries measurement metadata the artifact does not have —
      the metadata was written by hand, or the artifact is stale.
    """
    findings: List[str] = []
    records = {str(record.get("id")): record for record in artifact.get("catalogs") or []}

    if not records:
        findings.append(
            "the measurement artifact holds no record — it cannot confirm any limit. "
            "Regenerate it from a measurement report."
        )
        return findings

    pages_by_id = {str(page.get("id")): page for page in pages}

    for catalog_id in sorted(records, key=lambda cid: _catalog_sort_key({"id": cid})):
        page = pages_by_id.get(catalog_id)
        if page is None:
            findings.append(
                f"catalog {catalog_id} was measured but is not in the YAML — the target list "
                f"and the measurements disagree"
            )
            continue
        findings += compare_page(catalog_id, page, records[catalog_id])

    for catalog_id in sorted(pages_by_id, key=lambda cid: _catalog_sort_key({"id": cid})):
        if catalog_id in records:
            continue
        if has_metadata(pages_by_id[catalog_id]):
            findings.append(
                f"catalog {catalog_id} carries measurement metadata in the YAML but the "
                f"artifact has no measurement of it — the metadata was written by hand, or "
                f"the artifact is stale"
            )

    return findings

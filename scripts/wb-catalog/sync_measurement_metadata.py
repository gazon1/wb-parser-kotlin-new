#!/usr/bin/env python3
"""
sync_measurement_metadata.py — turns a measurement report into the metadata of a target.

Why this is a separate script
------------------------------
`known_pages_limit: 60` in `wb_catalog_targets.yaml` was a number with nothing behind
it: no date, no boundary, no source. A number like that cannot be checked. In a month
nobody can tell whether "60" is still true, and nobody can tell *which* of the two
boundaries produced it — a catalog that ended by itself, or one that the WB API cap
cut short. The second case means the crawl stores a slice of the category, and the
truncation counter fires honestly about it.

So the limit travels with its evidence. This script writes both:

- the YAML: `known_pages_limit` together with `known_pages_limit_measured_at`,
  `known_pages_limit_boundary`, `known_pages_limit_source` and `wb_subject_id`,
- the artifact: `catalog_measurements.json` at the repository root — the reduced form
  of the same measurements, and the file the gate compares the YAML against.

The policy: the maximum, never the last
----------------------------------------
**The limit that goes into the YAML is the MAXIMUM over the measurements, never the
last one.**

WB's pagination shifts between runs: the same catalog measured 59 and 60 available
pages on different passes, and the boundary moved with it. "The last measurement" is a
coin flip — the YAML would depend on which run happened to be written last, and a
re-measurement could silently shrink a catalog that had grown. The maximum is the one
number that is true of every measurement: it never under-reads a catalog that grew, and
it never asks WB for a page the API refuses (the limit is clamped to the API cap
anyway). The rule itself lives in `catalog_report.measured_limits`, so every consumer
of a report inherits it.

Several reports can be given at once (`--report` repeats): they are folded into one
set of measurements before the maximum is taken, which is what "over the measurements"
means when the measurements came from different passes.

Usage:
    python3 scripts/wb-catalog/sync_measurement_metadata.py --report /tmp/catalog_lengths.json --dry-run
    python3 scripts/wb-catalog/sync_measurement_metadata.py --report /tmp/catalog_lengths.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Dict, List

import click
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))

from catalog_measurements import (  # noqa: E402
    ARTIFACT_VERSION,
    artifact_records,
    best_measurements,
    page_with_metadata,
)

ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_CATALOG_YAML = ROOT / "wb_catalog_targets.yaml"
DEFAULT_ARTIFACT = ROOT / "catalog_measurements.json"


def load_report(path: Path) -> dict:
    """Read a measurement report."""
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        raise click.ClickException(f"Cannot read the report {path}: {e}") from e


def load_pages(yaml_path: Path) -> List[dict]:
    """Read the `pages` list of the catalog YAML."""
    try:
        with open(yaml_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
    except (OSError, yaml.YAMLError) as e:
        raise click.ClickException(f"Cannot parse {yaml_path}: {e}") from e

    pages = data.get("pages") if isinstance(data, dict) else None
    if not isinstance(pages, list):
        raise click.ClickException(f"{yaml_path} has no 'pages' list")
    return pages


def write_pages(yaml_path: Path, pages: List[dict]) -> None:
    """Write the catalog YAML back, keeping the field order of the original."""
    with open(yaml_path, "w", encoding="utf-8") as f:
        yaml.dump({"pages": pages}, f, allow_unicode=True, sort_keys=False)


def write_artifact(path: Path, limits: Dict[str, dict], sources: List[str]) -> None:
    """Write the measurement artifact — the reduced form of the measurements.

    The artifact is what the repository keeps of a measurement run: small enough to
    read, and exactly what the gate compares the YAML against. It is written by this
    command and never edited by hand.
    """
    artifact = {
        "version": ARTIFACT_VERSION,
        "policy": "maximum-over-measurements",
        "source_reports": sources,
        "catalogs": artifact_records(limits),
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(artifact, f, ensure_ascii=False, indent=2)
        f.write("\n")


@click.command(context_settings=dict(help_option_names=["-h", "--help"]))
@click.option(
    "--report",
    "report_paths",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    required=True,
    multiple=True,
    help=(
        "JSON report written by measure_catalog_length.py. Repeat for several passes: "
        "the limit is the MAXIMUM over all of them, never the last one."
    ),
)
@click.option(
    "--yaml",
    "yaml_path",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=DEFAULT_CATALOG_YAML,
    show_default=True,
    help="Catalog YAML to write",
)
@click.option(
    "--artifact",
    "artifact_path",
    type=click.Path(dir_okay=False, path_type=Path),
    default=DEFAULT_ARTIFACT,
    show_default=True,
    help="Measurement artifact to write",
)
@click.option("--dry-run", is_flag=True, help="Print the changes without writing them")
def sync_measurement_metadata(
    report_paths: List[Path],
    yaml_path: Path,
    artifact_path: Path,
    dry_run: bool,
) -> None:
    """Write the measurement metadata of every measured target into the YAML."""
    merged: dict = {"catalogs": []}
    for report_path in report_paths:
        report = load_report(report_path)
        merged["catalogs"] = list(merged["catalogs"]) + list(report.get("catalogs") or [])

    limits = best_measurements(merged.get("catalogs") or [])
    if not limits:
        raise click.ClickException(
            "the reports hold no measured catalog. Nothing to write — a number that was "
            "not measured must not reach the YAML."
        )

    pages = load_pages(yaml_path)
    updated: List[dict] = []
    changed = 0
    for page in pages:
        record = limits.get(str(page.get("id")))
        if record is None:
            updated.append(page)
            continue
        new_page = page_with_metadata(page, record)
        if new_page != page:
            changed += 1
        updated.append(new_page)

    missing = [cid for cid in limits if cid not in {str(p.get("id")) for p in pages}]
    unmeasured = [str(p.get("id")) for p in pages if str(p.get("id")) not in limits]

    click.echo(f"Measured catalogs in the reports: {len(limits)}")
    click.echo(f"Targets whose metadata changes: {changed}")
    if missing:
        click.echo("")
        click.echo(f"Measured but not in the YAML: {', '.join(sorted(missing))}")
    if unmeasured:
        click.echo("")
        click.echo(
            f"Not measured, left as it was: {len(unmeasured)} target(s) "
            f"({', '.join(unmeasured[:10])}{'…' if len(unmeasured) > 10 else ''})"
        )

    if dry_run:
        click.echo("")
        click.echo("DRY-RUN: nothing written")
        return

    write_pages(yaml_path, updated)
    write_artifact(artifact_path, limits, [path.name for path in report_paths])
    click.echo("")
    click.echo(f"Wrote {yaml_path}")
    click.echo(f"Wrote {artifact_path}")


if __name__ == "__main__":
    sync_measurement_metadata()

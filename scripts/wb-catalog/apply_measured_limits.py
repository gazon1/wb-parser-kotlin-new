#!/usr/bin/env python3
"""
apply_measured_limits.py — writes the measured catalog lengths into the YAML.

Why this is a separate script
-----------------------------
The measurement and the writing are two different acts, and conflating them is how
the catalog ended up with `known_pages_limit: 5` on every target: a number that
nobody measured, written by the same hand that guessed it.

So the measurement writes a report and this script turns that report into numbers in
`wb_catalog_targets.yaml`. The report is the evidence; the YAML is the decision based
on it. A target that was not measured keeps the number it had — and this script says
so out loud instead of quietly leaving it.

What it writes
--------------
Only for catalogs the report measured. The value is the one the measurement derived,
with the boundary it ended on recorded next to it:

| boundary         | what it means                                   | limit          |
|------------------|-------------------------------------------------|----------------|
| `catalog_end`    | the catalog ended by itself                     | pages + 1      |
| `api_cap`        | WB refused to serve further pages               | the last page  |

Usage:
    python3 scripts/wb-catalog/apply_measured_limits.py --report /tmp/lengths.json --dry-run
    python3 scripts/wb-catalog/apply_measured_limits.py --report /tmp/lengths.json
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import List

import click
import yaml

from catalog_report import (  # noqa: E402
    STATUS_MEASURED,
    Change,
    measured_limits,
    plan_changes,
)

DEFAULT_CATALOG_YAML = Path(__file__).resolve().parent.parent.parent / "wb_catalog_targets.yaml"


def load_report(path: Path) -> dict:
    """Read the measurement report."""
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


@click.command(context_settings=dict(help_option_names=["-h", "--help"]))
@click.option(
    "--report",
    "report_path",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    required=True,
    help="JSON report written by measure_catalog_length.py",
)
@click.option(
    "--yaml",
    "yaml_path",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=DEFAULT_CATALOG_YAML,
    show_default=True,
    help="Catalog YAML to write",
)
@click.option("--dry-run", is_flag=True, help="Print the changes without writing them")
def apply_measured_limits(report_path: Path, yaml_path: Path, dry_run: bool) -> None:
    """Write the measured catalog lengths into the YAML."""
    report = load_report(report_path)
    limits = measured_limits(report)
    if not limits:
        raise click.ClickException(
            f"{report_path} holds no measured catalog. Nothing to apply — a number "
            "that was not measured must not reach the YAML."
        )

    pages = load_pages(yaml_path)
    changes, missing = plan_changes(pages, limits)

    click.echo(f"Measured catalogs in the report: {len(limits)}")
    click.echo(f"Changes to {yaml_path}: {len(changes)}")
    for change in changes:
        click.echo(str(change))

    if missing:
        click.echo("")
        click.echo(f"Measured but not in the YAML: {', '.join(sorted(missing))}")

    unmeasured = [str(p.get("id")) for p in pages if str(p.get("id")) not in limits]
    if unmeasured:
        click.echo("")
        click.echo(
            f"Not measured, limit left as it was: {len(unmeasured)} target(s) "
            f"({', '.join(unmeasured[:10])}{'…' if len(unmeasured) > 10 else ''})"
        )

    if dry_run:
        click.echo("")
        click.echo("DRY-RUN: file not written")
        return

    if changes:
        by_id = {c.catalog_id: c for c in changes}
        for page in pages:
            change = by_id.get(str(page.get("id")))
            if change:
                page["known_pages_limit"] = change.new
        write_pages(yaml_path, pages)
        click.echo("")
        click.echo(f"Wrote {yaml_path}")
    else:
        click.echo("")
        click.echo("Nothing to write — every measured limit already matches the YAML.")


if __name__ == "__main__":
    apply_measured_limits()

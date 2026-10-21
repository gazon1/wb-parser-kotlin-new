#!/usr/bin/env python3
"""
check-egress-block.py — a measurement block's report must be its data.

Why this gate exists
--------------------
The report of a measurement block is what a decision rests on: the ADR that says
"the measurement does not authorize a second address" quotes the refusal shares
from it. And a report is prose — a number in a table can be edited, a claim can
be written as "yes" without anything behind it, and a verdict can be written
"the gate passed" for a block that did not.

The first block is the example: it was run by hand, and the rules it broke were
found afterwards, by reading the report. Nobody could have known at the time,
because nothing checked the report against the data it claims to summarise. The
block says so honestly — the gate accepts an honest "no" — but there was no
mechanism that would have *caught* a dishonest "yes".

This gate reads the raw JSON of every committed block and holds the report to it:

  • the numbers in the report's run table must be the numbers in the raw data —
    summed over the repeats, so a block of three repeats shows three runs'
    worth of requests;
  • a rule the report claims to have met ("да") must be met by the data, for
    every rule this gate can decide from the data;
  • the verdict must agree with the rules.

What it does NOT do
-------------------
It does not judge whether the measurement is *true* — that needs a live WB and a
day. It does not re-run the protocol. And it says "cannot be decided from the
data" instead of "yes" for the rules the raw data does not carry (the catalog
of a run, whether a profile was fresh): a rule that cannot be checked is not a
rule that was met.

It runs on a bare Python: the CI `static` job installs no packages.

Usage:
    python3 scripts/check-egress-block.py
    python3 scripts/check-egress-block.py --self-test
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MEASUREMENTS = ROOT / "docs" / "operations" / "egress-measurements"

# The rules this gate can decide from the data, and how to recognise them in the
# report's rules table. A rule that is not listed here is reported as "not
# decided by this gate" rather than silently passed.
RULE_KEYS = {
    "volume": ("объём", "объем"),
    "repeats": ("повтор",),
    "order": ("порядок",),
    "pause": ("пауза",),
    "window": ("окно суток",),
    "class": ("класс",),
    "separation": ("антибот", "транспорт"),
}

# The verdict of a block, as the runner writes it. The markers are exact, because
# "Блок НЕ прошёл гейт протокола" contains the words of "прошёл гейт протокола",
# and a verdict read by a substring would read a failure as a pass.
PASSED_VERDICTS = ("Блок прошёл гейт протокола", "Блок прошел гейт протокола")
FAILED_VERDICTS = ("Блок НЕ прошёл гейт протокола", "Блок НЕ прошел гейт протокола")


def strip_markup(text: str) -> str:
    """The cell of a markdown table, without the emphasis."""
    return text.replace("**", "").replace("*", "").strip()


def parse_tables(text: str) -> list[list[list[str]]]:
    """Every markdown table of the text, as a list of rows of cells."""
    tables: list[list[list[str]]] = []
    current: list[list[str]] = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("|") and stripped.endswith("|"):
            cells = [cell.strip() for cell in stripped.strip("|").split("|")]
            if all(set(cell) <= set("-: ") for cell in cells):
                continue  # the separator row
            current.append(cells)
            continue
        if current:
            tables.append(current)
            current = []
    if current:
        tables.append(current)
    return tables


def run_rows(text: str) -> list[dict]:
    """The report's per-run table: label, profile, address, requests, refused..."""
    rows = []
    for table in parse_tables(text):
        header = table[0]
        if len(header) < 9 or header[0] != "Прогон":
            continue
        for cells in table[1:]:
            if len(cells) < len(header):
                continue
            row = dict(zip(header, cells))
            rows.append(row)
        break
    return rows


def rule_rows(text: str) -> list[dict]:
    """The report's protocol rules table: rule, verdict, comment."""
    rows = []
    for table in parse_tables(text):
        header = table[0]
        if len(header) < 2 or header[0] != "Правило":
            continue
        for cells in table[1:]:
            if len(cells) < 2:
                continue
            rows.append({"rule": cells[0], "verdict": strip_markup(cells[1]), "comment": cells[2] if len(cells) > 2 else ""})
        break
    return rows


def classify(rule: str) -> str | None:
    """Which of the gate's rules this line of the report is about."""
    lowered = rule.lower()
    for key, needles in RULE_KEYS.items():
        if any(needle in lowered for needle in needles):
            return key
    return None


def number(text: str) -> float | None:
    """A number out of a cell, whatever it is written with."""
    cleaned = strip_markup(text).replace("%", "").replace(",", ".").strip()
    try:
        return float(cleaned)
    except ValueError:
        return None


def load_records(raw_dir: Path) -> tuple[list[dict], dict | None]:
    """The raw runs of a block, and the block's own facts if it has them."""
    records = []
    meta = None
    if not raw_dir.is_dir():
        return records, meta
    for path in sorted(raw_dir.glob("*.json")):
        try:
            with open(path, "r", encoding="utf-8") as handle:
                payload = json.load(handle)
        except (OSError, json.JSONDecodeError):
            continue
        if path.name == "block.json":
            meta = payload
        elif isinstance(payload, dict):
            records.append(payload)
    return records, meta


def aggregate(records: list[dict]) -> dict[str, dict]:
    """The sums over the repeats, per label."""
    totals: dict[str, dict] = {}
    for record in records:
        label = record.get("label", "?")
        row = totals.setdefault(
            label,
            {"requests": 0, "refused": 0, "errored": 0, "succeeded": 0, "runs": 0},
        )
        row["requests"] += int(record.get("requests", 0))
        row["refused"] += int(record.get("refused", 0))
        row["errored"] += int(record.get("errored", 0))
        row["succeeded"] += int(record.get("succeeded", 0))
        row["runs"] += 1
    return totals


def decidable_from_data(records: list[dict], meta: dict | None) -> dict[str, bool]:
    """The rules this gate can decide from the data, and its verdict on each."""
    verdicts: dict[str, bool] = {}
    totals = aggregate(records)

    if records:
        volumes = {row["requests"] for row in totals.values()}
        verdicts["volume"] = len(volumes) == 1
    if totals:
        verdicts["repeats"] = min(row["runs"] for row in totals.values()) >= 3
    else:
        verdicts["repeats"] = False

    if meta:
        orders = meta.get("orders") or []
        canonical = [run["label"] for run in (meta.get("plan") or {}).get("runs", [])]
        verdicts["order"] = bool(orders) and any(order != canonical for order in orders)

        gaps = meta.get("pause_minutes")
        verdicts["pause"] = bool(gaps) and 30 <= gaps <= 60

        days = {record.get("started_at", "")[:10] for record in records if record.get("started_at")}
        verdicts["window"] = len(days) == 1

        combat = (meta.get("plan") or {}).get("combat_class")
        classes = {record.get("proxy_class") for record in records if record.get("proxy_class")}
        verdicts["class"] = bool(combat) and classes == {combat}

    verdicts["separation"] = bool(records) and all(
        "refused" in record and "errored" in record and "succeeded" in record for record in records
    )
    return verdicts


def check_block(report: Path) -> tuple[list[str], list[str]]:
    """The findings about one block, and the notes about what could not be decided."""
    findings: list[str] = []
    notes: list[str] = []

    text = report.read_text(encoding="utf-8")
    raw_dir = report.parent / "raw" / report.stem
    records, meta = load_records(raw_dir)

    if not records:
        notes.append(f"{report.name}: no raw data — nothing to check")
        return findings, notes

    # 1. Every raw run must be consistent with itself.
    for record in records:
        label = record.get("label", "?")
        requests = int(record.get("requests", 0))
        refused = int(record.get("refused", 0))
        errored = int(record.get("errored", 0))
        succeeded = int(record.get("succeeded", 0))
        if requests != refused + errored + succeeded:
            findings.append(
                f"{report.name}: run {label} is inconsistent — {requests} requests, "
                f"{refused} refused + {errored} errored + {succeeded} succeeded"
            )
        ratio = record.get("refusal_ratio")
        if requests and ratio is not None and abs(float(ratio) - refused / requests) > 0.005:
            findings.append(
                f"{report.name}: run {label} says refusal_ratio {ratio}, "
                f"but {refused} of {requests} is {refused / requests:.4f}"
            )

    # 2. The report's numbers must be the raw numbers.
    totals = aggregate(records)
    reported = run_rows(text)
    if not reported:
        notes.append(f"{report.name}: the report has no per-run table — nothing to compare")
    for row in reported:
        label = row.get("Прогон", "?").strip()
        if label not in totals:
            findings.append(f"{report.name}: the report lists the run {label}, which is not in the raw data")
            continue
        raw = totals[label]
        for cell, key in (("запросов", "requests"), ("отказов", "refused"), ("транспортных", "errored"), ("успешных", "succeeded")):
            if cell not in row:
                continue
            stated = number(row[cell])
            if stated is None:
                findings.append(f"{report.name}: run {label} has an unreadable {cell}: {row[cell]!r}")
            elif int(stated) != raw[key]:
                findings.append(
                    f"{report.name}: run {label} says {int(stated)} {cell}, the raw data says {raw[key]}"
                )
        share = number(row.get("доля", ""))
        if share is not None:
            expected = (raw["refused"] / raw["requests"]) if raw["requests"] else 0.0
            if abs(share / 100.0 - expected) > 0.005:
                findings.append(
                    f"{report.name}: run {label} says a refusal share of {share}%, "
                    f"the raw data says {expected * 100:.1f}%"
                )

    # 3. A claim of "yes" must be backed by the data.
    verdicts = decidable_from_data(records, meta)
    rules = rule_rows(text)
    if not rules:
        notes.append(f"{report.name}: the report has no rules table — the protocol was not checked")
    for row in rules:
        key = classify(row["rule"])
        if key is None:
            notes.append(f"{report.name}: the rule '{row['rule']}' is not decided by this gate")
            continue
        if key not in verdicts:
            notes.append(
                f"{report.name}: the rule '{row['rule']}' cannot be decided from the data — "
                "the raw runs do not carry what it needs"
            )
            continue
        if row["verdict"] == "да" and not verdicts[key]:
            findings.append(
                f"{report.name}: the report claims the rule '{row['rule']}' was met, "
                "and the raw data does not support it"
            )

    # 4. The verdict must agree with the rules.
    failed_claims = [row for row in rules if row["verdict"] == "нет"]
    claims_passed = any(marker in text for marker in PASSED_VERDICTS) and not any(
        marker in text for marker in FAILED_VERDICTS
    )
    claims_failed = any(marker in text for marker in FAILED_VERDICTS)
    if claims_passed and failed_claims:
        findings.append(
            f"{report.name}: the report claims the block passed the protocol gate, "
            f"and lists {len(failed_claims)} rule(s) as not met"
        )
    if claims_failed and not failed_claims and not any(
        key in verdicts and not verdicts[key] for key in verdicts
    ):
        findings.append(
            f"{report.name}: the report claims the block failed the protocol gate, "
            "but nothing in it says a rule was broken"
        )

    return findings, notes


def check_all(measurements: Path) -> tuple[list[str], list[str]]:
    findings: list[str] = []
    notes: list[str] = []
    if not measurements.is_dir():
        return findings, [f"{measurements} does not exist — nothing to check"]
    for report in sorted(measurements.glob("*.md")):
        block_findings, block_notes = check_block(report)
        findings += block_findings
        notes += block_notes
    return findings, notes


def self_test(measurements: Path) -> int:
    """Prove the gate can fail: a tampered report must be caught."""
    if not measurements.is_dir() or not list(measurements.glob("*.md")):
        print("self-test: no committed block to work with — nothing to prove", file=sys.stderr)
        return 1

    source = sorted(measurements.glob("*.md"))[0]
    findings, _ = check_block(source)
    if findings:
        print(f"self-test: the committed block {source.name} already fails: {findings}", file=sys.stderr)
        return 1

    with tempfile.TemporaryDirectory() as work:
        copy = Path(work) / "egress-measurements"
        shutil.copytree(measurements, copy)
        target = sorted(copy.glob("*.md"))[0]
        text = target.read_text(encoding="utf-8")
        # Change one number in the run table: the raw data keeps the truth.
        lines = text.splitlines()
        tampered_a_number = False
        for index, line in enumerate(lines):
            stripped = line.strip()
            if not stripped.startswith("|") or "Прогон" in stripped or set(stripped) <= set("|- :"):
                continue
            cells = line.split("|")
            # The cell of the refusal count: a number the raw data also knows.
            for column in (6, 5, 9):
                if len(cells) > column and cells[column].strip().isdigit():
                    cells[column] = " 999 "
                    lines[index] = "|".join(cells)
                    tampered_a_number = True
                    break
            if tampered_a_number:
                break
        if not tampered_a_number:
            print("self-test: no number in the run table to tamper with", file=sys.stderr)
            return 1
        target.write_text("\n".join(lines), encoding="utf-8")
        tampered, _ = check_block(target)
        if not tampered:
            print("self-test: a tampered report passed — the gate cannot fail", file=sys.stderr)
            return 1
        print(f"✅ self-test: the gate catches a tampered report ({tampered[0]})")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="prove the gate can fail")
    parser.add_argument("--measurements", default=str(MEASUREMENTS), help="where the blocks live")
    args = parser.parse_args()

    measurements = Path(args.measurements)

    if args.self_test:
        return self_test(measurements)

    findings, notes = check_all(measurements)
    for note in notes:
        print(f"  · {note}")
    if findings:
        print("")
        for finding in findings:
            print(f"❌ {finding}")
        print(f"\n❌ {len(findings)} finding(s) in the measurement blocks")
        return 1
    print("✅ every measurement block's report agrees with its raw data")
    return 0


if __name__ == "__main__":
    sys.exit(main())

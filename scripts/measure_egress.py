#!/usr/bin/env python3
"""
measure_egress.py — runs a measurement block by the protocol.

Why this exists
---------------
The protocol (`docs/operations/egress-measurement-protocol.md`) is a list of rules:
a fixed volume, a 30–60 minute pause between the runs on one address, a randomised
order, at least three repeats per run, one day window, the same catalog, the proxy
of the same class as the combat one, a fresh profile per new address, and the
anti-bot refusals counted apart from the transport failures.

The first block was run by hand, and the rules it broke were found afterwards, by
reading the report: one block instead of three, a fixed order, a datacenter proxy
instead of the combat class. The rules were wishes. A rule that lives only in a
document is checked by whoever remembers it.

This script makes them mechanical:

  • the order is shuffled with a seed that is recorded, so the order is
    reproducible and not a choice made in the moment;
  • the pause between two runs on one address is enforced by the clock, not by the
    operator's memory;
  • every run records when it started, which address it was on, and which class
    that address is — so the pause and the class can be checked afterwards, from
    the data, not from the report;
  • the report is written FROM the data, so the numbers in it cannot be edited by
    hand without contradicting the raw JSON next to it.

And it says whether the block passed the gate. A block that did not pass says so.
It does not become a pass by being written up nicely.

Usage:
    uv run python scripts/measure_egress.py --plan /tmp/block.json
    uv run python scripts/measure_egress.py --plan /tmp/block.json --dry-run
    uv run python scripts/measure_egress.py --plan /tmp/block.json --pause-minutes 0
"""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INSTRUMENT = ROOT / "scripts" / "wb_browser_fetch.py"
DEFAULT_OUT = ROOT / "docs" / "operations" / "egress-measurements"

# The protocol's rules, as the script enforces them. The numbers here are the
# protocol's, not the script's: `docs/operations/egress-measurement-protocol.md`
# §1.1 is the authority, and this file is its executable form.
MIN_REPEATS = 3
MIN_PAUSE_MINUTES = 30
MAX_PAUSE_MINUTES = 60


def now() -> datetime:
    return datetime.now(timezone.utc)


def iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(text: str) -> datetime:
    return datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


class PlanError(Exception):
    """The plan does not describe a block that can be run."""


def load_plan(path: Path) -> dict:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            plan = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        raise PlanError(f"cannot read the plan {path}: {error}") from error
    if not isinstance(plan, dict):
        raise PlanError("the plan must be a JSON object")
    return plan


def validate(plan: dict) -> None:
    """The plan has to say enough for the block to mean anything."""
    if not plan.get("block"):
        raise PlanError("the plan has no 'block' name")
    runs = plan.get("runs")
    if not isinstance(runs, list) or not runs:
        raise PlanError("the plan has no runs")
    for run in runs:
        if not run.get("label"):
            raise PlanError("a run has no 'label'")
        for part in parts_of(run):
            for field in ("profile", "address"):
                if not part.get(field):
                    raise PlanError(f"the run {run['label']} has a part without '{field}'")
        mode = run.get("mode", "sequential")
        if mode not in ("sequential", "concurrent"):
            raise PlanError(
                f"the run {run['label']} has the mode '{mode}' — the modes are "
                "'sequential' and 'concurrent'"
            )
    labels = [run["label"] for run in runs]
    if len(set(labels)) != len(labels):
        raise PlanError(f"the plan repeats a label: {labels}")
    if not plan.get("count"):
        raise PlanError("the plan has no 'count' — the volume is the denominator of the refusal share")


def parts_of(run: dict) -> list[dict]:
    """The parts of a run: one for a single-profile run, several for a paired one.

    A run may be several profiles. The protocol needs both kinds:

    - **A5** — two profiles on DIFFERENT addresses, *not* simultaneously: the
      point is two addresses at the production rate, not one address at twice the
      rate;
    - **A6** — two profiles on the SAME address, *simultaneously*: the point is
      twice the rate on one address.

    A single-profile run is a run of one part, and the two forms are the same
    thing to the runner.
    """
    if run.get("parts"):
        return list(run["parts"])
    return [{key: value for key, value in run.items() if key not in ("label", "mode", "parts")}]


def addresses_of(run: dict) -> list[str]:
    """The addresses a run touches."""
    return list(dict.fromkeys(part["address"] for part in parts_of(run)))


def order_for(runs: list[dict], seed: int) -> list[dict]:
    """The order of one repeat: shuffled, and reproducible from the seed."""
    ordered = list(runs)
    random.Random(seed).shuffle(ordered)
    return ordered


def instrument_argv(part: dict, plan: dict, label: str) -> list[str]:
    """The command of one measurement run."""
    argv = [
        sys.executable,
        str(INSTRUMENT),
        "--profile",
        part["profile"],
        "--catalog",
        str(plan["catalog"]),
        "--count",
        str(plan["count"]),
        "--delay",
        str(part.get("delay", 3.0)),
        "--label",
        label,
        "--json",
    ]
    if plan.get("query"):
        argv += ["--query", plan["query"]]
    return argv


def summarise(
    part: dict,
    plan: dict,
    label: str,
    stdout: str,
    stderr: str,
    returncode: int,
    started: datetime,
    finished: datetime,
) -> dict:
    """The record of one measurement run: the instrument's numbers and the block's facts."""
    summary: dict = {}
    if stdout.strip():
        try:
            summary = json.loads(stdout)
        except json.JSONDecodeError:
            summary = {}
    if not summary:
        # The instrument did not answer at all. That is not a request that failed —
        # no request was made — so it must not be counted as a transport failure:
        # the counts have to stay consistent (requests = refused + errored +
        # succeeded), or the gate will read the run as broken when it is empty.
        summary = {
            "label": label,
            "requests": 0,
            "refused": 0,
            "errored": 0,
            "succeeded": 0,
            "refusal_ratio": 0.0,
            "statuses": [],
            "error": (stderr or stdout or "the instrument said nothing").strip()[:500],
        }
    summary["label"] = label
    summary["profile"] = part["profile"]
    summary["address"] = part["address"]
    summary["proxy_class"] = part.get("proxy_class", "unknown")
    summary["delay"] = part.get("delay", 3.0)
    summary["started_at"] = iso(started)
    summary["finished_at"] = iso(finished)
    summary["exit_code"] = returncode
    return summary


def run_instrument(part: dict, plan: dict, label: str) -> dict:
    """One measurement run through the instrument, with the block's metadata."""
    started = now()
    completed = subprocess.run(instrument_argv(part, plan, label), capture_output=True, text=True)
    finished = now()
    return summarise(part, plan, label, completed.stdout, completed.stderr, completed.returncode, started, finished)


def run_instances(records: list[dict]) -> list[dict]:
    """The runs of a block: one per (label, repeat), with the addresses it touched.

    A run may be several parts — A5 is two profiles on two addresses, A6 is two
    profiles on one address at the same time. The pause is between RUNS, so the
    parts of one run are one run: the gap between the two parts of A6 is zero by
    construction, and it is not a pause that was skipped.
    """
    grouped: dict[tuple[str, int], dict] = {}
    for record in records:
        key = (record.get("label", "?"), int(record.get("repeat", 0)))
        instance = grouped.setdefault(
            key,
            {"label": key[0], "repeat": key[1], "addresses": [], "started_at": record["started_at"]},
        )
        if record["address"] not in instance["addresses"]:
            instance["addresses"].append(record["address"])
        if record["started_at"] < instance["started_at"]:
            instance["started_at"] = record["started_at"]
    return sorted(grouped.values(), key=lambda instance: instance["started_at"])


def pauses_of(instances: list[dict]) -> list[dict]:
    """The gaps between the runs on one address, in minutes."""
    last_on_address: dict[str, datetime] = {}
    gaps = []
    for instance in instances:
        started = parse_iso(instance["started_at"])
        for address in instance["addresses"]:
            previous = last_on_address.get(address)
            if previous is not None:
                gaps.append(
                    {
                        "address": address,
                        "label": instance["label"],
                        "minutes": round((started - previous).total_seconds() / 60.0, 1),
                    }
                )
            last_on_address[address] = started
    return gaps


def evaluate(records: list[dict], plan: dict, orders: list[list[str]]) -> list[dict]:
    """The protocol's rules, checked against what was actually done."""
    findings: list[dict] = []

    instances = run_instances(records)
    labels = sorted({instance["label"] for instance in instances})
    repeats = (
        min(sum(1 for instance in instances if instance["label"] == label) for label in labels)
        if labels
        else 0
    )
    findings.append(
        {
            "rule": "не менее трёх повторов на прогон",
            "ok": repeats >= MIN_REPEATS,
            "detail": f"{repeats} повтор(а) на прогон",
        }
    )

    canonical = [run["label"] for run in plan["runs"]]
    randomised = any(order != canonical for order in orders)
    findings.append(
        {
            "rule": "рандомизированный порядок прогонов в блоке",
            "ok": randomised,
            "detail": "; ".join(" → ".join(order) for order in orders),
        }
    )

    # The volume is per PART: a paired run makes `count` requests per profile, and
    # the run's own total is twice that. The protocol's rule is about one run's
    # volume, and one profile's volume is what the instrument's `--count` fixes.
    volumes = sorted({record.get("requests", 0) for record in records})
    findings.append(
        {
            "rule": "фиксированный объём запросов на прогон",
            "ok": len(volumes) == 1 and volumes[0] == plan["count"],
            "detail": f"запрошено {plan['count']} на профиль; прогоны видели {volumes}",
        }
    )

    gaps = pauses_of(instances)
    bad = [gap for gap in gaps if not (MIN_PAUSE_MINUTES <= gap["minutes"] <= MAX_PAUSE_MINUTES)]
    findings.append(
        {
            "rule": "пауза 30–60 мин между прогонами на одном адресе",
            "ok": bool(gaps) and not bad,
            "detail": (
                f"{len(gaps)} разрыв(ов) на одном адресе, "
                + ", ".join(f"{gap['minutes']} мин" for gap in gaps)
                if gaps
                else "два прогона не делили адрес — правилу нечего было мерить"
            ),
        }
    )

    days = sorted({record["started_at"][:10] for record in records})
    findings.append(
        {
            "rule": "одно и то же окно суток",
            "ok": len(days) == 1,
            "detail": ", ".join(days),
        }
    )

    classes = sorted({record.get("proxy_class", "unknown") for record in records})
    combat = plan.get("combat_class")
    findings.append(
        {
            "rule": "прокси того же класса, что и боевой",
            "ok": bool(combat) and classes == [combat],
            "detail": f"боевой класс — {combat or 'не указан'}; прогоны использовали {classes}",
        }
    )

    separated = all(
        "refused" in record and "errored" in record and "succeeded" in record
        for record in records
    )
    findings.append(
        {
            "rule": "антибот и транспортный сбой считаются раздельно",
            "ok": separated,
            "detail": "каждый прогон пишет refused, errored и succeeded раздельно",
        }
    )

    return findings


def aggregate(records: list[dict]) -> list[dict]:
    """One row per label: the numbers the report shows."""
    rows = []
    for label in sorted({record["label"] for record in records}):
        of_label = [record for record in records if record["label"] == label]
        requests = sum(record.get("requests", 0) for record in of_label)
        refused = sum(record.get("refused", 0) for record in of_label)
        errored = sum(record.get("errored", 0) for record in of_label)
        succeeded = sum(record.get("succeeded", 0) for record in of_label)
        rows.append(
            {
                "label": label,
                "profiles": ", ".join(
                    dict.fromkeys(record.get("profile", "?") for record in of_label)
                ),
                "addresses": ", ".join(
                    dict.fromkeys(record.get("address", "?") for record in of_label)
                ),
                "proxy_class": ", ".join(
                    sorted({record.get("proxy_class", "unknown") for record in of_label})
                ),
                "delay": ", ".join(
                    str(delay)
                    for delay in dict.fromkeys(record.get("delay", 0) for record in of_label)
                ),
                "runs": len(of_label),
                "requests": requests,
                "refused": refused,
                "errored": errored,
                "succeeded": succeeded,
                "refusal_ratio": (refused / requests) if requests else 0.0,
            }
        )
    return rows


def write_report(
    path: Path,
    plan: dict,
    records: list[dict],
    rows: list[dict],
    findings: list[dict],
    orders: list[list[str]],
    seed: int,
    pause_minutes: float,
) -> None:
    """The report, written from the data."""
    passed = all(finding["ok"] for finding in findings)
    lines = [
        f"# Блок замера {plan['block']} — результаты",
        "",
        "Блок протокола `docs/operations/egress-measurement-protocol.md`, выполненный",
        "скриптом `scripts/measure_egress.py`. Отчёт написан из сырых данных: числа в нём",
        "нельзя поправить рукой, не разойдясь с JSON рядом.",
        "",
        "## Условия блока",
        "",
        "| Параметр | Значение |",
        "|---|---|",
        f"| Окно | {min(r['started_at'] for r in records)} — {max(r['finished_at'] for r in records)} |"
        if records
        else "| Окно | пусто |",
        f"| Каталог | {plan.get('catalog', '?')} |",
        f"| Объём | ровно {plan['count']} запросов на прогон |",
        f"| Повторов | {plan.get('repeats', MIN_REPEATS)} |",
        f"| Пауза между прогонами на одном адресе | {pause_minutes} мин |",
        f"| Порядок | рандомизирован, seed `{seed}` |",
        f"| Боевой класс прокси | {plan.get('combat_class', 'не указан')} |",
        "",
        "## Прогоны",
        "",
        "| Прогон | Профили | Адреса | Класс | delay | частей | запросов | отказов | доля | транспортных | успешных |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        lines.append(
            "| {label} | {profiles} | {addresses} | {proxy_class} | {delay} | {runs} | {requests} | "
            "{refused} | {share:.1%} | {errored} | {succeeded} |".format(
                share=row["refusal_ratio"], **row
            )
        )
    lines += [
        "",
    ]
    failed = [record for record in records if record.get("error")]
    if failed:
        lines += [
            "> ⚠️ Инструмент не ответил в прогонах: "
            + ", ".join(sorted({record["label"] for record in failed}))
            + ". Эти прогоны не дали данных — их нули в таблице выше означают «ничего не"
            " измерено», а не «отказов не было».",
            "",
        ]
    lines += [
        "## Проверка правил протокола",
        "",
        "| Правило | Выполнено | Комментарий |",
        "|---|---|---|",
    ]
    for finding in findings:
        lines.append(
            f"| {finding['rule']} | {'да' if finding['ok'] else '**нет**'} | {finding['detail']} |"
        )
    lines += [
        "",
        "## Вердикт",
        "",
        (
            "**Блок прошёл гейт протокола.**"
            if passed
            else "**Блок НЕ прошёл гейт протокола.** Он не авторизует адрес: решение принимается "
            "по `docs/decisions/2026-10-27-the-measurement-does-not-authorize-a-second-address.md`."
        ),
        "",
        "Порядок прогонов по повторам:",
        "",
    ]
    for index, order in enumerate(orders, start=1):
        lines.append(f"{index}. {' → '.join(order)}")
    lines += [
        "",
        "## Сырые данные",
        "",
        f"`raw/{plan['block']}/` — по одному JSON на прогон, с временем старта, адресом и",
        "классом; плюс `block.json` — план, seed, порядок и вердикт блока.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", required=True, help="the block plan (JSON)")
    parser.add_argument("--out", default=str(DEFAULT_OUT), help="where the block is written")
    parser.add_argument("--seed", type=int, default=None, help="the seed of the shuffle")
    parser.add_argument(
        "--pause-minutes",
        type=float,
        default=float(MIN_PAUSE_MINUTES),
        help="the pause between the runs on one address (the protocol says 30–60)",
    )
    parser.add_argument("--repeats", type=int, default=None, help="how many times to repeat the runs")
    parser.add_argument("--block", default=None, help="the block's name, if not the plan's")
    parser.add_argument("--dry-run", action="store_true", help="print the plan and run nothing")
    args = parser.parse_args()

    try:
        plan = load_plan(Path(args.plan))
        validate(plan)
    except PlanError as error:
        print(f"❌ {error}", file=sys.stderr)
        return 2

    if args.block:
        plan["block"] = args.block
    if args.repeats is not None:
        plan["repeats"] = args.repeats
    plan.setdefault("repeats", MIN_REPEATS)

    seed = args.seed if args.seed is not None else random.randrange(1, 2**31 - 1)
    orders = [order_for(plan["runs"], seed + repeat) for repeat in range(plan["repeats"])]

    print(f"Блок {plan['block']}, seed {seed}, повторов {plan['repeats']}")
    for index, order in enumerate(orders, start=1):
        print(f"  повтор {index}: {' → '.join(run['label'] for run in order)}")
    if args.pause_minutes < MIN_PAUSE_MINUTES:
        print(
            f"⚠️  пауза {args.pause_minutes} мин — меньше, чем требует протокол "
            f"({MIN_PAUSE_MINUTES}–{MAX_PAUSE_MINUTES}). Блок не пройдёт гейт."
        )
    if args.dry_run:
        print("пробный прогон: ничего не измерено")
        return 0

    out = Path(args.out)
    raw = out / "raw" / plan["block"]
    raw.mkdir(parents=True, exist_ok=True)

    records: list[dict] = []
    last_on_address: dict[str, float] = {}

    for repeat, order in enumerate(orders, start=1):
        for run in order:
            parts = parts_of(run)
            addresses = addresses_of(run)

            # The pause is between two RUNS on one address. A run that touches
            # several addresses waits for the longest of them.
            waits = []
            for address in addresses:
                if address not in last_on_address:
                    continue
                elapsed = time.time() - last_on_address[address]
                if elapsed < args.pause_minutes * 60:
                    waits.append(args.pause_minutes * 60 - elapsed)
            if waits:
                wait = max(waits)
                print(f"  ждём {wait / 60:.1f} мин перед {run['label']}")
                time.sleep(wait)

            mode = run.get("mode", "sequential")
            profiles = ", ".join(part["profile"] for part in parts)
            print(f"  повтор {repeat}: {run['label']} ({mode}, профили {profiles})")

            if mode == "concurrent":
                # A6: two profiles on ONE address, at the same time. The point of
                # the run is the DOUBLE rate on that address, and running them one
                # after another would halve it — the run would measure something
                # else than the thing it is for.
                processes = []
                process_started: list[datetime] = []
                process_finished: list[datetime] = []
                started_at = now()
                for index, part in enumerate(parts):
                    processes.append(
                        (
                            index,
                            part,
                            subprocess.Popen(
                                [
                                    sys.executable,
                                    str(INSTRUMENT),
                                    "--profile",
                                    part["profile"],
                                    "--catalog",
                                    str(plan["catalog"]),
                                    "--count",
                                    str(plan["count"]),
                                    "--delay",
                                    str(part.get("delay", 3.0)),
                                    "--label",
                                    f"{run['label']}-{chr(ord('a') + index)}",
                                    "--json",
                                ]
                                + (["--query", plan["query"]] if plan.get("query") else []),
                                stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE,
                                text=True,
                            ),
                        )
                    )
                for index, part, process in processes:
                    process_started.append(started_at)
                    stdout, stderr = process.communicate()
                    process_finished.append(now())
                    started = iso(process_started[index])
                    finished = iso(process_finished[index])
                    record = summarise(
                        part,
                        plan,
                        run["label"],
                        stdout,
                        stderr,
                        process.returncode,
                        parse_iso(started),
                        parse_iso(finished),
                    )
                    record["part"] = chr(ord("a") + index)
                    record["repeat"] = repeat
                    record["seed"] = seed
                    record["block"] = plan["block"]
                    records.append(record)
                    path = raw / f"{run['label']}-{chr(ord('a') + index)}-r{repeat}.json"
                    path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                for address in addresses:
                    last_on_address[address] = time.time()
                continue

            for index, part in enumerate(parts):
                suffix = f"-{chr(ord('a') + index)}" if len(parts) > 1 else ""
                # The instrument is told which part it is, so its own output says
                # so; the RECORD carries the run's label, so the report and the
                # gate sum the run, not its parts.
                record = run_instrument(part, plan, f"{run['label']}{suffix}")
                record["repeat"] = repeat
                record["seed"] = seed
                record["block"] = plan["block"]
                if len(parts) > 1:
                    record["part"] = chr(ord("a") + index)
                records.append(record)
                path = raw / f"{run['label']}{suffix}-r{repeat}.json"
                path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            for address in addresses:
                last_on_address[address] = time.time()

    findings = evaluate(records, plan, [[run["label"] for run in order] for order in orders])
    rows = aggregate(records)
    passed = all(finding["ok"] for finding in findings)

    write_report(
        out / f"{plan['block']}.md",
        plan,
        records,
        rows,
        findings,
        [[run["label"] for run in order] for order in orders],
        seed,
        args.pause_minutes,
    )

    block = {
        "block": plan["block"],
        "seed": seed,
        "plan": plan,
        "orders": [[run["label"] for run in order] for order in orders],
        "pause_minutes": args.pause_minutes,
        "findings": findings,
        "passed": passed,
        "started_at": records[0]["started_at"] if records else None,
        "finished_at": records[-1]["finished_at"] if records else None,
    }
    (raw / "block.json").write_text(json.dumps(block, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print("")
    for finding in findings:
        print(f"  {'✅' if finding['ok'] else '❌'} {finding['rule']} — {finding['detail']}")
    print("")
    print(f"{'✅ блок прошёл гейт протокола' if passed else '❌ блок НЕ прошёл гейт протокола'}")
    print(f"   отчёт: {out / (plan['block'] + '.md')}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())

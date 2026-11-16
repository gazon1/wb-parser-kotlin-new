#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["typer>=0.12", "rich>=13", "httpx>=0.27"]
# ///
"""
wbparser — observe and drive the local crawler from a terminal.

    wbparser up                 start the stack and wait until it can serve
    wbparser status             health, credentials, crawl counters, egress
    wbparser crawl              run exactly one crawl, in the foreground
    wbparser items              what was scraped, paged and filtered
    wbparser stats              aggregate counters of the catalog
    wbparser runs               the journal: which target ran, from which address
    wbparser schedule           the tick, the freshness window, what is due
    wbparser logs               the application log
    wbparser down               stop the stack (the volume is kept)

Run it with `uv run scripts/parser/wbparser.py …` (the shebang handles the rest),
or install it once with `uv tool install --editable .`.

## Why this exists, and why it is not a web UI

The repository ships no web UI on purpose: the catalog is a read API with its own
storefront elsewhere, and a second interface inside the crawler would be a second
consumer of the same data with its own bugs. A terminal client costs no port, no
dependency beyond three libraries, and no deployment.

## Why it calls HTTP and never SQL

`docs/decisions/DIGEST.md`: the catalog is read through the API, and the API owns the
SQL. So this file contains no query — every fact comes from `/api/health/**`,
`/api/admin/**` or `/api/catalog/v1/**`. The log is the one exception, and it is not
data: `docker logs` is the only place a log line ever lived.

## Exit codes

0 — the command did what it says. 1 — it ran and something is wrong. 2 — it could not
run at all (no stack, no environment, no answer).
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from pathlib import Path
from typing import Annotated, Any

import httpx
import typer
from rich.console import Console
from rich.table import Table

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PORT = 8082
APP_CONTAINER = "wb-parser-debug"
PG_CONTAINER = "wb-parser-debug-postgres"
COMPOSE = ROOT / "docker-compose.yml"
JAR = ROOT / "app" / "build" / "libs" / "app.jar"

console = Console(highlight=False)
err_console = Console(stderr=True, highlight=False)

EXIT_OK, EXIT_WRONG, EXIT_UNUSABLE = 0, 1, 2


class Unusable(Exception):
    """The command cannot run here: a missing precondition, not a failed check."""


# `--json` writes one JSON document to stdout and nothing else, so `| jq` works on it
# without a flag to strip a header. A module-level flag is enough: one process, one run.
JSON = False


def _show_json(payload: Any) -> None:
    json.dump(payload, sys.stdout, ensure_ascii=False, indent=2, default=str)
    sys.stdout.write("\n")


# ── where the environment comes from ─────────────────────────────────────────


def _read_env_file(path: Path) -> dict[str, str]:
    """
    `KEY=value`, `# comment`, optional quotes. Parsed, not sourced: the file is data,
    and sourcing it would let a value that looks like a command run one.
    """
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip().removeprefix("export ").strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip("\"'")
    return values


def _port() -> int:
    return int(os.environ.get("SERVER_PORT") or _read_env_file(ROOT / ".env").get("SERVER_PORT") or DEFAULT_PORT)


def _base_url() -> str:
    return os.environ.get("WBPARSER_URL") or f"http://localhost:{_port()}"


def _env() -> dict[str, str]:
    """
    The credentials this tool acts with: the `.env` file, then the shell.

    `.env` wins on purpose. It describes the stack these commands start and talk to;
    an inherited `DB_USER=…` from an unrelated shell session describes something else,
    and letting it win silently pointed a crawl at a database that did not have that
    user. A wrong value in the file is visible and editable; a stale exported one is
    neither.
    """
    inherited = {
        name: value
        for name, value in os.environ.items()
        if name in _OVERRIDABLE and name not in _read_env_file(ROOT / ".env")
    }
    return {**inherited, **_read_env_file(ROOT / ".env")}


# The names a shell may supply when the file does not, and no others: everything else
# the file owns.
_OVERRIDABLE = {
    "DB_HOST",
    "DB_PORT",
    "DB_NAME",
    "DB_USER",
    "DB_PASSWORD",
    "SERVER_PORT",
    "ADMIN_API_KEY",
    "CATALOG_API_KEY",
}


def _client(*, admin: bool = False, catalog: bool = False) -> httpx.Client:
    """A client that carries the key of the API it is about to call — and only that one."""
    env = _env()
    headers: dict[str, str] = {}
    if admin:
        key = env.get("ADMIN_API_KEY", "")
        if not key:
            raise Unusable("ADMIN_API_KEY is not set — see docs/operations/credentials-runbook.md")
        headers["X-Api-Key"] = key
    if catalog:
        key = env.get("CATALOG_API_KEY", "")
        if not key:
            raise Unusable("CATALOG_API_KEY is not set — see docs/operations/credentials-runbook.md")
        headers["X-Api-Key"] = key
    return httpx.Client(base_url=_base_url(), headers=headers, timeout=15.0)


def _get(path: str, *, admin: bool = False, catalog: bool = False, **params: Any) -> Any:
    """
    One GET, with the failure modes already translated.

    A caller never sees an `httpx` exception: an unreachable stack says how to start it,
    and a refused key says which key.
    """
    query = {name: value for name, value in params.items() if value not in (None, "")}
    try:
        with _client(admin=admin, catalog=catalog) as client:
            response = client.get(path, params=query)
    except httpx.ConnectError as error:
        raise Unusable(f"cannot reach {_base_url()} — start it with `wbparser up`") from error
    except (httpx.TimeoutException, httpx.RemoteProtocolError, httpx.ReadError) as error:
        # A server that accepts the connection and then hangs up is a server that is
        # starting or restarting. Saying "cannot reach" would be wrong the moment it
        # finishes booting, so the message says what is actually true.
        raise Unusable(f"{_base_url()} is not answering yet — is it still starting?") from error

    if response.status_code == 401:
        raise Unusable(f"the key was refused for {path} (401) — check the key in .env")
    if response.status_code == 403:
        raise Unusable(f"the key is missing or wrong for {path} (403)")
    if response.status_code >= 400:
        detail = response.text.strip()[:200]
        raise Unusable(f"HTTP {response.status_code} on {path}" + (f": {detail}" if detail else ""))
    return response.json()


def _ready() -> dict[str, Any]:
    return _get("/api/health/ready")


def _require_stack() -> None:
    _ready()


def _docker(*argv: str) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(["docker", *argv], cwd=ROOT, text=True, check=True)
    except FileNotFoundError as error:
        raise Unusable("docker is not installed") from error
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "").strip()
        raise Unusable(f"docker {' '.join(argv[:2])} failed: {detail[:300]}") from error


def _running(container: str) -> bool:
    result = subprocess.run(
        ["docker", "inspect", "-f", "{{.State.Running}}", container],
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip() == "true"


# ── formatting ───────────────────────────────────────────────────────────────


def _instant(value: str | None) -> datetime | None:
    if not value:
        return None
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def _age(value: str | None) -> str:
    """How long ago, in the unit a human would use. `never` when there is no moment."""
    moment = _instant(value)
    if moment is None:
        return "never"
    seconds = int((datetime.now(UTC) - moment).total_seconds())
    return f"in {_span(-seconds)}" if seconds < 0 else f"{_span(seconds)} ago"


def _span(seconds: int) -> str:
    if seconds < 60:
        return f"{seconds}s"
    if seconds < 3600:
        return f"{seconds // 60}m"
    if seconds < 86400:
        return f"{seconds // 3600}h{seconds % 3600 // 60:02d}m"
    return f"{seconds // 86400}d{seconds % 86400 // 3600:02d}h"


def _rubles(kopecks: int | None) -> str:
    return "—" if kopecks is None else f"{kopecks / 100:.2f} ₽"


def _table(title: str, columns: list[str], rows: list[list[Any]]) -> None:
    table = Table(title=title, title_justify="left", header_style="bold", expand=False)
    for column in columns:
        table.add_column(column, overflow="ellipsis", no_wrap=False)
    for row in rows:
        table.add_row(*("—" if cell is None or cell == "" else str(cell) for cell in row))
    console.print(table)


def _headline(text: str) -> None:
    console.print(f"\n[bold]{text}[/bold]")


# ── commands ─────────────────────────────────────────────────────────────────


def up(
    build: Annotated[bool, typer.Option("--build", help="rebuild the jar even if one exists")] = False,
    timeout: Annotated[int, typer.Option(help="seconds to wait for readiness")] = 180,
) -> None:
    """Start the stack (Postgres + the application) and wait until it can serve."""
    if build or not JAR.is_file():
        console.print("building the jar…")
        _docker_run([str(ROOT / "gradlew"), ":app:bootJar", "--no-daemon"])
    else:
        console.print(f"[dim]reusing {JAR.relative_to(ROOT)} (rebuild with --build)[/dim]")

    console.print("starting the stack…")
    _docker("compose", "-f", str(COMPOSE), "up", "-d")

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if _ready().get("status") == "UP":
                break
        except Unusable:
            pass
        time.sleep(2)
    else:
        console.print(f"[red]✗[/red] the application did not become ready in {timeout}s")
        console.print("[dim]see what it said: wbparser logs -n 50[/dim]")
        raise typer.Exit(EXIT_WRONG)

    console.print(f"[green]✓[/green] ready at {_base_url()}")
    console.print(
        f"  health   {_base_url()}/api/health/ready\n"
        f"  catalog  {_base_url()}/api/catalog/v1/stats  (X-Api-Key)\n"
        f"  admin    {_base_url()}/api/admin/targets     (X-Api-Key)\n"
        f"  debugger localhost:5005 (JDWP)"
    )
    console.print("[dim]there is no web UI in this repository — `wbparser items` reads what was parsed[/dim]")


def _docker_run(argv: list[str]) -> None:
    try:
        subprocess.run(argv, cwd=ROOT, check=True)  # noqa: S603 - a fixed argv
    except subprocess.CalledProcessError as error:
        raise Unusable(f"{argv[0]} failed") from error


def down(
    volumes: Annotated[bool, typer.Option("--volumes", help="also delete the database volume")] = False,
) -> None:
    """Stop the stack. The database volume is kept unless --volumes is given."""
    if volumes:
        console.print("[yellow]![/yellow] removing the database volume — every scraped row is lost")
    _docker("compose", "-f", str(COMPOSE), "down", *(["-v"] if volumes else []))
    console.print("[green]✓[/green] stopped" + (" (volume removed)" if volumes else " (volume kept)"))


def status() -> None:
    """Health, readiness, credentials, crawl counters, and the egress groups."""
    _require_stack()
    ready = _ready()
    keys = {"ADMIN_API_KEY": _key_works(admin=True), "CATALOG_API_KEY": _key_works(admin=False)}
    if JSON:
        _show_json({"ready": ready, "keys": keys})
    else:
        _print_status(ready, keys)
    if ready.get("status") != "UP" or not all(keys.values()):
        raise typer.Exit(EXIT_WRONG)


def _key_works(*, admin: bool) -> bool:
    path = "/api/admin/targets" if admin else "/api/catalog/v1/health"
    try:
        _get(path, admin=admin, catalog=not admin)
    except Unusable:
        return False
    return True


def _print_status(ready: dict[str, Any], keys: dict[str, bool]) -> None:
    healthy = ready.get("status") == "UP"
    console.print(
        f"{'[green]✓[/green]' if healthy else '[red]✗[/red]'} application at {_base_url()}: "
        f"{'UP' if healthy else 'DOWN'}"
    )

    _headline("checks")
    for name, value in (ready.get("checks") or {}).items():
        mark = "[green]✓[/green]" if str(value).startswith("UP") else "[yellow]![/yellow]"
        console.print(f"  {mark} {name}: {value}")

    crawler = ready.get("crawler") or {}
    _headline("crawl")
    if crawler.get("state") == "not_started":
        console.print("  [yellow]![/yellow] not started — no counters yet")
    else:
        console.print(
            f"  requests {crawler.get('requests', 0)}  refusals {crawler.get('antibot_responses', 0)}"
            f"  share {crawler.get('refusal_share', 'n/a')}"
        )
        console.print(
            f"  pages {crawler.get('pages', 0)}  items {crawler.get('items', 0)}"
            f"  stale tokens {crawler.get('presets_responses', 0)}"
        )
        if retries := crawler.get("retries"):
            console.print("  retries: " + ", ".join(f"{reason}={count}" for reason, count in retries.items()))

    _headline("egress")
    for group in (ready.get("egress") or {}).get("groups") or []:
        console.print(
            f"  {group.get('group')}: {group.get('address') or 'unknown'}"
            f"  {'healthy' if group.get('healthy') else 'unhealthy'}"
            f"  isolated={group.get('isolated')}  refusals={group.get('refusal_rate')}"
        )

    _headline("credentials")
    for name, works in keys.items():
        console.print(
            f"  {'[green]✓[/green]' if works else '[red]✗[/red]'} {name}: "
            f"{'accepted' if works else 'refused or missing'}"
        )


def crawl() -> None:
    """
    Run exactly one crawl, in the foreground, and report the exit code.

    The `once` profile switches the web server and the security config off, so a crawl
    runs on a machine that has no API keys at all.
    """
    if not JAR.is_file():
        raise Unusable(f"{JAR.relative_to(ROOT)} is missing — run `wbparser up` first")
    if not _running(PG_CONTAINER):
        raise Unusable("the database is not running — start it with `wbparser up`")

    env = {**_env(), "SPRING_PROFILES_ACTIVE": "once"}
    env.setdefault("DB_HOST", "localhost")
    env.setdefault("DB_PORT", "5433")
    env.setdefault("DB_NAME", "wbparser")
    env.setdefault("DB_USER", "postgres")
    env.setdefault("CAMOUFOX_HOME", str(Path.home() / ".cache" / "camoufox"))
    env.setdefault("PROFILES_DIR", str(Path.home() / ".wbparser" / "profiles"))

    console.print(f"crawling once against {env['DB_HOST']}:{env['DB_PORT']}/{env['DB_NAME']}…")
    completed = subprocess.run(  # noqa: S603 - a fixed argv
        ["java", "-jar", str(JAR), "--spring.profiles.active=once"],
        cwd=ROOT,
        env=env,
        text=True,
        check=False,
    )
    codes = {
        0: "a crawl ran and succeeded",
        1: "a crawl ran and failed",
        2: "no crawl ran — the advisory lock is held elsewhere",
        3: "the crawl was cancelled",
    }
    reason = codes.get(completed.returncode, "an unexpected exit")
    mark = "[green]✓[/green]" if completed.returncode == EXIT_OK else "[red]✗[/red]"
    console.print(f"{mark} exit {completed.returncode}: {reason}")
    if completed.returncode != EXIT_OK:
        raise typer.Exit(EXIT_WRONG)


class Sort(StrEnum):
    """The orderings `/items` accepts. A wrong one is a 400, so the shell offers the right ones."""

    date_desc = "DateDesc"
    date_asc = "DateAsc"
    price_asc = "PriceAsc"
    price_desc = "PriceDesc"
    cashback_desc = "CashbackPercentDesc"
    cashback_asc = "CashbackPercentAsc"
    title_asc = "TitleAsc"
    title_desc = "TitleDesc"


def items(
    target: Annotated[str | None, typer.Option(help="a target UUID")] = None,
    query: Annotated[str | None, typer.Option("--query", "-q", help="search the title")] = None,
    page: Annotated[int, typer.Option(help="zero-based page index")] = 0,
    page_size: Annotated[int, typer.Option(help="rows per page (max 60)")] = 20,
    sort: Annotated[Sort, typer.Option(help="the order of the rows")] = Sort.date_desc,
    in_stock: Annotated[bool, typer.Option("--in-stock", help="only products marked in stock")] = False,
    min_price: Annotated[float | None, typer.Option(help="minimum price, rubles")] = None,
    max_price: Annotated[float | None, typer.Option(help="maximum price, rubles")] = None,
    min_cashback: Annotated[float | None, typer.Option(help="minimum cashback, percent")] = None,
) -> None:
    """What was scraped, through the catalog API — the same data the storefront reads."""
    _require_stack()
    result = _get(
        "/api/catalog/v1/items",
        catalog=True,
        targetId=target,
        q=query,
        sort=sort.value,
        page=page,
        pageSize=page_size,
        inStock="true" if in_stock else None,
        minPrice=min_price,
        maxPrice=max_price,
        minCashback=min_cashback,
    )
    if JSON:
        _show_json(result)
    else:
        _print_items(result)
    if not result.get("totalCount"):
        raise typer.Exit(EXIT_WRONG)


def _print_items(page: dict[str, Any]) -> None:
    rows = page.get("items") or []
    _headline(
        f"products — page {page.get('page', 0) + 1}/{max(1, page.get('totalPages', 0))}"
        f", {page.get('totalCount', 0)} total"
    )
    if not rows:
        console.print("  [yellow]![/yellow] nothing matched")
        console.print("[dim]is the crawl done? try `wbparser status` and `wbparser runs`[/dim]")
        return
    _table(
        "",
        ["target", "title", "price", "cashback", "scraped"],
        [
            [
                item.get("targetName"),
                item.get("title"),
                _rubles(item.get("salePriceKopecks") or item.get("priceKopecks")),
                f"{item['cashbackPercent']}%" if item.get("cashbackPercent") is not None else "—",
                _age(item.get("scrapedAt")),
            ]
            for item in rows
        ],
    )
    console.print(f"[dim]next page: wbparser items --page {page.get('page', 0) + 1}[/dim]")
    if data_as_of := page.get("dataAsOf"):
        console.print(f"[dim]the data on this page is {_age(data_as_of)}[/dim]")


def stats() -> None:
    """Aggregate counters of the catalog: how big it is and how fresh."""
    _require_stack()
    payload = _get("/api/catalog/v1/stats", catalog=True)
    if JSON:
        _show_json(payload)
        return
    _headline("catalog")
    console.print(f"  products            {payload.get('totalProducts', 0)}")
    console.print(
        f"  observations        {payload.get('totalItems', 0)}  [dim](one row per target × product × price)[/dim]"
    )
    console.print(f"  targets             {payload.get('totalTargets', 0)}")
    console.print(
        f"  with cashback       {payload.get('productsWithCashback', 0)} products"
        f" / {payload.get('withCashback', 0)} observations"
    )
    console.print(f"  newest observation  {_age(payload.get('newestScrapedAt'))}")
    console.print(f"  [dim]counters computed {_age(payload.get('asOf'))} (cached up to ten minutes)[/dim]")


def runs(
    limit: Annotated[int, typer.Option(help="how many rows")] = 20,
    target: Annotated[str | None, typer.Option(help="a target UUID")] = None,
    errors: Annotated[int, typer.Option(help="how many unresolved errors to show")] = 10,
) -> None:
    """The journal of runs: what ran, on which address, and what came of it."""
    _require_stack()
    jobs = _get("/api/admin/jobs", admin=True, limit=limit, targetId=target)
    target_runs = _get("/api/admin/target-runs", admin=True, limit=limit, targetId=target)
    failures = _get("/api/admin/errors", admin=True, limit=errors, unresolvedOnly="true")
    if JSON:
        _show_json({"jobs": jobs, "targetRuns": target_runs, "errors": failures})
        return
    _print_runs(target_runs, jobs, failures)


def _print_runs(target_runs: list[dict], jobs: list[dict], failures: list[dict]) -> None:
    _headline(f"target runs — last {len(target_runs)}")
    if target_runs:
        _table(
            "",
            ["target", "group", "address", "outcome", "pages", "items", "wb total", "when"],
            [
                [
                    run.get("targetName") or str(run.get("targetId"))[:8],
                    run.get("egressGroup"),
                    run.get("egressIp"),
                    run.get("outcome"),
                    run.get("pages", 0),
                    run.get("items", 0),
                    run.get("wbTotal"),
                    _age(run.get("startedAt")),
                ]
                for run in target_runs
            ],
        )
        refused = sum(1 for run in target_runs if run.get("outcome") == "antibot")
        if refused:
            console.print(
                f"  [yellow]![/yellow] {refused} of {len(target_runs)} runs were refused by WB"
                f" — that is the address, not the target"
            )
    else:
        console.print("  [yellow]![/yellow] no runs recorded yet")

    _headline(f"jobs — last {len(jobs)}")
    if jobs:
        _table(
            "",
            ["status", "pages", "items", "started", "error"],
            [
                [
                    job.get("status"),
                    job.get("pagesCrawled", 0),
                    job.get("itemsSaved", 0),
                    _age(job.get("startedAt")),
                    job.get("errorMessage"),
                ]
                for job in jobs
            ],
        )
    else:
        console.print("  [yellow]![/yellow] no jobs recorded yet")

    if failures:
        _headline(f"unresolved errors — {len(failures)}")
        for failure in failures:
            console.print(
                f"  [yellow]![/yellow] {failure.get('category')}: {failure.get('message')}"
                f"  [dim]({_age(failure.get('createdAt'))})[/dim]"
            )


def schedule(
    limit: Annotated[int, typer.Option(help="how many targets to list")] = 15,
) -> None:
    """
    The tick, the freshness window, and which targets are due.

    The crawler has no "next run at" query and should not have one: a target is due by
    the same rule the claim uses — active, nothing pending, and past its freshness
    window. This command applies that rule to what the admin API reports.
    """
    _require_stack()
    env = _env()
    cron = env.get("CRAWLER_SCHEDULE_CRON", "0 0 */2 * * *")
    freshness = int(env.get("CRAWLER_SCHEDULE_FRESHNESS_MINUTES", "360"))
    targets = _get("/api/admin/targets", admin=True)

    now = datetime.now(UTC)
    due, upcoming = _partition_by_due(targets, now, freshness)
    if JSON:
        _show_json(
            {
                "cron": cron,
                "freshnessMinutes": freshness,
                "activeTargets": sum(1 for t in targets if t.get("active", True)),
                "due": due,
                "upcoming": [{"at": when, "target": t} for when, t in upcoming[:limit]],
            }
        )
        return
    _print_schedule(cron, freshness, targets, due, upcoming, limit)


def _partition_by_due(
    targets: list[dict], now: datetime, freshness_minutes: int
) -> tuple[list[dict], list[tuple[datetime, dict]]]:
    """
    Split the targets by the rule the claim uses — as far as the API exposes it.

    The claim has three conditions (`claimDueTargets`): active, nothing pending, and no
    live lease. The admin API reports the first two and not the lease, so this can say
    "due" about a target the crawler will skip because an interrupted run still holds
    its lease. That was a real confusion once: 132 targets listed as due, and a crawl
    that found nothing to do. The lease expires on its own (two hours by default), so
    the disagreement is bounded — and `schedule` says so instead of hiding it.
    """
    fresh_before = now - timedelta(minutes=freshness_minutes)
    due: list[dict] = []
    upcoming: list[tuple[datetime, dict]] = []
    for target in targets:
        if not target.get("active", True):
            continue
        pending = _instant(target.get("nextAttemptAt"))
        if pending and pending > now:
            upcoming.append((pending, target))
            continue
        last = _instant(target.get("lastCrawledAt"))
        if last is None or last <= fresh_before:
            due.append(target)
        else:
            upcoming.append((last + timedelta(minutes=freshness_minutes), target))
    return due, sorted(upcoming, key=lambda pair: pair[0])


def _print_schedule(cron, freshness, targets, due, upcoming, limit: int) -> None:
    _headline("schedule")
    console.print(f"  tick       {cron}  [dim](how often the scheduler looks for work)[/dim]")
    console.print(f"  freshness  {freshness} min  [dim](how long a target rests after a crawl)[/dim]")
    console.print("  [dim]the tick is not the cadence: a tick that finds nothing due starts nothing[/dim]")
    console.print(f"\n  active targets  {sum(1 for t in targets if t.get('active', True))} of {len(targets)}")
    console.print(f"  due now         {len(due)}")
    if due:
        console.print("  [dim]a target held by an interrupted run's lease is listed here and[/dim]")
        console.print("  [dim]skipped by the crawl until that lease expires (2h by default)[/dim]")

    if due:
        _headline(f"due now — first {min(limit, len(due))}")
        _table(
            "",
            ["target", "wb id", "last crawled", "fails"],
            [
                [t.get("name"), t.get("wbCatalogId"), _age(t.get("lastCrawledAt")), t.get("consecutiveFailures", 0)]
                for t in due[:limit]
            ],
        )
    if upcoming:
        _headline(f"next due — first {min(limit, len(upcoming))}")
        _table("", ["target", "when"], [[t.get("name"), _age(when.isoformat())] for when, t in upcoming[:limit]])


def logs(
    follow: Annotated[bool, typer.Option("--follow", "-f", help="keep watching")] = False,
    lines: Annotated[int, typer.Option("--lines", "-n", help="how many lines to start from")] = 100,
    grep: Annotated[str | None, typer.Option(help="only lines matching this regular expression")] = None,
) -> None:
    """
    The application log. Not data, so not the API: `docker logs` is where it lives.
    """
    if not _running(APP_CONTAINER) and not _running(PG_CONTAINER):
        raise Unusable("nothing is running — start it with `wbparser up`")
    argv = ["docker", "logs", *(["--follow"] if follow else []), "--tail", str(lines), APP_CONTAINER]
    if grep:
        completed = subprocess.run([*argv], cwd=ROOT, text=True, capture_output=True, check=False)
        console.print(f"[dim]$ {' '.join(argv)} | grep -E {grep!r}[/dim]")
        for line in completed.stdout.splitlines():
            if re.search(grep, line):
                print(line)
        return
    subprocess.run(argv, cwd=ROOT, check=False)  # noqa: S603 - a fixed argv


# ── entry point ──────────────────────────────────────────────────────────────


app = typer.Typer(
    add_completion=False,
    no_args_is_help=True,
    rich_markup_mode="rich",
    help="Observe and drive the local crawler. There is no web UI in this repository.",
)
for _command in (up, down, status, crawl, items, stats, runs, schedule, logs):
    app.command()(_command)


@app.callback()
def _global_options(
    json_output: Annotated[bool, typer.Option("--json", help="one JSON document on stdout, nothing else")] = False,
) -> None:
    """Global flags, accepted before or after the command."""
    global JSON  # noqa: PLW0603 - one process, one run, one output mode
    JSON = json_output


def _dispatch() -> None:
    """Run the app, and turn the two failure classes into exit codes instead of tracebacks."""
    try:
        app()
    except Unusable as error:
        err_console.print(f"[red]error:[/red] {error}")
        sys.exit(EXIT_UNUSABLE)
    except httpx.HTTPError as error:
        err_console.print(f"[red]error:[/red] {error}")
        sys.exit(EXIT_UNUSABLE)
    except KeyboardInterrupt:
        sys.exit(130)


if __name__ == "__main__":
    _dispatch()

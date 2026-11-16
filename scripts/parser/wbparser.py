#!/usr/bin/env -S uv run
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

Run it with `uv run scripts/parser/wbparser.py …` — dependencies live in
`pyproject.toml`, so `uv sync` is the only setup step.

## Why this exists, and why it is not a web UI

The repository ships no web UI on purpose: the catalog is a read API with its own
storefront elsewhere, and a second interface inside the crawler would be a second
consumer of the same data with its own bugs. A terminal client costs no port and no
deployment.

## Why it calls HTTP and never SQL

`docs/decisions/DIGEST.md`: the catalog is read through the API, and the API owns the
SQL. So this file contains no query — every fact comes from `/api/health/**`,
`/api/admin/**` or `/api/catalog/v1/**`. The log is the one exception, and it is not
data: `docker compose logs` is the only place a log line ever lived.

## Why the responses are validated

The shapes below are hand-written rather than generated: `contract/catalog-v1.openapi.yaml`
describes the catalog and nothing else, so generation would type half the client and
leave the admin half untyped. When the server publishes a whole spec (springdoc, or a
committed `docs/openapi.json`), replace this section with generated models — the rest of
the file does not change.

Validation earns its lines: a renamed field fails with a message naming the field instead
of rendering "—", and `--json` becomes `model_dump` rather than a second serialiser.

## Exit codes

0 — the command did what it says. 1 — it ran and something is wrong. 2 — it could not
run at all (no stack, no environment, no answer).
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from functools import cache
from pathlib import Path
from typing import Annotated, Any, TypeVar

try:
    import click
    import httpx
    import typer
    from croniter import croniter
    from dotenv import dotenv_values
    from pydantic import BaseModel, ConfigDict, TypeAdapter, ValidationError
    from pydantic.alias_generators import to_camel
    from rich.console import Console
    from rich.markup import escape
    from rich.table import Table
except ModuleNotFoundError as missing:  # pragma: no cover - a wrong interpreter, not a code path
    # A bare `python3 script.py` does not read `pyproject.toml`, so the dependencies are
    # simply absent. Saying that beats a traceback that ends in "No module named
    # 'typer'" — that message names the symptom and not the fix.
    raise SystemExit(
        f"error: {missing.name} is not installed for this interpreter.\n"
        "Run it through the project environment instead:\n"
        "  uv run scripts/parser/wbparser.py …\n"
        "  just parser::…        (the same thing)\n"
        "Setup, once: uv sync"
    ) from missing

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PORT = 8082
JAR = ROOT / "app" / "build" / "libs" / "app.jar"

console = Console(highlight=False)
err_console = Console(stderr=True, highlight=False)

EXIT_OK, EXIT_WRONG, EXIT_UNUSABLE = 0, 1, 2


class Unusable(Exception):
    """The command cannot run here: a missing precondition, not a failed check."""


# `--json` writes one JSON document to stdout and nothing else, so `| jq` works on it
# without a flag to strip a header. One process, one run, one output mode.
JSON = False

# ── responses ────────────────────────────────────────────────────────────────


class Camel(BaseModel):
    """A response whose JSON is camelCase, as Spring writes it, with snake_case fields here."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


class Item(Camel):
    id: str
    target_name: str
    title: str
    price_kopecks: int | None = None
    sale_price_kopecks: int | None = None
    cashback_percent: float | None = None
    scraped_at: datetime | None = None


class ItemsPage(Camel):
    items: list[Item]
    total_count: int
    page: int
    total_pages: int
    data_as_of: datetime | None = None


class Stats(Camel):
    total_items: int
    total_products: int
    total_targets: int
    with_cashback: int
    products_with_cashback: int
    newest_scraped_at: datetime | None = None
    as_of: datetime | None = None


class Target(Camel):
    id: str
    name: str
    active: bool = True
    wb_catalog_id: str | None = None
    last_crawled_at: datetime | None = None
    next_attempt_at: datetime | None = None
    consecutive_failures: int = 0


class Job(Camel):
    id: str
    status: str
    pages_crawled: int = 0
    items_saved: int = 0
    started_at: datetime | None = None
    completed_at: datetime | None = None
    # `schedule` or `manual`, and null for rows written before the trigger was recorded.
    triggered_by: str | None = None
    error_message: str | None = None


class TargetRun(Camel):
    target_id: str
    target_name: str | None = None
    egress_group: str | None = None
    egress_ip: str | None = None
    outcome: str
    pages: int = 0
    items: int = 0
    wb_total: int | None = None
    started_at: datetime | None = None


class RecordedError(Camel):
    category: str | None = None
    message: str
    created_at: datetime | None = None


# ── environment ──────────────────────────────────────────────────────────────


@cache
def _file_env() -> dict[str, str]:
    """The `.env` file, parsed (not sourced: a value that looks like a command is data)."""
    return {name: value for name, value in dotenv_values(ROOT / ".env").items() if value is not None}


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


@cache
def _env() -> dict[str, str]:
    """
    The credentials this tool acts with: the shell, then the `.env` file.

    The file wins on purpose. It describes the stack these commands start and talk to;
    an inherited `DB_USER=…` from an unrelated shell session describes something else,
    and letting it win silently pointed a crawl at a database that did not have that
    user. A wrong value in the file is visible and editable; a stale exported one is
    neither.
    """
    shell = {name: value for name, value in os.environ.items() if name in _OVERRIDABLE}
    return {**shell, **_file_env()}


@cache
def _base_url() -> str:
    port = os.environ.get("SERVER_PORT") or _file_env().get("SERVER_PORT") or DEFAULT_PORT
    return os.environ.get("WBPARSER_URL") or f"http://localhost:{port}"


@cache
def _container_env(name: str) -> str | None:
    """
    The value the running application actually holds, or None when it is not running.

    The compose file overrides several of these, so the file describes a stack nobody
    may be running: `CRAWLER_SCHEDULE_CRON` is `*/30 * * * * *` in the container and
    absent from `.env`. A report that reads the file describes the wrong stack.
    """
    if not _running("app"):
        return None
    completed = subprocess.run(
        ["docker", "compose", "exec", "-T", "app", "printenv", name],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    return completed.stdout.strip() or None


def _effective(name: str, default: str) -> str:
    """Container, then `.env`, then the default — the most specific answer wins."""
    return _container_env(name) or _env().get(name) or default


@cache
def _client(*, admin: bool = False, catalog: bool = False) -> httpx.Client:
    """
    One client per role, carrying that role's key and no other.

    Two roles, two clients: the admin key must not travel to the catalog API even by
    accident, and the admin API refuses a request whose key is the catalog's.
    """
    headers: dict[str, str] = {}
    env = _env()
    if admin or catalog:
        name = "ADMIN_API_KEY" if admin else "CATALOG_API_KEY"
        key = env.get(name, "")
        if not key:
            raise Unusable(f"{name} is not set — see docs/operations/credentials-runbook.md")
        headers["X-Api-Key"] = key
    return httpx.Client(base_url=_base_url(), headers=headers, timeout=15.0)


T = TypeVar("T")

# ── HTTP ─────────────────────────────────────────────────────────────────────


def _get(
    path: str,
    model: type[T] | None = None,
    *,
    admin: bool = False,
    catalog: bool = False,
    **params: Any,
) -> Any:
    """
    One GET, parsed into `model`, with the failure modes already translated.

    A caller never sees an `httpx` exception: an unreachable stack says how to start it,
    a refused key says which key, and a response that does not match the model says so.
    """
    query = {name: value for name, value in params.items() if value not in (None, "")}
    try:
        response = _client(admin=admin, catalog=catalog).get(path, params=query)
    except httpx.ConnectError as error:
        raise Unusable(f"cannot reach {_base_url()} — start it with `wbparser up`") from error
    except (httpx.TimeoutException, httpx.RemoteProtocolError, httpx.ReadError) as error:
        # A server that accepts the connection and then hangs up is a server that is
        # starting or restarting. "Cannot reach" would be wrong the moment it boots.
        raise Unusable(f"{_base_url()} is not answering yet — is it still starting?") from error

    if response.status_code == 401:
        raise Unusable(f"the key was refused for {path} (401) — check the key in .env")
    if response.status_code == 403:
        raise Unusable(f"the key is missing or wrong for {path} (403)")
    if response.status_code >= 400:
        detail = response.text.strip()[:200]
        raise Unusable(f"HTTP {response.status_code} on {path}" + (f": {detail}" if detail else ""))

    payload = response.json()
    if model is None:
        # A shape this file does not own: `/api/health/ready` reports whatever the
        # crawler has counters for, and a model would freeze that into a contract the
        # server never agreed to. `status` prints it as it comes.
        return payload
    try:
        return TypeAdapter(model).validate_python(payload)
    except ValidationError as error:
        raise Unusable(f"{path}: the response does not match the model — has the API changed?\n{error}") from error


def _emit(payload: Any, render: Any) -> None:
    """One code path for `--json` and for the human table: the caller says what to draw."""
    if JSON:
        json.dump(payload, sys.stdout, ensure_ascii=False, indent=2, default=str)
        sys.stdout.write("\n")
    else:
        render()


# ── formatting ───────────────────────────────────────────────────────────────


def _age(moment: datetime | None) -> str:
    """How long ago, in the unit a human would use. `never` when there is no moment."""
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
    """
    A table whose cells are data, not markup.

    Every cell is escaped: a product title is the site's text, and a title containing
    `[bold]` would otherwise be parsed as Rich markup — a title containing `[/x]` would
    raise `MarkupError` and take the command down.
    """
    table = Table(title=title, title_justify="left", header_style="bold")
    for column in columns:
        table.add_column(column, overflow="ellipsis")
    for row in rows:
        table.add_row(*(escape("—" if cell is None or cell == "" else str(cell)) for cell in row))
    console.print(table)


def _headline(text: str) -> None:
    console.print(f"\n[bold]{escape(text)}[/bold]")


# ── docker ───────────────────────────────────────────────────────────────────


def _run(argv: list[str], *, live: bool = False) -> subprocess.CompletedProcess[str]:
    """
    Run a command in the repository root.

    `live=True` inherits the terminal: a multi-minute Gradle build prints as it goes,
    and capturing it would hide the progress *and* the reason it failed. Everything else
    is captured, so a failure can quote what the command actually said — with the stream
    inherited, `stderr` is empty and the message has nothing to quote.
    """
    try:
        completed = subprocess.run(  # noqa: S603 - argv is built here, never from user input
            argv, cwd=ROOT, text=True, capture_output=not live, check=False
        )
    except FileNotFoundError as error:
        raise Unusable(f"'{argv[0]}' is not installed") from error
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "").strip()
        raise Unusable(f"`{' '.join(argv[:4])}` failed: {detail[:300] or 'no output'}")
    if not live and completed.stdout.strip():
        console.print(completed.stdout.rstrip(), style="dim")
    return completed


def _compose(*argv: str, live: bool = False) -> subprocess.CompletedProcess[str]:
    # Compose finds `docker-compose.yml` in the working directory, so no `-f` is needed.
    return _run(["docker", "compose", *argv], live=live)


def _running(service: str) -> bool:
    completed = subprocess.run(
        ["docker", "compose", "ps", "--status", "running", "--services"],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    return service in completed.stdout.split()


# ── commands ─────────────────────────────────────────────────────────────────


app = typer.Typer(
    add_completion=False,
    no_args_is_help=True,
    rich_markup_mode="rich",
    help="Observe and drive the local crawler. There is no web UI in this repository.",
)


def _global_options(
    json_output: Annotated[bool, typer.Option("--json", help="one JSON document on stdout, nothing else")] = False,
) -> None:
    """Global flags. They go before the command: `wbparser --json status`."""
    global JSON  # noqa: PLW0603 - one process, one run, one output mode
    JSON = json_output


app.callback()(_global_options)


@app.command()
def up(
    build: Annotated[bool, typer.Option("--build", help="rebuild the jar even if one exists")] = False,
    timeout: Annotated[int, typer.Option(help="seconds to wait for readiness")] = 180,
) -> None:
    """Start the stack (Postgres + the application) and wait until it can serve."""
    if build or not JAR.is_file():
        console.print("building the jar…")
        _run([str(ROOT / "gradlew"), ":app:bootJar", "--no-daemon"], live=True)
    else:
        console.print(f"[dim]reusing {JAR.relative_to(ROOT)} (rebuild with --build)[/dim]")

    console.print("starting the stack…")
    _compose("up", "-d")

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if _get("/api/health/ready").get("status") == "UP":
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


@app.command()
def down(
    volumes: Annotated[bool, typer.Option("--volumes", help="also delete the database volume")] = False,
) -> None:
    """Stop the stack. The database volume is kept unless --volumes is given."""
    if volumes:
        console.print("[yellow]![/yellow] removing the database volume — every scraped row is lost")
    _compose("down", *(["-v"] if volumes else []))
    console.print("[green]✓[/green] stopped" + (" (volume removed)" if volumes else " (volume kept)"))


@app.command()
def status() -> None:
    """Health, readiness, credentials, crawl counters, and the egress groups."""
    ready = _get("/api/health/ready")
    keys = {"ADMIN_API_KEY": _key_works(admin=True), "CATALOG_API_KEY": _key_works(admin=False)}
    _emit({"ready": ready, "keys": keys}, lambda: _print_status(ready, keys))
    if ready.get("status") != "UP" or not all(keys.values()):
        raise typer.Exit(EXIT_WRONG)


def _key_works(*, admin: bool) -> bool:
    try:
        _get("/api/admin/targets" if admin else "/api/catalog/v1/health", admin=admin, catalog=not admin)
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
        console.print(f"  {mark} {escape(name)}: {escape(str(value))}")

    crawler = ready.get("crawler") or {}
    _headline("crawl")
    if crawler.get("state") == "not_started":
        console.print("  [yellow]![/yellow] not started — no counters yet")
    else:
        console.print(
            f"  requests {crawler.get('requests', 0)}  refusals {crawler.get('antibot_responses', 0)}"
            f"  share {escape(str(crawler.get('refusal_share', 'n/a')))}"
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
            f"  {escape(str(group.get('group')))}: {escape(str(group.get('address') or 'unknown'))}"
            f"  {'healthy' if group.get('healthy') else 'unhealthy'}"
            f"  isolated={group.get('isolated')}  refusals={group.get('refusal_rate')}"
        )

    _headline("credentials")
    for name, works in keys.items():
        console.print(
            f"  {'[green]✓[/green]' if works else '[red]✗[/red]'} {name}: "
            f"{'accepted' if works else 'refused or missing'}"
        )


@app.command()
def crawl() -> None:
    """
    Run exactly one crawl, in the foreground, and report the exit code.

    The `once` profile switches the web server and the security config off, so a crawl
    runs on a machine that has no API keys at all.
    """
    if not JAR.is_file():
        raise Unusable(f"{JAR.relative_to(ROOT)} is missing — run `wbparser up` first")
    if not _running("postgres"):
        raise Unusable("the database is not running — start it with `wbparser up`")

    # `env=` REPLACES the environment rather than extending it, so PATH, HOME and
    # JAVA_HOME have to be carried over explicitly: without them `java` is found only
    # because it happens to sit in /usr/bin, and a JDK-managed one is not found at all.
    env = {
        **os.environ,
        **_env(),
        "SPRING_PROFILES_ACTIVE": "once",
        "CAMOUFOX_HOME": _env().get("CAMOUFOX_HOME") or str(Path.home() / ".cache" / "camoufox"),
        "PROFILES_DIR": _env().get("PROFILES_DIR") or str(Path.home() / ".wbparser" / "profiles"),
    }
    env.setdefault("DB_HOST", "localhost")
    env.setdefault("DB_PORT", "5433")
    env.setdefault("DB_NAME", "wbparser")
    env.setdefault("DB_USER", "postgres")

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


@app.command()
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
    result = _get(
        "/api/catalog/v1/items",
        ItemsPage,
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
    _emit(result.model_dump(mode="json", by_alias=True), lambda: _print_items(result))
    if not result.total_count:
        raise typer.Exit(EXIT_WRONG)


def _print_items(result: ItemsPage) -> None:
    _headline(f"products — page {result.page + 1}/{max(1, result.total_pages)}, {result.total_count} total")
    if not result.items:
        console.print("  [yellow]![/yellow] nothing matched")
        console.print("[dim]is the crawl done? try `wbparser status` and `wbparser runs`[/dim]")
        return
    _table(
        "",
        ["target", "title", "price", "cashback", "scraped"],
        [
            [
                item.target_name,
                item.title,
                _rubles(item.sale_price_kopecks or item.price_kopecks),
                f"{item.cashback_percent}%" if item.cashback_percent is not None else "—",
                _age(item.scraped_at),
            ]
            for item in result.items
        ],
    )
    console.print(f"[dim]next page: wbparser items --page {result.page + 1}[/dim]")
    if result.data_as_of:
        console.print(f"[dim]the data on this page is {_age(result.data_as_of)}[/dim]")


@app.command()
def stats() -> None:
    """Aggregate counters of the catalog: how big it is and how fresh."""
    payload = _get("/api/catalog/v1/stats", Stats, catalog=True)
    _emit(payload.model_dump(mode="json", by_alias=True), lambda: _print_stats(payload))


def _print_stats(payload: Stats) -> None:
    _headline("catalog")
    console.print(f"  products            {payload.total_products}")
    console.print(f"  observations        {payload.total_items}  [dim](one row per target × product × price)[/dim]")
    console.print(f"  targets             {payload.total_targets}")
    console.print(
        f"  with cashback       {payload.products_with_cashback} products / {payload.with_cashback} observations"
    )
    console.print(f"  newest observation  {_age(payload.newest_scraped_at)}")
    console.print(f"  [dim]counters computed {_age(payload.as_of)} (cached up to ten minutes)[/dim]")


@app.command()
def runs(
    limit: Annotated[int, typer.Option(help="how many rows")] = 20,
    target: Annotated[str | None, typer.Option(help="a target UUID")] = None,
    errors: Annotated[int, typer.Option(help="how many unresolved errors to show")] = 10,
) -> None:
    """The journal of runs: what ran, on which address, and what came of it."""
    jobs = _get("/api/admin/jobs", list[Job], admin=True, limit=limit, targetId=target)
    target_runs = _get("/api/admin/target-runs", list[TargetRun], admin=True, limit=limit, targetId=target)
    failures = _get("/api/admin/errors", list[RecordedError], admin=True, limit=errors, unresolvedOnly="true")
    _emit(
        {
            "jobs": [job.model_dump(mode="json", by_alias=True) for job in jobs],
            "targetRuns": [run.model_dump(mode="json", by_alias=True) for run in target_runs],
            "errors": [error.model_dump(mode="json", by_alias=True) for error in failures],
        },
        lambda: _print_runs(target_runs, jobs, failures),
    )


def _print_runs(target_runs: list[TargetRun], jobs: list[Job], failures: list[RecordedError]) -> None:
    _headline(f"target runs — last {len(target_runs)}")
    if target_runs:
        _table(
            "",
            ["target", "group", "address", "outcome", "pages", "items", "wb total", "when"],
            [
                [
                    run.target_name or run.target_id[:8],
                    run.egress_group,
                    run.egress_ip,
                    run.outcome,
                    run.pages,
                    run.items,
                    run.wb_total,
                    _age(run.started_at),
                ]
                for run in target_runs
            ],
        )
        refused = sum(1 for run in target_runs if run.outcome == "antibot")
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
            [[job.status, job.pages_crawled, job.items_saved, _age(job.started_at), job.error_message] for job in jobs],
        )
    else:
        console.print("  [yellow]![/yellow] no jobs recorded yet")

    if failures:
        _headline(f"unresolved errors — {len(failures)}")
        for failure in failures:
            console.print(
                f"  [yellow]![/yellow] {escape(str(failure.category))}: {escape(failure.message)}"
                f"  [dim]({_age(failure.created_at)})[/dim]"
            )


@app.command()
def schedule(
    limit: Annotated[int, typer.Option(help="how many targets to list")] = 15,
) -> None:
    """
    The tick, the freshness window, and which targets are due.

    The rule below is `claimDueTargets`, copied. The copy is deliberate for now and it
    is also the weak point: the claim has three conditions (active, nothing pending, no
    live lease) and the admin API reports two, so a target held by an interrupted run's
    lease is listed here and skipped by the crawl. The durable fix is an
    `/api/admin/schedule` endpoint that answers the question once, on the side that owns
    the rule; until then this command says so rather than hiding it.
    """
    targets = _get("/api/admin/targets", list[Target], admin=True)
    cron = _env().get("CRAWLER_SCHEDULE_CRON") or "0 0 */2 * * *"
    freshness = int(_env().get("CRAWLER_SCHEDULE_FRESHNESS_MINUTES") or 360)

    now = datetime.now(UTC)
    due, upcoming = _partition_by_due(targets, now, freshness)
    _emit(
        {
            "cron": cron,
            "freshnessMinutes": freshness,
            "activeTargets": sum(1 for target in targets if target.active),
            "due": [target.model_dump(mode="json", by_alias=True) for target in due],
            "upcoming": [
                {"at": when, "target": target.model_dump(mode="json", by_alias=True)}
                for when, target in upcoming[:limit]
            ],
        },
        lambda: _print_schedule(cron, freshness, targets, due, upcoming, limit),
    )


def _partition_by_due(
    targets: list[Target], now: datetime, freshness_minutes: int
) -> tuple[list[Target], list[tuple[datetime, Target]]]:
    """Split by the claim's rule, as far as the API exposes it — see [schedule]."""
    fresh_before = now - timedelta(minutes=freshness_minutes)
    due: list[Target] = []
    upcoming: list[tuple[datetime, Target]] = []
    for target in targets:
        if not target.active:
            continue
        if target.next_attempt_at and target.next_attempt_at > now:
            upcoming.append((target.next_attempt_at, target))
        elif target.last_crawled_at is None or target.last_crawled_at <= fresh_before:
            due.append(target)
        else:
            upcoming.append((target.last_crawled_at + timedelta(minutes=freshness_minutes), target))
    return due, sorted(upcoming, key=lambda pair: pair[0])


def _print_schedule(
    cron: str,
    freshness: int,
    targets: list[Target],
    due: list[Target],
    upcoming: list[tuple[datetime, Target]],
    limit: int,
) -> None:
    _headline("schedule")
    console.print(f"  tick       {escape(cron)}  [dim](how often the scheduler looks for work)[/dim]")
    console.print(f"  freshness  {freshness} min  [dim](how long a target rests after a crawl)[/dim]")
    console.print("  [dim]the tick is not the cadence: a tick that finds nothing due starts nothing[/dim]")
    console.print(f"\n  active targets  {sum(1 for t in targets if t.active)} of {len(targets)}")
    console.print(f"  due now         {len(due)}")
    if due:
        console.print("  [dim]a target held by an interrupted run's lease is listed here and[/dim]")
        console.print("  [dim]skipped by the crawl until that lease expires (2h by default)[/dim]")

    if due:
        _headline(f"due now — first {min(limit, len(due))}")
        _table(
            "",
            ["target", "wb id", "last crawled", "fails"],
            [[t.name, t.wb_catalog_id, _age(t.last_crawled_at), t.consecutive_failures] for t in due[:limit]],
        )
    if upcoming:
        _headline(f"next due — first {min(limit, len(upcoming))}")
        _table("", ["target", "when"], [[t.name, _age(when)] for when, t in upcoming[:limit]])


@app.command()
def history(
    limit: Annotated[int, typer.Option(help="how many runs to show")] = 15,
) -> None:
    """
    The last crawler runs, what started each, and when the next one is due.

    "What started it" comes from `crawl_jobs.triggered_by`: `schedule` for a scheduler
    tick, `manual` for an operator (the `once` profile, `wbparser crawl`). Rows written
    before the column was filled say `—`, and that is the honest answer for them.

    The next run is a *tick* that will find work. A tick with nothing due starts
    nothing, so the estimate takes the later of the next tick and the moment the
    earliest target's freshness expires — a next tick on its own would promise a run
    that does not happen.
    """
    jobs = _get("/api/admin/jobs", list[Job], admin=True, limit=limit)
    next_run, due_estimate = _next_run()

    _emit(
        {
            "runs": [job.model_dump(mode="json", by_alias=True) for job in jobs],
            "schedule": {
                "cron": _effective("CRAWLER_SCHEDULE_CRON", "0 0 */2 * * *"),
                "freshnessMinutes": int(_effective("CRAWLER_SCHEDULE_FRESHNESS_MINUTES", "360")),
                "nextRun": next_run,
                "earliestDue": due_estimate,
            },
        },
        lambda: _print_history(jobs, next_run, due_estimate),
    )


def _next_run() -> tuple[datetime | None, datetime | None]:
    """
    When a tick will start a crawl, and when the earliest target becomes due.

    The two are separate facts and the answer is the later of them: a tick that finds
    nothing due is a no-op, so "the next tick" alone overstates what will happen.
    """
    cron = _effective("CRAWLER_SCHEDULE_CRON", "0 0 */2 * * *")
    freshness = int(_effective("CRAWLER_SCHEDULE_FRESHNESS_MINUTES", "360"))
    now = datetime.now(UTC)

    try:
        earliest_due = _earliest_due(freshness, now) if _running("app") else None
    except Unusable:
        earliest_due = None

    # Spring's cron has seconds first; croniter can read that layout, and `?` means
    # "no specific value", which croniter spells `*`.
    try:
        iterator = croniter(cron.replace("?", "*"), now, second_at_beginning=True)
    except (ValueError, KeyError):
        return None, earliest_due

    tick = iterator.get_next(datetime)
    if earliest_due is None or earliest_due <= tick:
        return tick, earliest_due
    # Nothing is due at the next tick: walk forward to the first tick that finds work.
    return iterator.get_next(datetime, start_time=earliest_due), earliest_due


def _earliest_due(freshness_minutes: int, now: datetime) -> datetime | None:
    """When the earliest active target becomes claimable, or None when nothing is active."""
    targets = _get("/api/admin/targets", list[Target], admin=True)
    moments: list[datetime] = []
    for target in targets:
        if not target.active:
            continue
        candidate = now
        if target.next_attempt_at and target.next_attempt_at > candidate:
            candidate = target.next_attempt_at
        if target.last_crawled_at:
            fresh_until = target.last_crawled_at + timedelta(minutes=freshness_minutes)
            if fresh_until > candidate:
                candidate = fresh_until
        moments.append(candidate)
    return min(moments) if moments else None


def _print_history(jobs: list[Job], next_run: datetime | None, earliest_due: datetime | None) -> None:
    cron = _effective("CRAWLER_SCHEDULE_CRON", "0 0 */2 * * *")
    freshness = _effective("CRAWLER_SCHEDULE_FRESHNESS_MINUTES", "360")

    _headline("schedule")
    console.print(f"  tick       {escape(cron)}  [dim](how often the scheduler looks for work)[/dim]")
    console.print(f"  freshness  {freshness} min  [dim](how long a target rests after a crawl)[/dim]")
    if next_run:
        console.print(f"  next run   {next_run.astimezone().strftime('%Y-%m-%d %H:%M:%S %Z')}  ({_age(next_run)})")
    else:
        console.print("  next run   [yellow]unknown[/yellow] — the cron expression does not parse")
    if earliest_due:
        console.print(f"  [dim]the earliest target is due {_age(earliest_due)}[/dim]")

    _headline(f"runs — last {len(jobs)}")
    if not jobs:
        console.print("  [yellow]![/yellow] no runs recorded yet")
        return
    _table(
        "",
        ["started", "finished", "trigger", "status", "pages", "items", "error"],
        [
            [
                _age(job.started_at),
                _duration(job),
                job.triggered_by or "—",
                job.status,
                job.pages_crawled,
                job.items_saved,
                job.error_message,
            ]
            for job in jobs
        ],
    )


def _duration(job: Job) -> str:
    """How long the run took — or that it is still going, which is not a duration."""
    if job.completed_at is None:
        return "running" if job.status == "Running" else "—"
    if job.started_at is None:
        return "—"
    seconds = int((job.completed_at - job.started_at).total_seconds())
    return _span(max(0, seconds))


@app.command(name="help")
def help_all() -> None:
    """Show the help of every command."""
    group = typer.main.get_command(app)
    root = click.Context(group, info_name="wbparser")
    for name, command in group.commands.items():
        console.print()
        console.rule(f"[bold]{name}[/bold]", align="left")
        console.print(command.get_help(click.Context(command, info_name=name, parent=root)).rstrip())


@app.command()
def logs(
    follow: Annotated[bool, typer.Option("--follow", "-f", help="keep watching")] = False,
    lines: Annotated[int, typer.Option("--lines", "-n", help="how many lines to start from")] = 100,
) -> None:
    """
    The application log. Not data, so not the API: `docker compose logs` is where it lives.

    Filtering is `wbparser logs -f | grep …` rather than a flag. A `--grep` cannot work
    with `--follow`: reading a captured stream means nothing is printed until the
    container stops, and a regex flag that silently does nothing is worse than none.
    """
    if not _running("app"):
        raise Unusable("the application is not running — start it with `wbparser up`")
    _compose("logs", *(["--follow"] if follow else []), "--tail", str(lines), "app", live=True)


def _dispatch() -> None:
    """Run the app, and turn a missing precondition into a message and an exit code."""
    try:
        app()
    except Unusable as error:
        err_console.print(f"[red]error:[/red] {escape(str(error))}")
        sys.exit(EXIT_UNUSABLE)
    except httpx.HTTPError as error:
        err_console.print(f"[red]error:[/red] {escape(str(error))}")
        sys.exit(EXIT_UNUSABLE)
    except KeyboardInterrupt:
        sys.exit(130)


if __name__ == "__main__":
    _dispatch()

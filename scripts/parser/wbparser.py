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

The catalog's shapes are generated from `contract/catalog-v1.openapi.yaml` into
`catalog_models.py` (`just parser::models`); the admin shapes stay hand-written below,
because the contract describes the catalog and nothing else. Validating the catalog
against the generated model is what makes a contract drift a message naming the field
instead of a table cell rendered as "—".

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
from dataclasses import astuple, dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from enum import StrEnum
from functools import cache
from pathlib import Path
from typing import Annotated, Any, TypeVar

try:
    import click
    import httpx
    import simplejson
    import typer

    # The catalog half of the responses — generated, not hand-written, so a change to
    # the contract reaches this client as a file that no longer matches the payloads.
    # `just parser::models` regenerates it from `contract/catalog-v1.openapi.yaml`.
    from catalog_models import PagedProductCard as ItemsPage
    from catalog_models import ProductCard
    from catalog_models import SiteStats as Stats
    from dotenv import dotenv_values
    from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, ValidationError
    from pydantic.alias_generators import to_camel
    from rich.console import Console
    from rich.markup import escape
    from rich.table import Table
    from rich.text import Text
    from textual import on, work
    from textual.app import App, ComposeResult
    from textual.binding import Binding
    from textual.containers import Horizontal, Vertical
    from textual.widgets import (
        Button,
        DataTable,
        Footer,
        Header,
        Input,
        RichLog,
        Select,
        Static,
        TabbedContent,
        TabPane,
    )
except ModuleNotFoundError as missing:  # pragma: no cover - a wrong interpreter, not a code path
    if missing.name == "catalog_models":
        raise SystemExit(
            "error: catalog_models.py is missing next to this script.\n"
            "It is generated from the contract, not installed — restore it with:\n"
            "  just parser::models"
        ) from missing
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


class Target(Camel):
    id: str
    name: str
    active: bool = True
    wb_catalog_id: str | None = None
    last_crawled_at: datetime | None = None
    next_attempt_at: datetime | None = None
    consecutive_failures: int = 0
    # Until when another run holds the target. A leased target is not claimable, so a
    # target that looks due can still be skipped by the crawl.
    lease_until: datetime | None = None


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


class Upcoming(Camel):
    at: datetime | None = None
    target: Target | None = None


class Schedule(Camel):
    """The schedule as the server answers it: the tick's rule, owned by the server."""

    cron: str = "0 0 */4 * * *"
    freshness_minutes: int = 360
    active_targets: int = 0
    targets: list[Target] = Field(default_factory=list)
    due: list[Target] = Field(default_factory=list)
    upcoming: list[Upcoming] = Field(default_factory=list)
    next_run: datetime | None = None
    earliest_due: datetime | None = None
    limit: int = 15


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
    """Explicit WBPARSER_URL wins; otherwise the port follows the same precedence as credentials."""
    port = _env().get("SERVER_PORT") or str(DEFAULT_PORT)
    return os.environ.get("WBPARSER_URL") or f"http://localhost:{port}"


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


def _reset_caches() -> None:
    """Drop the memoised answers: the stack may have just been started or stopped."""
    _file_env.cache_clear()
    _env.cache_clear()
    _base_url.cache_clear()
    _client.cache_clear()


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

    # The catalog is the one place the contract promises decimals, and a JSON fraction
    # parsed as a float has already lost its value before a `Decimal` field sees it.
    payload = _decode_json(response.content, decimal_numbers=catalog)
    if model is None:
        # A shape this file does not own: `/api/health/ready` reports whatever the
        # crawler has counters for, and a model would freeze that into a contract the
        # server never agreed to. `status` prints it as it comes.
        return payload
    try:
        return TypeAdapter(model).validate_python(payload)
    except ValidationError as error:
        raise Unusable(f"{path}: the response does not match the model — has the API changed?\n{error}") from error


def _decode_json(body: bytes, *, decimal_numbers: bool = False) -> Any:
    """
    Decode a response body. `decimal_numbers` keeps catalog fractions exact.

    The stdlib decoder turns every JSON fraction into a float first, and a float cannot
    hold `0.1000000000000000000000000001`. The catalog is the only API whose contract
    promises decimals, so only that path asks for `Decimal`.
    """
    return json.loads(body, parse_float=Decimal) if decimal_numbers else json.loads(body)


def _jsonable(value: Any) -> Any:
    """Convert a response value into JSON-compatible Python data.

    Nested Pydantic models become dicts, Decimals stay as numbers, and datetimes become
    ISO strings — so `--json` emits objects where the API returns objects, not `str(...)`
    fallbacks that only look right until a consumer reads them.
    """
    if isinstance(value, BaseModel):
        # Python mode keeps Decimal and datetime as values we can normalize below; JSON
        # mode would stringify Decimals, which must remain numbers in the output.
        return _jsonable(value.model_dump(by_alias=True))
    if isinstance(value, dict):
        return {key: _jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(item) for item in value]
    if isinstance(value, Decimal):
        return value
    if isinstance(value, datetime):
        return value.isoformat().replace("+00:00", "Z")
    if isinstance(value, StrEnum):
        return value.value
    if isinstance(value, Path):
        return str(value)
    return value


def _encode_json(payload: Any) -> str:
    """
    One JSON document, with money as JSON numbers rather than strings.

    `Decimal` has no stdlib `json` support, and Pydantic's JSON mode writes it as a
    quoted string. `simplejson` writes the exact decimal literal unquoted, so `--json`
    keeps the shape the API itself sends.
    """
    return simplejson.dumps(
        _jsonable(payload),
        ensure_ascii=False,
        indent=2,
        use_decimal=True,
        default=str,
    )


def _emit(payload: Any, render: Any) -> None:
    """One code path for `--json` and for the human table: the caller says what to draw."""
    if JSON:
        sys.stdout.write(_encode_json(payload) + "\n")
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


def _price(item: ProductCard) -> str:
    kopecks = item.sale_price_kopecks if item.sale_price_kopecks is not None else item.price_kopecks
    return _rubles(kopecks)


def _cashback(item: ProductCard) -> str:
    return f"{item.cashback_percent}%" if item.cashback_percent is not None else "—"


def _duration(job: Job) -> str:
    """How long the run took — or that it is still going, which is not a duration."""
    if job.completed_at is None:
        return "running" if job.status == "Running" else "—"
    if job.started_at is None:
        return "—"
    seconds = int((job.completed_at - job.started_at).total_seconds())
    return _span(max(0, seconds))


def _cell(value: Any) -> Text:
    """A table cell: the value, or an em dash when it is empty. Cells are data, not markup."""
    return Text("—" if value is None or value == "" else str(value))


def _table(title: str, columns: list[str], rows: list[list[Any]]) -> None:
    """A table whose cells are data, not markup."""
    table = Table(title=title, title_justify="left", header_style="bold")
    for column in columns:
        table.add_column(column, overflow="ellipsis")
    for row in rows:
        table.add_row(*(_cell(cell) for cell in row))
    console.print(table)


def _mark(ok: bool) -> str:
    return "[green]✓[/green]" if ok else "[red]✗[/red]"


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


# ── sections ──────────────────────────────────────────────────────────────────


@dataclass
class Section:
    """One labelled block of tabular data: the title, the columns, and the rows to put in a table."""

    title: str
    columns: list[str]
    rows: list[list[Any]]


def items_section(page: ItemsPage) -> Section:
    """The products of one page, as rows a renderer turns into a table."""
    return Section(
        f"products — page {page.page + 1}/{max(1, page.total_pages)}, {page.total_count} total",
        ["target", "title", "price", "cashback", "scraped"],
        [
            [
                item.target_name,
                item.title,
                _price(item),
                _cashback(item),
                _age(item.scraped_at),
            ]
            for item in page.items
        ],
    )


def runs_sections(data: dict[str, Any]) -> list[Section]:
    """The tables of the runs screen: target runs, jobs, and unresolved errors."""
    return [
        Section(
            f"target runs — last {len(data['targetRuns'])}",
            ["target", "group", "address", "outcome", "pages", "items", "wb total", "when"],
            [
                [
                    run.target_name or (run.target_id[:8] if run.target_id else None),
                    run.egress_group,
                    run.egress_ip,
                    run.outcome,
                    run.pages,
                    run.items,
                    run.wb_total,
                    _age(run.started_at),
                ]
                for run in data["targetRuns"]
            ],
        ),
        Section(
            f"jobs — last {len(data['jobs'])}",
            ["status", "pages", "items", "started", "error"],
            [
                [job.status, job.pages_crawled, job.items_saved, _age(job.started_at), job.error_message]
                for job in data["jobs"]
            ],
        ),
        Section(
            f"unresolved errors — {len(data['errors'])}",
            ["category", "message", "when"],
            [[e.category, e.message, _age(e.created_at)] for e in data["errors"]],
        ),
    ]


def schedule_sections(data: Schedule) -> list[Section]:
    """The tables of the schedule screen: what is due and what is upcoming."""
    limit = int(data.limit or 15)
    return [
        Section(
            f"due now — first {min(limit, len(data.due))}",
            ["target", "wb id", "last crawled", "fails"],
            [[t.name, t.wb_catalog_id, _age(t.last_crawled_at), t.consecutive_failures] for t in data.due[:limit]],
        ),
        Section(
            f"next due — first {min(limit, len(data.upcoming))}",
            ["target", "when"],
            [[item.target.name, _age(item.at)] for item in data.upcoming if item.target is not None][:limit],
        ),
    ]


def history_section(jobs: list[Job]) -> Section:
    """The runs table of the history screen: what started each run and how it went."""
    return Section(
        f"runs — last {len(jobs)}",
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


# ── commands ─────────────────────────────────────────────────────────────────


app = typer.Typer(
    add_completion=False,
    no_args_is_help=False,
    invoke_without_command=True,
    rich_markup_mode="rich",
    help="Observe and drive the local crawler with a Textual TUI (default) or CLI subcommands.",
)


@app.callback(invoke_without_command=True)
def _global_options(
    ctx: typer.Context,
    json_output: Annotated[bool, typer.Option("--json", help="one JSON document on stdout, nothing else")] = False,
) -> None:
    """Global flags. They go before the command: `wbparser --json status`."""
    global JSON  # noqa: PLW0603 - one process, one run, one output mode
    JSON = json_output
    if ctx.invoked_subcommand is None:
        WbparserApp().run()


@app.command()
def up(
    build: Annotated[bool, typer.Option("--build", help="rebuild the jar even if one exists")] = False,
    timeout: Annotated[int, typer.Option(help="seconds to wait for readiness")] = 180,
) -> None:
    """Start the stack (Postgres + the application) and wait until it can serve."""
    _reset_caches()
    rebuilt = build or not JAR.is_file()
    if rebuilt:
        console.print("building the jar…")
        _run([str(ROOT / "gradlew"), ":app:bootJar", "--no-daemon"], live=True)
    else:
        console.print(f"[dim]reusing {JAR.relative_to(ROOT)} (rebuild with --build)[/dim]")

    console.print("starting the stack…")
    # When the jar was rebuilt, recreate the app container so it picks up the new
    # bind-mounted jar (the debug stack mounts the jar as a file, and a rebuilt jar
    # can leave the running JVM reading a stale/corrupt archive).
    up_args = ["up", "-d"]
    if rebuilt:
        up_args.extend(["--force-recreate", "app"])
    _compose(*up_args)

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if _get("/api/health/ready").get("status") == "UP":
                break
        except Unusable:
            pass
        time.sleep(2)
    else:
        console.print(f"{_mark(False)} the application did not become ready in {timeout}s")
        console.print("[dim]see what it said: wbparser logs -n 50[/dim]")
        raise typer.Exit(EXIT_WRONG)

    console.print(f"{_mark(True)} ready at {_base_url()}")
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
    _reset_caches()
    if volumes:
        console.print("[yellow]![/yellow] removing the database volume — every scraped row is lost")
    _compose("down", *(["-v"] if volumes else []))
    console.print(f"{_mark(True)} stopped" + (" (volume removed)" if volumes else " (volume kept)"))


def _fetch_status() -> tuple[dict[str, Any], dict[str, bool]]:
    ready = _get("/api/health/ready")
    keys = {"ADMIN_API_KEY": _key_works(admin=True), "CATALOG_API_KEY": _key_works(admin=False)}
    return ready, keys


@app.command()
def status() -> None:
    """Health, readiness, credentials, crawl counters, and the egress groups."""
    ready, keys = _fetch_status()
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
    console.print(f"{_mark(healthy)} application at {_base_url()}: {'UP' if healthy else 'DOWN'}")

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
        console.print(f"  {_mark(works)} {name}: {'accepted' if works else 'refused or missing'}")


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
    mark = _mark(completed.returncode == EXIT_OK)
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


def _fetch_items(
    *,
    target: str | None = None,
    query: str | None = None,
    page: int = 0,
    page_size: int = 20,
    sort: str = Sort.date_desc.value,
    in_stock: bool = False,
    min_price: float | None = None,
    max_price: float | None = None,
    min_cashback: float | None = None,
) -> ItemsPage:
    return _get(
        "/api/catalog/v1/items",
        ItemsPage,
        catalog=True,
        targetId=target,
        q=query,
        sort=sort,
        page=page,
        pageSize=page_size,
        inStock="true" if in_stock else None,
        minPrice=min_price,
        maxPrice=max_price,
        minCashback=min_cashback,
    )


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
    result = _fetch_items(
        target=target,
        query=query,
        page=page,
        page_size=page_size,
        sort=sort.value,
        in_stock=in_stock,
        min_price=min_price,
        max_price=max_price,
        min_cashback=min_cashback,
    )
    _emit(result, lambda: _print_items(result))
    if not result.total_count:
        raise typer.Exit(EXIT_WRONG)


def _print_items(result: ItemsPage) -> None:
    if not result.items:
        console.print("  [yellow]![/yellow] nothing matched")
        console.print("[dim]is the crawl done? try `wbparser status` and `wbparser runs`[/dim]")
        return
    _table(*astuple(items_section(result)))
    console.print(f"[dim]next page: wbparser items --page {result.page + 1}[/dim]")
    if result.data_as_of:
        console.print(f"[dim]the data on this page is {_age(result.data_as_of)}[/dim]")


def _fetch_stats() -> Stats:
    return _get("/api/catalog/v1/stats", Stats, catalog=True)


@app.command()
def stats() -> None:
    """Aggregate counters of the catalog: how big it is and how fresh."""
    payload = _get("/api/catalog/v1/stats", Stats, catalog=True)
    _emit(payload, lambda: _print_stats(payload))


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


def _fetch_runs(limit: int = 20, target: str | None = None, errors: int = 10) -> dict[str, Any]:
    jobs = _get("/api/admin/jobs", list[Job], admin=True, limit=limit, targetId=target)
    target_runs = _get("/api/admin/target-runs", list[TargetRun], admin=True, limit=limit, targetId=target)
    failures = _get("/api/admin/errors", list[RecordedError], admin=True, limit=errors, unresolvedOnly="true")
    return {"jobs": jobs, "targetRuns": target_runs, "errors": failures}


@app.command()
def runs(
    limit: Annotated[int, typer.Option(help="how many rows")] = 20,
    target: Annotated[str | None, typer.Option(help="a target UUID")] = None,
    errors: Annotated[int, typer.Option(help="how many unresolved errors to show")] = 10,
) -> None:
    """The journal of runs: what ran, on which address, and what came of it."""
    data = _fetch_runs(limit=limit, target=target, errors=errors)
    _emit(data, lambda: _print_runs(data))


def _print_runs(data: dict[str, Any]) -> None:
    sections = runs_sections(data)
    for section, empty in zip(sections, ("no runs recorded yet", "no jobs recorded yet", None), strict=True):
        _headline(str(section.title))
        if not section.rows:
            if empty is not None:
                console.print(f"  [yellow]![/yellow] {empty}")
            continue
        _table("", section.columns, section.rows)
    if data["targetRuns"]:
        refused = sum(1 for run in data["targetRuns"] if run.outcome == "antibot")
        if refused:
            console.print(
                f"  [yellow]![/yellow] {refused} of {len(data['targetRuns'])} runs were refused by WB"
                f" — that is the address, not the target"
            )


def _fetch_schedule(limit: int = 15) -> Schedule:
    """The schedule as the server answers it: the tick's rule, owned by the server."""
    try:
        return _get("/api/admin/schedule", Schedule, admin=True, limit=limit)
    except Unusable as error:
        if "HTTP 404 on /api/admin/schedule" in str(error):
            raise Unusable(
                "HTTP 404 on /api/admin/schedule — the running application does not serve this endpoint. "
                "Rebuild and restart it: `wbparser up --build` (or rebuild the jar and recreate the app container)."
            ) from error
        raise


@app.command()
def schedule(
    limit: Annotated[int, typer.Option(help="how many targets to list")] = 15,
) -> None:
    """
    The tick, the freshness window, and which targets are due.

    The answer comes from the server: the `/api/admin/schedule` endpoint reads the same
    claim the scheduler takes, so what is due here is what the tick will act on.
    """
    data = _fetch_schedule(limit=limit)
    _emit(
        {
            "cron": data.cron,
            "freshnessMinutes": data.freshness_minutes,
            "activeTargets": data.active_targets,
            "due": data.due,
            "upcoming": [{"at": item.at, "target": item.target} for item in data.upcoming],
            "nextRun": data.next_run,
            "earliestDue": data.earliest_due,
        },
        lambda: _print_schedule(data),
    )


def _print_schedule_header(cron: str, freshness: int) -> None:
    _headline("schedule")
    console.print(f"  tick       {escape(cron)}  [dim](how often the scheduler looks for work)[/dim]")
    console.print(f"  freshness  {freshness} min  [dim](how long a target rests after a crawl)[/dim]")
    console.print("  [dim]the tick is not the cadence: a tick that finds nothing due starts nothing[/dim]")


def _partition_by_due(
    targets: list[Target], now: datetime, freshness_minutes: int
) -> tuple[list[Target], list[tuple[datetime, Target]]]:
    """Split by the claim's rule, as far as the API exposes it — see [schedule]."""
    due: list[Target] = []
    upcoming: list[tuple[datetime, Target]] = []
    for target in targets:
        if not target.active:
            continue
        available_at = now
        if target.lease_until and target.lease_until > available_at:
            available_at = target.lease_until
        if target.next_attempt_at and target.next_attempt_at > available_at:
            available_at = target.next_attempt_at
        if target.last_crawled_at is not None:
            fresh_until = target.last_crawled_at + timedelta(minutes=freshness_minutes)
            if fresh_until > available_at:
                available_at = fresh_until
        if available_at > now:
            upcoming.append((available_at, target))
        else:
            due.append(target)
    return due, sorted(upcoming, key=lambda pair: pair[0])


def _print_schedule(
    cron: str,
    freshness: int,
    leaseMinutes: int,
    targets: list[Target],
    due: list[Target],
    upcoming: list[tuple[datetime, Target]],
    limit: int,
) -> None:
    _headline("schedule")
    console.print(f"  tick       {escape(cron)}  [dim](how often the scheduler looks for work)[/dim]")
    console.print(f"  freshness  {freshness} min  [dim](how long a target rests after a crawl)[/dim]")
    console.print("  [dim]the tick is not the cadence: a tick that finds nothing due starts nothing[/dim]")
    console.print(f"\n  active targets  {sum(1 for target in targets if target.active)} of {len(targets)}")
    console.print(f"  due now         {len(due)}")
    if due:
        console.print("  [dim]a target held by an interrupted run's lease is listed here and[/dim]")
        console.print(f"  [dim]skipped by the crawl until that lease expires ({leaseMinutes} min)[/dim]")


def _print_next_run(next_run: datetime | None, earliest_due: datetime | None) -> None:
    if next_run:
        console.print(f"  next run   {next_run.astimezone().strftime('%Y-%m-%d %H:%M:%S %Z')}  ({_age(next_run)})")
    else:
        console.print("  next run   [yellow]unknown[/yellow]")
    if earliest_due:
        console.print(f"  [dim]the earliest target is due {_age(earliest_due)}[/dim]")


def _print_schedule(data: Schedule) -> None:
    _print_schedule_header(data.cron, int(data.freshness_minutes))
    _print_next_run(data.next_run, data.earliest_due)
    console.print(f"\n  active targets  {sum(1 for t in data.targets if t.active)} of {len(data.targets)}")
    console.print(f"  due now         {len(data.due)}")
    for section in schedule_sections(data):
        _headline(str(section.title))
        if not section.rows:
            continue
        _table("", section.columns, section.rows)


def _fetch_history(limit: int = 15) -> dict[str, Any]:
    jobs = _get("/api/admin/jobs", list[Job], admin=True, limit=limit)
    schedule = _fetch_schedule()
    return {
        "jobs": jobs,
        "cron": schedule.cron,
        "freshnessMinutes": schedule.freshness_minutes,
        "nextRun": schedule.next_run,
        "earliestDue": schedule.earliest_due,
    }


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
    data = _fetch_history(limit=limit)
    _emit(
        {
            "runs": data["jobs"],
            "schedule": {
                "cron": data["cron"],
                "freshnessMinutes": data["freshnessMinutes"],
                "nextRun": data["nextRun"],
                "earliestDue": data["earliestDue"],
            },
        },
        lambda: _print_history(
            data["cron"], data["freshnessMinutes"], data["jobs"], data["nextRun"], data["earliestDue"]
        ),
    )


def _print_history(
    cron: str,
    freshness: int,
    jobs: list[Job],
    next_run: datetime | None,
    earliest_due: datetime | None,
) -> None:
    _print_schedule_header(cron, freshness)
    _print_next_run(next_run, earliest_due)
    _headline(f"runs — last {len(jobs)}")
    if not jobs:
        console.print("  [yellow]![/yellow] no runs recorded yet")
        return
    section = history_section(jobs)
    _table("", section.columns, section.rows)


# ── TUI ─────────────────────────────────────────────────────────────────────


def fill(table: DataTable, section: Section) -> None:
    """One common path from a section to a Textual table: clear, add columns, add rows."""
    table.clear(columns=True)
    table.add_columns(*section.columns)
    for row in section.rows:
        table.add_row(*(_cell(cell) for cell in row))


class WbparserApp(App):
    # Enable mouse text selection and clipboard copy (Ctrl+C / Cmd+C) in supported widgets.
    ALLOW_SELECT = True

    TITLE = "wbparser"
    SUB_TITLE = "crawler console"
    CSS = """
    Screen { layout: vertical; }
    #status-summary { height: auto; padding: 0 1; }
    .pane { height: 1fr; }
    .box { border: round $primary; padding: 0 1; margin: 0 0 1 0; height: auto; }
    .title { text-style: bold; color: $accent; margin-bottom: 0; }
    .row { height: auto; }
    .row > * { margin-right: 1; }
    DataTable { height: 1fr; }
    #items-table, #runs-targets, #runs-jobs, #runs-errors, #sched-due, #sched-upcoming, #history-table { height: 1fr; min-height: 8; }
    #items-controls Input { width: 24; }
    #items-controls Select { width: 22; }
    #items-controls .small { width: 8; }
    #status-actions Button { margin-right: 1; }
    #activity-log, #logs-view { height: 1fr; border: round $surface; }
    #stats-view { padding: 1 2; }
    .ok { color: $success; }
    .bad { color: $error; }
    .warn { color: $warning; }
    #msg { height: auto; padding: 0 1; color: $text-muted; }
    """

    BINDINGS = [
        Binding("q", "quit", "Quit"),
        Binding("r", "refresh", "Refresh"),
        Binding("1", "tab('tab-status')", "Status", show=False),
        Binding("2", "tab('tab-items')", "Items", show=False),
        Binding("3", "tab('tab-runs')", "Runs", show=False),
        Binding("4", "tab('tab-schedule')", "Schedule", show=False),
        Binding("5", "tab('tab-history')", "History", show=False),
        Binding("6", "tab('tab-stats')", "Stats", show=False),
        Binding("7", "tab('tab-logs')", "Logs", show=False),
    ]

    def compose(self) -> ComposeResult:
        yield Header(show_clock=True)
        with TabbedContent(initial="tab-status", id="tabs"):
            with TabPane("Status", id="tab-status"), Vertical(classes="pane"):
                with Horizontal(id="status-actions", classes="row"):
                    yield Button("Refresh", id="btn-refresh-status", variant="primary")
                    yield Button("Up", id="btn-up")
                    yield Button("Down", id="btn-down", variant="warning")
                    yield Button("Crawl once", id="btn-crawl")
                yield Static("Loading…", id="status-summary", classes="box")
                with Horizontal(classes="row"):
                    with Vertical(classes="box", id="box-checks"):
                        yield Static("Checks", classes="title")
                        yield DataTable(id="status-checks")
                    with Vertical(classes="box", id="box-creds"):
                        yield Static("Credentials", classes="title")
                        yield DataTable(id="status-creds")
                with Vertical(classes="box"):
                    yield Static("Crawl & egress", classes="title")
                    yield Static("", id="status-crawl")
                    yield DataTable(id="status-egress")
                with Vertical(classes="box"):
                    yield Static("Activity", classes="title")
                    yield RichLog(id="activity-log", highlight=True, markup=True, wrap=True)

            with TabPane("Items", id="tab-items"), Vertical(classes="pane"):
                with Horizontal(id="items-controls", classes="row"):
                    yield Input(placeholder="query (-q)", id="items-q")
                    yield Input(placeholder="target UUID", id="items-target")
                    yield Select(
                        [(s.value, s.value) for s in Sort],
                        value=Sort.date_desc.value,
                        allow_blank=False,
                        id="items-sort",
                    )
                    yield Input(placeholder="page", value="0", id="items-page", classes="small")
                    yield Input(placeholder="size", value="20", id="items-size", classes="small")
                    yield Button("Load", id="btn-items", variant="primary")
                yield Static("", id="items-info")
                yield DataTable(id="items-table")

            with TabPane("Runs", id="tab-runs"), Vertical(classes="pane"):
                with Horizontal(classes="row"):
                    yield Button("Refresh", id="btn-runs", variant="primary")
                yield Static("Target runs", classes="title")
                yield DataTable(id="runs-targets")
                yield Static("Jobs", classes="title")
                yield DataTable(id="runs-jobs")
                yield Static("Unresolved errors", classes="title")
                yield DataTable(id="runs-errors")

            with TabPane("Schedule", id="tab-schedule"), Vertical(classes="pane"):
                with Horizontal(classes="row"):
                    yield Button("Refresh", id="btn-schedule", variant="primary")
                yield Static("", id="schedule-summary", classes="box")
                yield Static("Due now", classes="title")
                yield DataTable(id="sched-due")
                yield Static("Upcoming", classes="title")
                yield DataTable(id="sched-upcoming")

            with TabPane("History", id="tab-history"), Vertical(classes="pane"):
                with Horizontal(classes="row"):
                    yield Button("Refresh", id="btn-history", variant="primary")
                yield Static("", id="history-summary", classes="box")
                yield DataTable(id="history-table")

            with TabPane("Stats", id="tab-stats"), Vertical(classes="pane"):
                with Horizontal(classes="row"):
                    yield Button("Refresh", id="btn-stats", variant="primary")
                yield DataTable(id="stats-table")

            with TabPane("Logs", id="tab-logs"), Vertical(classes="pane"):
                with Horizontal(classes="row"):
                    yield Input(placeholder="lines", value="150", id="logs-lines", classes="small")
                    yield Button("Load", id="btn-logs", variant="primary")
                    yield Button("Follow", id="btn-logs-follow")
                    yield Button("Stop", id="btn-logs-stop", variant="warning")
                yield RichLog(id="logs-view", highlight=True, markup=False, wrap=True)
        yield Static("", id="msg")
        yield Footer()

    def on_mount(self) -> None:
        self._follow_proc: subprocess.Popen[str] | None = None
        self._init_tables()
        self.action_refresh()

    def _init_tables(self) -> None:
        self.query_one("#status-checks", DataTable).add_columns("check", "value")
        self.query_one("#status-creds", DataTable).add_columns("key", "status")
        self.query_one("#status-egress", DataTable).add_columns("group", "address", "health", "isolated", "refusals")
        self.query_one("#stats-table", DataTable).add_columns("metric", "value")

    def action_tab(self, tab_id: str) -> None:
        self.query_one("#tabs", TabbedContent).active = tab_id

    def action_refresh(self) -> None:
        tab = self.query_one("#tabs", TabbedContent).active
        self._refresh_tab(tab)

    def _refresh_tab(self, tab: str) -> None:
        self.query_one("#msg", Static).update("")
        if tab == "tab-status":
            self.load_status()
        elif tab == "tab-items":
            self.load_items()
        elif tab == "tab-runs":
            self.load_runs()
        elif tab == "tab-schedule":
            self.load_schedule()
        elif tab == "tab-history":
            self.load_history()
        elif tab == "tab-stats":
            self.load_stats()
        elif tab == "tab-logs":
            self.load_logs()

    @on(TabbedContent.TabActivated)
    def _on_tab(self, event: TabbedContent.TabActivated) -> None:
        if event.tabbed_content.id == "tabs":
            self._refresh_tab(event.pane.id or "")

    @on(Button.Pressed)
    def _on_button(self, event: Button.Pressed) -> None:
        bid = event.button.id or ""
        if bid == "btn-refresh-status":
            self.load_status()
        elif bid == "btn-up":
            self.run_app_action("up")
        elif bid == "btn-down":
            self.run_app_action("down")
        elif bid == "btn-crawl":
            self.run_app_action("crawl")
        elif bid == "btn-items":
            self.load_items()
        elif bid == "btn-runs":
            self.load_runs()
        elif bid == "btn-schedule":
            self.load_schedule()
        elif bid == "btn-history":
            self.load_history()
        elif bid == "btn-stats":
            self.load_stats()
        elif bid == "btn-logs":
            self.load_logs()
        elif bid == "btn-logs-follow":
            self.follow_logs()
        elif bid == "btn-logs-stop":
            self.stop_follow()

    def _note(self, text: str) -> None:
        self.query_one("#msg", Static).update(text)

    def _along(self, text: str) -> None:
        self.query_one("#activity-log", RichLog).write(text)

    @work(thread=True, exclusive=True, group="status")
    def load_status(self) -> None:
        self.call_from_thread(self._note, "Loading status…")
        try:
            ready, keys = _fetch_status()
            self.call_from_thread(self._render_status, ready, keys, None)
        except Exception as exc:  # noqa: BLE001
            self.call_from_thread(self._render_status, None, None, str(exc))

    def _render_status(self, ready: dict[str, Any] | None, keys: dict[str, bool] | None, err: str | None) -> None:
        self._note("")
        if err:
            self.query_one("#status-summary", Static).update(f"[bold red]Unavailable:[/bold red] {escape(err)}")
            for tid in ("#status-checks", "#status-creds", "#status-egress"):
                self.query_one(tid, DataTable).clear()
            self.query_one("#status-crawl", Static).update("")
            return
        assert ready is not None and keys is not None
        healthy = ready.get("status") == "UP"
        mark = "[green]UP[/green]" if healthy else "[red]DOWN[/red]"
        self.query_one("#status-summary", Static).update(f"Application at [bold]{escape(_base_url())}[/bold]: {mark}")
        t = self.query_one("#status-checks", DataTable)
        t.clear()
        for name, value in (ready.get("checks") or {}).items():
            ok = str(value).startswith("UP")
            t.add_row(_cell(name), Text(str(value), style="green" if ok else "yellow"))
        t = self.query_one("#status-creds", DataTable)
        t.clear()
        for name, works in keys.items():
            t.add_row(
                _cell(name), Text("accepted", style="green") if works else Text("refused or missing", style="red")
            )
        crawler = ready.get("crawler") or {}
        if crawler.get("state") == "not_started":
            crawl_text = "[yellow]not started — no counters yet[/yellow]"
        else:
            crawl_text = (
                f"requests {crawler.get('requests', 0)}  refusals {crawler.get('antibot_responses', 0)}  "
                f"share {_cell(crawler.get('refusal_share', 'n/a'))}  |  "
                f"pages {crawler.get('pages', 0)}  items {crawler.get('items', 0)}  "
                f"stale tokens {crawler.get('presets_responses', 0)}"
            )
            if retries := crawler.get("retries"):
                crawl_text += "\nretries: " + ", ".join(f"{r}={c}" for r, c in retries.items())
        self.query_one("#status-crawl", Static).update(crawl_text)
        t = self.query_one("#status-egress", DataTable)
        t.clear()
        for g in (ready.get("egress") or {}).get("groups") or []:
            h = Text("healthy", style="green") if g.get("healthy") else Text("unhealthy", style="red")
            t.add_row(
                _cell(g.get("group")),
                _cell(g.get("address") or "unknown"),
                h,
                _cell(g.get("isolated")),
                _cell(g.get("refusal_rate")),
            )

    @work(thread=True, exclusive=True, group="items")
    def load_items(self) -> None:
        self.call_from_thread(self._note, "Loading items…")
        try:
            q = self.query_one("#items-q", Input).value or None
            target = self.query_one("#items-target", Input).value or None
            sort = self.query_one("#items-sort", Select).value
            try:
                page = int(self.query_one("#items-page", Input).value or "0")
            except ValueError:
                page = 0
            try:
                size = int(self.query_one("#items-size", Input).value or "20")
            except ValueError:
                size = 20
            result = _fetch_items(target=target, query=q, page=page, page_size=size, sort=str(sort))
            self.call_from_thread(self._render_items, result, None)
        except Exception as exc:  # noqa: BLE001
            self.call_from_thread(self._render_items, None, str(exc))

    def _render_items(self, result: ItemsPage | None, err: str | None) -> None:
        self._note("")
        t = self.query_one("#items-table", DataTable)
        if err:
            self.query_one("#items-info", Static).update(f"[red]{escape(err)}[/red]")
            return
        assert result is not None
        self.query_one("#items-info", Static).update(
            f"page {result.page + 1}/{max(1, result.total_pages)} — {result.total_count} total"
            + (f" (data {_age(result.data_as_of)})" if result.data_as_of else "")
        )
        fill(t, items_section(result))

    @work(thread=True, exclusive=True, group="runs")
    def load_runs(self) -> None:
        self.call_from_thread(self._note, "Loading runs…")
        try:
            data = _fetch_runs()
            self.call_from_thread(self._render_runs, data, None)
        except Exception as exc:  # noqa: BLE001
            self.call_from_thread(self._render_runs, None, str(exc))

    def _render_runs(self, data: dict[str, Any] | None, err: str | None) -> None:
        self._note("")
        for tid in ("#runs-targets", "#runs-jobs", "#runs-errors"):
            self.query_one(tid, DataTable).clear()
        if err:
            self._note(f"[red]{escape(err)}[/red]")
            return
        assert data is not None
        sections = runs_sections(data)
        fill(self.query_one("#runs-targets", DataTable), sections[0])
        fill(self.query_one("#runs-jobs", DataTable), sections[1])
        fill(self.query_one("#runs-errors", DataTable), sections[2])

    @work(thread=True, exclusive=True, group="schedule")
    def load_schedule(self) -> None:
        self.call_from_thread(self._note, "Loading schedule…")
        try:
            data = _fetch_schedule()
            self.call_from_thread(self._render_schedule, data, None)
        except Exception as exc:  # noqa: BLE001
            self.call_from_thread(self._render_schedule, None, str(exc))

    def _render_schedule(self, data: Schedule | None, err: str | None) -> None:
        self._note("")
        if err:
            self.query_one("#schedule-summary", Static).update(f"[red]{escape(err)}[/red]")
            return
        assert data is not None
        nr = data.next_run
        nr_text = (
            f"{nr.astimezone().strftime('%Y-%m-%d %H:%M:%S %Z')} ({_age(nr)})" if nr else "[yellow]unknown[/yellow]"
        )
        summary = (
            f"tick: {data.cron}  |  freshness: {data.freshness_minutes} min  |  "
            f"active: {data.active_targets}/{len(data.targets)}  |  due now: {len(data.due)}\n"
            f"next run: {nr_text}"
        )
        if data.earliest_due:
            summary += f"  |  earliest due {_age(data.earliest_due)}"
        if data.due:
            summary += "\n[yellow]a target held by an interrupted run's lease may appear due but be skipped[/yellow]"
        self.query_one("#schedule-summary", Static).update(summary)
        sections = schedule_sections(data)
        fill(self.query_one("#sched-due", DataTable), sections[0])
        fill(self.query_one("#sched-upcoming", DataTable), sections[1])

    @work(thread=True, exclusive=True, group="history")
    def load_history(self) -> None:
        self.call_from_thread(self._note, "Loading history…")
        try:
            data = _fetch_history()
            self.call_from_thread(self._render_history, data, None)
        except Exception as exc:  # noqa: BLE001
            self.call_from_thread(self._render_history, None, str(exc))

    def _render_history(self, data: dict[str, Any] | None, err: str | None) -> None:
        self._note("")
        if err:
            self.query_one("#history-summary", Static).update(f"[red]{escape(err)}[/red]")
            return
        assert data is not None
        nr = data["nextRun"]
        nr_text = (
            f"{nr.astimezone().strftime('%Y-%m-%d %H:%M:%S %Z')} ({_age(nr)})" if nr else "[yellow]unknown[/yellow]"
        )
        summary = f"tick: {data['cron']}  |  freshness: {data['freshnessMinutes']} min  |  next run: {nr_text}"
        if data.get("earliestDue"):
            summary += f"  |  earliest due {_age(data['earliestDue'])}"
        self.query_one("#history-summary", Static).update(summary)
        fill(self.query_one("#history-table", DataTable), history_section(data["jobs"]))

    @work(thread=True, exclusive=True, group="stats")
    def load_stats(self) -> None:
        self.call_from_thread(self._note, "Loading stats…")
        try:
            payload = _fetch_stats()
            self.call_from_thread(self._render_stats, payload, None)
        except Exception as exc:  # noqa: BLE001
            self.call_from_thread(self._render_stats, None, str(exc))

    def _render_stats(self, payload: Stats | None, err: str | None) -> None:
        self._note("")
        t = self.query_one("#stats-table", DataTable)
        t.clear()
        if err:
            self._note(f"[red]{escape(err)}[/red]")
            return
        assert payload is not None
        rows = [
            ("products", payload.total_products),
            ("observations", f"{payload.total_items} (one row per target × product × price)"),
            ("targets", payload.total_targets),
            ("with cashback", f"{payload.products_with_cashback} products / {payload.with_cashback} observations"),
            ("newest observation", _age(payload.newest_scraped_at)),
            ("counters computed", f"{_age(payload.as_of)} (cached up to ten minutes)"),
        ]
        for k, v in rows:
            t.add_row(_cell(k), _cell(v))

    @work(thread=True, exclusive=True, group="logs")
    def load_logs(self) -> None:
        try:
            n = int(self.query_one("#logs-lines", Input).value or "150")
        except ValueError:
            n = 150
        view = self.query_one("#logs-view", RichLog)
        if not _running("app"):
            self.call_from_thread(view.write, "app is not running — start it with Up")
            return
        proc = subprocess.run(
            ["docker", "compose", "logs", "--tail", str(n), "app"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.call_from_thread(view.clear)
        self.call_from_thread(view.write, proc.stdout or proc.stderr or "(no output)")

    @work(thread=True, exclusive=True, group="follow")
    def follow_logs(self) -> None:
        self.stop_follow()
        if not _running("app"):
            self.call_from_thread(self.query_one("#logs-view", RichLog).write, "app is not running")
            return
        proc = subprocess.Popen(
            ["docker", "compose", "logs", "--follow", "--tail", "50", "app"],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        self._follow_proc = proc
        assert proc.stdout is not None
        try:
            for line in proc.stdout:
                self.call_from_thread(self.query_one("#logs-view", RichLog).write, Text.from_ansi(line))
        finally:
            proc.stdout.close()

    def stop_follow(self) -> None:
        proc = getattr(self, "_follow_proc", None)
        if proc and proc.poll() is None:
            proc.terminate()
        self._follow_proc = None

    @work(thread=True, exclusive=True, group="action")
    def run_app_action(self, action: str) -> None:
        log = self.query_one("#activity-log", RichLog)
        self.call_from_thread(log.write, f"[bold]▶ {action}[/bold]")
        try:
            if action == "up":
                self._action_up(log)
            elif action == "down":
                self._action_down(log)
            elif action == "crawl":
                self._action_crawl(log)
            self.call_from_thread(log.write, f"[green]✔ {action} finished[/green]")
        except Exception as exc:  # noqa: BLE001
            self.call_from_thread(log.write, f"[red]✖ {action}: {escape(str(exc))}[/red]")
        self.call_from_thread(self.load_status)

    def _stream_cmd(self, argv: list[str], log: RichLog, env: dict[str, str] | None = None) -> int:
        proc = subprocess.Popen(argv, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
        assert proc.stdout is not None
        for line in proc.stdout:
            self.call_from_thread(log.write, line.rstrip("\n"))
        return proc.wait()

    def _action_up(self, log: RichLog) -> None:
        _reset_caches()
        if not JAR.is_file():
            self.call_from_thread(log.write, "building jar…")
            rc = self._stream_cmd([str(ROOT / "gradlew"), ":app:bootJar", "--no-daemon"], log)
            if rc != 0:
                raise Unusable(f"jar build failed (exit {rc})")
        self.call_from_thread(log.write, "starting stack…")
        rc = self._stream_cmd(["docker", "compose", "up", "-d"], log)
        if rc != 0:
            raise Unusable(f"docker compose up failed (exit {rc})")
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            try:
                if _get("/api/health/ready").get("status") == "UP":
                    self.call_from_thread(log.write, f"ready at {_base_url()}")
                    return
            except Unusable:
                pass
            time.sleep(2)
        raise Unusable("application did not become ready in time")

    def _action_down(self, log: RichLog) -> None:
        _reset_caches()
        rc = self._stream_cmd(["docker", "compose", "down"], log)
        if rc != 0:
            raise Unusable(f"docker compose down failed (exit {rc})")

    def _action_crawl(self, log: RichLog) -> None:
        if not JAR.is_file():
            raise Unusable(f"{JAR.relative_to(ROOT)} is missing — run Up first")
        if not _running("postgres"):
            raise Unusable("database is not running — start it with Up")
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
        rc = self._stream_cmd(["java", "-jar", str(JAR), "--spring.profiles.active=once"], log, env=env)
        codes = {0: "succeeded", 1: "failed", 2: "lock held elsewhere", 3: "cancelled"}
        self.call_from_thread(log.write, f"crawl exit {rc}: {codes.get(rc, 'unexpected')}")
        if rc != 0:
            raise Unusable(f"crawl exit {rc}")


@app.command()
def tui() -> None:
    """Launch the interactive Textual TUI."""
    WbparserApp().run()


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

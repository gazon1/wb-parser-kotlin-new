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

import asyncio
import contextlib
import json
import os
import sys
import time
import webbrowser
from collections.abc import Awaitable, Callable
from dataclasses import astuple, dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from enum import StrEnum
from pathlib import Path
from typing import Annotated, Any, TypeVar

try:
    import click
    import httpx

    # The one way a child process is started in this tool: argv, no shell, and the
    # child's life owned by the call that started it. It knows nothing about Rich,
    # Textual or the crawler.
    import proc
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
    from textual import events, on, work
    from textual.app import App, ComposeResult
    from textual.binding import Binding
    from textual.containers import Horizontal, Vertical
    from textual.message import Message
    from textual.screen import ModalScreen
    from textual.widgets import (
        Button,
        Checkbox,
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
    from textual.worker import WorkerCancelled
except ModuleNotFoundError as missing:  # pragma: no cover - a wrong interpreter, not a code path
    if missing.name == "catalog_models":
        raise SystemExit(
            "error: catalog_models.py is missing next to this script.\n"
            "It is generated from the contract, not installed — restore it with:\n"
            "  just parser::models"
        ) from missing
    if missing.name == "proc":
        raise SystemExit(
            "error: proc.py is missing next to this script.\n"
            "It is the subprocess layer and it is not installed — restore it with:\n"
            "  git checkout scripts/parser/proc.py"
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


def _env() -> dict[str, str]:
    """
    The credentials this tool acts with: the shell, then the `.env` file.

    The file wins on purpose. It describes the stack these commands start and talk to;
    an inherited `DB_USER=…` from an unrelated shell session describes something else,
    and letting it win silently pointed a crawl at a database that did not have that
    user. A wrong value in the file is visible and editable; a stale exported one is
    neither.

    Nothing is memoised here: the file is read when it is needed, so a stack that was
    just started is described by the file as it is now.
    """
    file_env = {name: value for name, value in dotenv_values(ROOT / ".env").items() if value is not None}
    shell = {name: value for name, value in os.environ.items() if name in _OVERRIDABLE}
    return {**shell, **file_env}


def _base_url(env: dict[str, str] | None = None) -> str:
    """Explicit WBPARSER_URL wins; otherwise the port follows the same precedence as credentials."""
    if env is None:
        env = _env()
    port = env.get("SERVER_PORT") or str(DEFAULT_PORT)
    return os.environ.get("WBPARSER_URL") or f"http://localhost:{port}"


T = TypeVar("T")

# ── HTTP ─────────────────────────────────────────────────────────────────────


def _headers(role: str, env: dict[str, str]) -> dict[str, str]:
    """
    The headers of one role: its own key, and no other.

    The admin key must not travel to the catalog API even by accident, and the admin
    API refuses a request whose key is the catalog's. The public role carries no key
    at all, and a role nobody knows is an error rather than an anonymous request: a
    typo must not quietly drop the key.
    """
    if role == "public":
        return {}
    name = {"admin": "ADMIN_API_KEY", "catalog": "CATALOG_API_KEY"}.get(role)
    if name is None:
        raise Unusable(f"unknown HTTP role {role!r}")
    key = env.get(name, "")
    if not key:
        raise Unusable(f"{name} is not set — see docs/operations/credentials-runbook.md")
    return {"X-Api-Key": key}


class Api:
    """
    One scope of HTTP access: one client per role, closed with the scope.

    The clients live exactly as long as the `async with` block. So a key is read
    once per scope, no client is shared between two event loops, and no client is
    left open after the command that opened it.
    """

    def __init__(self) -> None:
        # The configuration is read once per scope: the URL and the keys of one scope
        # come from the same state of `.env`, even if the file changes meanwhile.
        self._env = _env()
        self._base_url = _base_url(self._env)
        self._clients: dict[str, httpx.AsyncClient] = {}

    def _client(self, role: str) -> httpx.AsyncClient:
        client = self._clients.get(role)
        if client is None:
            client = httpx.AsyncClient(base_url=self._base_url, headers=_headers(role, self._env), timeout=15.0)
            self._clients[role] = client
        return client

    async def aclose(self) -> None:
        """
        Close every client, even when one of them fails to close.

        The scope's contract is that no client outlives it, so one stubborn client
        cannot leave the others open. The failures are collected and raised together,
        so none of them is lost.
        """
        clients = list(self._clients.values())
        self._clients.clear()
        errors: list[Exception] = []
        for client in clients:
            try:
                await client.aclose()
            except Exception as error:
                errors.append(error)
        if errors:
            raise ExceptionGroup("closing the HTTP clients failed", errors)

    async def __aenter__(self) -> Api:
        return self

    async def __aexit__(self, exc_type: object, exc: BaseException | None, tb: object) -> None:
        try:
            await self.aclose()
        except ExceptionGroup as group:
            if exc is None:
                raise
            # The operation already failed: its error is the answer, and the cleanup
            # failure is a note on it rather than a replacement for it.
            for error in group.exceptions:
                exc.add_note(f"closing the HTTP clients failed: {error}")

    async def get(self, path: str, model: type[T] | None = None, *, role: str = "public", **params: Any) -> Any:
        """One GET, parsed into `model`, with the failure modes already translated."""
        query = {name: value for name, value in params.items() if value not in (None, "")}
        response = await self._send(path, query, role)
        # The catalog is the one place the contract promises decimals, and a JSON
        # fraction parsed as a float has already lost its value before a `Decimal`
        # field sees it.
        return _interpret(response, path, model, decimal_numbers=role == "catalog")

    async def _send(self, path: str, query: dict[str, Any], role: str) -> httpx.Response:
        """The request itself, with the transport failures translated around the await."""
        client = self._client(role)
        try:
            return await client.get(path, params=query)
        except httpx.ConnectError as error:
            raise Unusable(f"cannot reach {self._base_url} — start it with `wbparser up`") from error
        except (httpx.TimeoutException, httpx.RemoteProtocolError, httpx.ReadError) as error:
            # A server that accepts the connection and then hangs up is a server that is
            # starting or restarting. "Cannot reach" would be wrong the moment it boots.
            raise Unusable(f"{self._base_url} is not answering yet — is it still starting?") from error


def _interpret(
    response: httpx.Response,
    path: str,
    model: type[T] | None = None,
    *,
    decimal_numbers: bool = False,
) -> Any:
    """
    The response, checked and parsed: the status, the JSON, and the model.

    A caller never sees an `httpx` exception: a refused key says which key, and a
    response that does not match the model says so.
    """
    if response.status_code == 401:
        raise Unusable(f"the key was refused for {path} (401) — check the key in .env")
    if response.status_code == 403:
        raise Unusable(f"the key is missing or wrong for {path} (403)")
    if response.status_code >= 400:
        detail = response.text.strip()[:200]
        raise Unusable(f"HTTP {response.status_code} on {path}" + (f": {detail}" if detail else ""))

    try:
        payload = _decode_json(response.content, decimal_numbers=decimal_numbers)
    except ValueError as error:
        raise Unusable(f"{path}: the response is not JSON — {error}") from error
    if model is None:
        # A shape this file does not own: `/api/health/ready` reports whatever the
        # crawler has counters for, and a model would freeze that into a contract the
        # server never agreed to. `status` prints it as it comes.
        return payload
    try:
        return TypeAdapter(model).validate_python(payload)
    except ValidationError as error:
        raise Unusable(f"{path}: the response does not match the model — has the API changed?\n{error}") from error


async def _with_api(job: Callable[[Api], Awaitable[T]]) -> T:
    """One job, one scope: the clients live exactly as long as the job does."""
    async with Api() as api:
        return await job(api)


async def _gather(*jobs: Awaitable[Any]) -> list[Any]:
    """
    Independent requests, run together, and the first failure is the answer.

    Every request is awaited even when one of them has failed, so no request is left
    running after the scope closes; the first failure is then raised, so a failed
    request is never reported as a successful one.
    """
    results = await asyncio.gather(*jobs, return_exceptions=True)
    for result in results:
        if isinstance(result, BaseException):
            raise result
    return list(results)


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


def _points(item: ProductCard) -> str:
    """The WB points paid for a review — «Баллы ВБ за отзыв», as WB writes them."""
    return f"{item.feedback_points} Б" if item.feedback_points is not None else "—"


def _points_share(item: ProductCard) -> str:
    """The points as a share of the paid price — WB spends a point as a ruble."""
    kopecks = item.sale_price_kopecks if item.sale_price_kopecks is not None else item.price_kopecks
    if item.feedback_points is None or not kopecks:
        return "—"
    return f"{item.feedback_points * 10000 / kopecks:.1f}%"


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


def _number(value: str, fallback: int) -> int:
    """The number the user typed, or the fallback when it is not a number."""
    try:
        return int(value)
    except ValueError:
        return fallback


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


def _console_say(text: str) -> None:
    """Print one line of an action's output. The CLI half of the `say` callback."""
    console.print(text)


# ── the commands that start something ────────────────────────────────────────
#
# The argv of every child process this tool starts. They live in one place so
# that a rule — where compose looks for its file, which profile the crawl runs
# under — is written once.


def _gradle_command() -> list[str]:
    """The command that builds the jar."""
    return [str(ROOT / "gradlew"), ":app:bootJar", "--no-daemon"]


def _compose_command(*argv: str) -> list[str]:
    """The command that talks to the stack. Compose finds `docker-compose.yml` in the working directory."""
    return ["docker", "compose", *argv]


def _java_command() -> list[str]:
    """The command that runs exactly one crawl."""
    return ["java", "-jar", str(JAR), "--spring.profiles.active=once"]


def _jar_label() -> str:
    """The jar's path, relative to the repository when it sits inside it."""
    try:
        return str(JAR.relative_to(ROOT))
    except ValueError:
        return str(JAR)


# ── sections ──────────────────────────────────────────────────────────────────


@dataclass
class Section:
    """One labelled block of tabular data: the title, the columns, and the rows to put in a table."""

    title: str
    columns: list[str]
    rows: list[list[Any]]


# The fields of a product, in the order the table shows them. The order is also the
# order of the checkbox row, so one list describes both.
ITEM_COLUMNS = ["target", "title", "price", "cashback", "points", "points %", "scraped"]

# The operator's field choice, kept between runs next to the browser profiles.
COLUMNS_FILE = Path.home() / ".wbparser" / "items-columns.json"


def _saved_columns() -> list[str]:
    """The fields the operator chose last time; unknown names are dropped, all when none is left."""
    try:
        saved = json.loads(COLUMNS_FILE.read_text())
    except (OSError, ValueError):
        return list(ITEM_COLUMNS)
    # The file is the operator's to edit: a root that is not a list is a file to ignore,
    # not a reason for the terminal to fail on start.
    if not isinstance(saved, list):
        return list(ITEM_COLUMNS)
    kept = [name for name in ITEM_COLUMNS if name in saved]
    return kept or list(ITEM_COLUMNS)


def _save_columns(columns: list[str]) -> None:
    COLUMNS_FILE.parent.mkdir(parents=True, exist_ok=True)
    COLUMNS_FILE.write_text(json.dumps(columns))


def items_section(page: ItemsPage) -> Section:
    """The products of one page, as rows a renderer turns into a table."""
    return Section(
        f"products — page {page.page + 1}/{max(1, page.total_pages)}, {page.total_count} total",
        ITEM_COLUMNS,
        [
            [
                item.target_name,
                item.title,
                _price(item),
                _cashback(item),
                _points(item),
                _points_share(item),
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


# ── actions ──────────────────────────────────────────────────────────────────
#
# The three things an operator can do to the stack: up, down, crawl. The CLI and
# the TUI call the same functions, so the rules — how the jar is built, what the
# crawl's environment is, when the stack is ready — live in one place.
#
# An action reports through a callback and returns an exit code. The callback is
# the only difference between the two front ends: a Rich console in the CLI, a
# RichLog in the TUI.


async def _stream(say: Callable[[str], None], *argv: str, env: dict[str, str] | None = None) -> int:
    """
    Run a command and hand its output to the operator, line by line, as it arrives.

    The child's own output is escaped: a log line that looks like markup is a log
    line, not a style.
    """
    try:
        return await proc.stream(lambda line: say(escape(line)), *argv, env=env)
    except proc.ProcError as error:
        raise Unusable(str(error)) from error


async def _running(service: str) -> bool:
    """Whether a compose service is running right now."""
    try:
        result = await proc.sh(*_compose_command("ps", "--status", "running", "--services"))
    except proc.ProcError as error:
        raise Unusable(str(error)) from error
    return service in result.stdout.split()


async def _wait_ready(timeout: int) -> bool:
    """
    Wait until the application answers as ready. `False` when it did not in time.

    The polling lives inside one coroutine: the loop is the action's, and the
    clients it uses are closed when the action ends.
    """
    async with Api() as api:
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            try:
                if (await api.get("/api/health/ready")).get("status") == "UP":
                    return True
            except Unusable:
                pass
            # The wait ends at the deadline, not two seconds after it.
            await asyncio.sleep(min(2, remaining))


def _crawl_env() -> dict[str, str]:
    """
    The environment one crawl runs in.

    `env=` replaces the environment rather than extending it, so PATH, HOME and
    JAVA_HOME have to be carried over explicitly: without them `java` is found only
    because it happens to sit in /usr/bin, and a JDK-managed one is not found at all.
    """
    file_env = _env()
    env = {
        **os.environ,
        **file_env,
        "SPRING_PROFILES_ACTIVE": "once",
        "CAMOUFOX_HOME": file_env.get("CAMOUFOX_HOME") or str(Path.home() / ".cache" / "camoufox"),
        "PROFILES_DIR": file_env.get("PROFILES_DIR") or str(Path.home() / ".wbparser" / "profiles"),
    }
    env.setdefault("DB_HOST", "localhost")
    env.setdefault("DB_PORT", "5433")
    env.setdefault("DB_NAME", "wbparser")
    env.setdefault("DB_USER", "postgres")
    return env


async def action_up(say: Callable[[str], None], *, build: bool = False, timeout: int = 180) -> int:
    """Start the stack (Postgres + the application) and wait until it can serve."""
    rebuilt = build or not JAR.is_file()
    if rebuilt:
        say("building the jar…")
        if await _stream(say, *_gradle_command()) != 0:
            raise Unusable("the jar build failed")
    else:
        say(f"[dim]reusing {_jar_label()} (rebuild with --build)[/dim]")

    say("starting the stack…")
    # When the jar was rebuilt, recreate the app container so it picks up the new
    # bind-mounted jar (the debug stack mounts the jar as a file, and a rebuilt jar
    # can leave the running JVM reading a stale/corrupt archive).
    compose = _compose_command("up", "-d", *(["--force-recreate", "app"] if rebuilt else []))
    if await _stream(say, *compose) != 0:
        raise Unusable("`docker compose up` failed")

    if not await _wait_ready(timeout):
        say(f"{_mark(False)} the application did not become ready in {timeout}s")
        say("[dim]see what it said: wbparser logs -n 50[/dim]")
        return EXIT_WRONG

    say(f"{_mark(True)} ready at {_base_url()}")
    return EXIT_OK


async def action_down(say: Callable[[str], None], *, volumes: bool = False) -> int:
    """Stop the stack. The database volume is kept unless it is asked for."""
    if volumes:
        say("[yellow]![/yellow] removing the database volume — every scraped row is lost")
    if await _stream(say, *_compose_command("down", *(["-v"] if volumes else []))) != 0:
        raise Unusable("`docker compose down` failed")
    say(f"{_mark(True)} stopped" + (" (volume removed)" if volumes else " (volume kept)"))
    return EXIT_OK


async def action_crawl(say: Callable[[str], None]) -> int:
    """
    Run exactly one crawl, in the foreground, and report the exit code.

    The `once` profile switches the web server and the security config off, so a crawl
    runs on a machine that has no API keys at all.
    """
    if not JAR.is_file():
        raise Unusable(f"{_jar_label()} is missing — run `wbparser up` first")
    if not await _running("postgres"):
        raise Unusable("the database is not running — start it with `wbparser up`")

    env = _crawl_env()
    say(f"crawling once against {env['DB_HOST']}:{env['DB_PORT']}/{env['DB_NAME']}…")
    code = await _stream(say, *_java_command(), env=env)
    reasons = {
        0: "a crawl ran and succeeded",
        1: "a crawl ran and failed",
        2: "no crawl ran — the advisory lock is held elsewhere",
        3: "the crawl was cancelled",
    }
    say(f"{_mark(code == EXIT_OK)} exit {code}: {reasons.get(code, 'an unexpected exit')}")
    return EXIT_OK if code == EXIT_OK else EXIT_WRONG


# The actions the operator can start, by name. The TUI's buttons and the CLI's
# commands both come from here.
_ACTIONS: dict[str, Callable[[Callable[[str], None]], Awaitable[int]]] = {
    "up": action_up,
    "down": action_down,
    "crawl": action_crawl,
}


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
    code = asyncio.run(action_up(_console_say, build=build, timeout=timeout))
    if code != EXIT_OK:
        raise typer.Exit(code)
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
    code = asyncio.run(action_down(_console_say, volumes=volumes))
    if code != EXIT_OK:
        raise typer.Exit(code)


async def _fetch_status(api: Api) -> tuple[dict[str, Any], dict[str, bool]]:
    ready, admin_works, catalog_works = await _gather(
        api.get("/api/health/ready"),
        _key_works(api, admin=True),
        _key_works(api, admin=False),
    )
    return ready, {"ADMIN_API_KEY": admin_works, "CATALOG_API_KEY": catalog_works}


@app.command()
def status() -> None:
    """Health, readiness, credentials, crawl counters, and the egress groups."""
    ready, keys = asyncio.run(_with_api(_fetch_status))
    _emit({"ready": ready, "keys": keys}, lambda: _print_status(ready, keys))
    if ready.get("status") != "UP" or not all(keys.values()):
        raise typer.Exit(EXIT_WRONG)


async def _key_works(api: Api, *, admin: bool) -> bool:
    """Whether the server accepts this role's key. A refused key is an answer, not a failure."""
    try:
        await api.get("/api/admin/targets" if admin else "/api/catalog/v1/health", role="admin" if admin else "catalog")
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
    code = asyncio.run(action_crawl(_console_say))
    if code != EXIT_OK:
        raise typer.Exit(code)


class Sort(StrEnum):
    """The orderings `/items` accepts. A wrong one is a 400, so the shell offers the right ones."""

    date_desc = "DateDesc"
    date_asc = "DateAsc"
    price_asc = "PriceAsc"
    price_desc = "PriceDesc"
    cashback_desc = "CashbackPercentDesc"
    cashback_asc = "CashbackPercentAsc"
    points_desc = "FeedbackPointsDesc"
    points_asc = "FeedbackPointsAsc"
    points_share_desc = "FeedbackPointsShareDesc"
    points_share_asc = "FeedbackPointsShareAsc"
    title_asc = "TitleAsc"
    title_desc = "TitleDesc"


class Period(StrEnum):
    """The preset windows of the items tab: the whole table, today, or the last crawl run."""

    all = "all"
    today = "today"
    last_run = "last run"


async def _since(api: Api, period: str) -> datetime | None:
    """
    The lower bound of one preset: `today` is local midnight, `last run` is when the
    newest crawl started — the catalog's clock, not this tool's.

    A `last run` with no recorded start is an **empty** window, and a bound of "now" is
    how that is said: reading it as "no bound" would answer the last run with the whole
    table. The jobs endpoint orders a row without a start last, so this only happens when
    no run has a start at all.
    """
    if period == Period.today:
        return datetime.now().astimezone().replace(hour=0, minute=0, second=0, microsecond=0)
    if period == Period.last_run:
        jobs = await api.get("/api/admin/jobs", list[Job], role="admin", limit=1)
        return (jobs[0].started_at if jobs else None) or datetime.now(UTC)
    return None


async def _fetch_items(
    api: Api,
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
    scraped_after: datetime | None = None,
) -> ItemsPage:
    return await api.get(
        "/api/catalog/v1/items",
        ItemsPage,
        role="catalog",
        targetId=target,
        q=query,
        sort=sort,
        page=page,
        pageSize=page_size,
        inStock="true" if in_stock else None,
        minPrice=min_price,
        maxPrice=max_price,
        minCashback=min_cashback,
        scrapedAfter=scraped_after.isoformat() if scraped_after else None,
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
    result = asyncio.run(
        _with_api(
            lambda api: _fetch_items(
                api,
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
        )
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


async def _fetch_stats(api: Api) -> Stats:
    return await api.get("/api/catalog/v1/stats", Stats, role="catalog")


@app.command()
def stats() -> None:
    """Aggregate counters of the catalog: how big it is and how fresh."""
    payload = asyncio.run(_with_api(_fetch_stats))
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


async def _fetch_runs(api: Api, limit: int = 20, target: str | None = None, errors: int = 10) -> dict[str, Any]:
    """The journal of runs: three independent reads, asked at once."""
    jobs, target_runs, failures = await _gather(
        api.get("/api/admin/jobs", list[Job], role="admin", limit=limit, targetId=target),
        api.get("/api/admin/target-runs", list[TargetRun], role="admin", limit=limit, targetId=target),
        api.get("/api/admin/errors", list[RecordedError], role="admin", limit=errors, unresolvedOnly="true"),
    )
    return {"jobs": jobs, "targetRuns": target_runs, "errors": failures}


@app.command()
def runs(
    limit: Annotated[int, typer.Option(help="how many rows")] = 20,
    target: Annotated[str | None, typer.Option(help="a target UUID")] = None,
    errors: Annotated[int, typer.Option(help="how many unresolved errors to show")] = 10,
) -> None:
    """The journal of runs: what ran, on which address, and what came of it."""
    data = asyncio.run(_with_api(lambda api: _fetch_runs(api, limit=limit, target=target, errors=errors)))
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


async def _fetch_schedule(api: Api, limit: int = 15) -> Schedule:
    """The schedule as the server answers it: the tick's rule, owned by the server."""
    try:
        return await api.get("/api/admin/schedule", Schedule, role="admin", limit=limit)
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
    data = asyncio.run(_with_api(lambda api: _fetch_schedule(api, limit=limit)))
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


async def _fetch_history(api: Api, limit: int = 15) -> dict[str, Any]:
    """The last runs and the schedule: two independent reads, asked at once."""
    jobs, schedule = await _gather(
        api.get("/api/admin/jobs", list[Job], role="admin", limit=limit),
        _fetch_schedule(api),
    )
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
    data = asyncio.run(_with_api(lambda api: _fetch_history(api, limit=limit)))
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


def _visible(section: Section, columns: list[str]) -> Section:
    """The same rows with only the chosen fields, in the order the section defines them."""
    keep = [index for index, name in enumerate(section.columns) if name in columns]
    return Section(
        section.title,
        [section.columns[index] for index in keep],
        [[row[index] for index in keep] for row in section.rows],
    )


class ProductTable(DataTable):
    """
    A table that reports a click on a row.

    The base class stops the click in its own handler, so a handler above it in the
    DOM never sees the click. Reporting it here, before the base class runs, is the
    one place a row click is still visible.
    """

    class RowClicked(Message):
        def __init__(self, row: int) -> None:
            self.row = row
            super().__init__()

    @on(events.Click)
    def _row_click(self, event: events.Click) -> None:
        meta = event.style.meta
        row = meta.get("row")
        if isinstance(row, int) and row >= 0 and not meta.get("out_of_bounds", False):
            self.post_message(self.RowClicked(row))


class Pane(Vertical):
    """
    A tab body that loads and draws its own data.

    `fetch()` is awaited on the app's event loop and touches no widget; `show()`
    touches widgets and does no I/O; `reload()` is the only entry point and owns
    the three states — loading, error, and the time of the last success. The pane
    carries a `Static#pane-status` for them.

    An error from `fetch()` replaces the status line and keeps the previous data and
    `updated_at`. An error from `show()` also keeps `updated_at`, but the base class
    cannot roll back widgets that `show()` already changed — a widget that fails
    mid-update is not repaired here, so `show()` prepares its content before touching
    widgets. A reload while one is in flight does nothing. Call `reload()` after the
    pane is mounted; Textual cancels the worker when the pane is unmounted.
    """

    # The id of this pane's own refresh button. The base class watches for it, so
    # the app does not have to know which button belongs to which pane.
    REFRESH_BUTTON = ""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._loading = False
        self.updated_at: datetime | None = None

    async def fetch(self) -> Any:
        raise NotImplementedError

    def show(self, data: Any) -> None:
        raise NotImplementedError

    @on(Button.Pressed)
    def _on_refresh_button(self, event: Button.Pressed) -> None:
        """This pane's refresh button reloads this pane and nothing else."""
        if event.button.id == self.REFRESH_BUTTON:
            self.reload()

    def reload(self) -> None:
        """Start one load; a reload while a load is in flight does nothing."""
        if self._loading:
            return
        self._loading = True
        self._status("[dim]loading…[/dim]")
        self.run_worker(self._load())

    async def _load(self) -> None:
        try:
            data = await self.fetch()
            # Both stages are guarded: a failure to draw is as much a failed reload as
            # a failure to fetch. CancelledError is not an Exception, so a worker
            # cancelled on unmount is never shown as an error.
            self.show(data)
        except Exception as exc:  # noqa: BLE001 - a pane boundary: an error is shown, never raised
            self._status(f"[red]{escape(str(exc))}[/red]")
        else:
            self.updated_at = datetime.now(UTC)
            self._status(f"[dim]updated {self.updated_at.astimezone():%H:%M:%S}[/dim]")
        finally:
            self._loading = False

    def _status(self, text: str) -> None:
        self.query_one("#pane-status", Static).update(text)


class StatsPane(Pane):
    """The catalog's counters, fetched and drawn by the pane itself — the pilot."""

    REFRESH_BUTTON = "btn-stats"

    def compose(self) -> ComposeResult:
        with Horizontal(classes="row"):
            yield Button("Refresh", id="btn-stats", variant="primary")
        yield Static("", id="pane-status", classes="box")
        yield DataTable(id="stats-table")

    def on_mount(self) -> None:
        self.query_one("#stats-table", DataTable).add_columns("metric", "value")

    async def fetch(self) -> Stats:
        async with Api() as api:
            return await _fetch_stats(api)

    def show(self, payload: Stats) -> None:
        # Cells are built before the table is touched, so a failure while building them
        # leaves the last good rows. clear() and add_row() are not atomic: a widget that
        # fails mid-update is beyond what this class repairs.
        rows = [
            ("products", payload.total_products),
            ("observations", f"{payload.total_items} (one row per target × product × price)"),
            ("targets", payload.total_targets),
            ("with cashback", f"{payload.products_with_cashback} products / {payload.with_cashback} observations"),
            ("newest observation", _age(payload.newest_scraped_at)),
            ("counters computed", f"{_age(payload.as_of)} (cached up to ten minutes)"),
        ]
        cells = [(_cell(key), _cell(value)) for key, value in rows]
        table = self.query_one("#stats-table", DataTable)
        table.clear()
        for row in cells:
            table.add_row(*row)


class ConfirmDown(ModalScreen[bool]):
    """
    The operator's confirmation: the stack stops only when they say so.

    The button is a stop, not a delete: the database volume is kept, and the answer
    is a plain yes or no — anything else (Escape) counts as no.
    """

    DEFAULT_CSS = """
    ConfirmDown > Vertical {
        width: 60;
        height: auto;
        border: round $warning;
        background: $surface;
        padding: 1 2;
    }
    ConfirmDown Horizontal {
        height: auto;
        align: right middle;
    }
    """

    BINDINGS = [Binding("escape", "refuse", "Keep running")]

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Static("Stop the stack?\n\nThe database volume is kept — nothing is deleted.")
            with Horizontal():
                yield Button("Stop", id="confirm-stop", variant="warning")
                yield Button("Keep running", id="confirm-keep")

    @on(Button.Pressed)
    def _on_button(self, event: Button.Pressed) -> None:
        self.dismiss(event.button.id == "confirm-stop")

    def action_refuse(self) -> None:
        """Escape is a refusal: the stack keeps running, and nothing is started."""
        self.dismiss(False)


class Actions(Horizontal):
    """
    The stack actions — Up, Down, Crawl — as buttons that run one at a time.

    The buttons call the shared actions in a worker, so the event loop keeps
    running while Gradle builds or a crawl runs: the screen stays alive, and
    cancelling the worker stops the child process instead of leaving it behind.

    Down asks first: the stack stops only when the operator confirms it.
    """

    class Finished(Message):
        """An action has finished: its name and its exit code."""

        def __init__(self, action: str, code: int) -> None:
            super().__init__()
            self.action = action
            self.code = code

    DEFAULT_CSS = """
    Actions { height: auto; }
    """

    def compose(self) -> ComposeResult:
        yield Button("Up", id="btn-up")
        yield Button("Down", id="btn-down", variant="warning")
        yield Button("Crawl once", id="btn-crawl")

    def _say(self, text: str) -> None:
        self.screen.query_one("#activity-log", RichLog).write(text)

    @on(Button.Pressed)
    def _on_button(self, event: Button.Pressed) -> None:
        action = {"btn-up": "up", "btn-down": "down", "btn-crawl": "crawl"}.get(event.button.id or "")
        if action is not None:
            # The buttons wait for the action, and they wait from the click: a second
            # click must not start a second action.
            self._set_enabled(False)
            if action == "down":
                # The stack stops only when the operator confirms it.
                self.app.push_screen(ConfirmDown(), self._on_confirmed)
            else:
                self.run_action(action)

    def _on_confirmed(self, confirmed: bool | None) -> None:
        """The operator answered the confirmation: yes starts the stop, no changes nothing."""
        if confirmed:
            self.run_action("down")
            return
        self._say("down was not confirmed — the stack was left running")
        self._set_enabled(True)

    @work(group="action")
    async def run_action(self, action: str) -> None:
        """Run one action. The buttons wait for it, and the child process dies with the worker."""
        self._say(f"[bold]▶ {action}[/bold]")
        try:
            code = await _ACTIONS[action](self._say)
        except Unusable as error:
            self._say(f"[red]✖ {action}: {escape(str(error))}[/red]")
            code = EXIT_UNUSABLE
        except asyncio.CancelledError:
            self._say(f"[yellow]■ {action} was cancelled[/yellow]")
            raise
        except Exception as error:  # noqa: BLE001 - the operator must see what went wrong
            self._say(f"[red]✖ {action}: {escape(str(error))}[/red]")
            code = EXIT_UNUSABLE
        else:
            self._say(f"[green]✔ {action} finished[/green]" if code == EXIT_OK else f"[red]✖ {action} failed[/red]")
        finally:
            self._set_enabled(True)
        self.post_message(self.Finished(action, code))

    def _set_enabled(self, enabled: bool) -> None:
        """The buttons wait: one action at a time, and no cancelled action under the operator's feet."""
        for button in self.query(Button):
            button.disabled = not enabled


class StatusPane(Pane):
    """The application's health, its credentials, its counters and its egress groups."""

    REFRESH_BUTTON = "btn-refresh-status"

    def compose(self) -> ComposeResult:
        with Horizontal(id="status-actions", classes="row"):
            yield Button("Refresh", id="btn-refresh-status", variant="primary")
            yield Actions()
        yield Static("", id="pane-status", classes="box")
        yield Static("", id="status-summary", classes="box")
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

    def on_mount(self) -> None:
        self.query_one("#status-checks", DataTable).add_columns("check", "value")
        self.query_one("#status-creds", DataTable).add_columns("key", "status")
        self.query_one("#status-egress", DataTable).add_columns("group", "address", "health", "isolated", "refusals")

    async def fetch(self) -> tuple[dict[str, Any], dict[str, bool]]:
        async with Api() as api:
            return await _fetch_status(api)

    def show(self, data: tuple[dict[str, Any], dict[str, bool]]) -> None:
        ready, keys = data
        # Phase one — prepare. Nothing here touches a widget: the base class cannot
        # roll a widget back, so a malformed answer has to fail while the content is
        # built, and leave the last good data on screen.
        healthy = ready.get("status") == "UP"
        mark = "[green]UP[/green]" if healthy else "[red]DOWN[/red]"
        summary = f"Application at [bold]{escape(_base_url())}[/bold]: {mark}"

        checks_rows = []
        for name, value in (ready.get("checks") or {}).items():
            ok = str(value).startswith("UP")
            checks_rows.append((_cell(name), Text(str(value), style="green" if ok else "yellow")))

        creds_rows = []
        for name, works in keys.items():
            creds_rows.append(
                (_cell(name), Text("accepted", style="green") if works else Text("refused or missing", style="red"))
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

        egress_rows = []
        for g in (ready.get("egress") or {}).get("groups") or []:
            health = Text("healthy", style="green") if g.get("healthy") else Text("unhealthy", style="red")
            egress_rows.append(
                (
                    _cell(g.get("group")),
                    _cell(g.get("address") or "unknown"),
                    health,
                    _cell(g.get("isolated")),
                    _cell(g.get("refusal_rate")),
                )
            )

        # Phase two — draw.
        self._update_summary(summary)
        self._update_checks(checks_rows)
        self._update_credentials(creds_rows)
        self._update_crawl(crawl_text)
        self._update_egress(egress_rows)

    def _update_summary(self, summary: str) -> None:
        self.query_one("#status-summary", Static).update(summary)

    def _update_checks(self, rows: list[tuple[Any, ...]]) -> None:
        table = self.query_one("#status-checks", DataTable)
        table.clear()
        for row in rows:
            table.add_row(*row)

    def _update_credentials(self, rows: list[tuple[Any, ...]]) -> None:
        table = self.query_one("#status-creds", DataTable)
        table.clear()
        for row in rows:
            table.add_row(*row)

    def _update_crawl(self, text: str) -> None:
        self.query_one("#status-crawl", Static).update(text)

    def _update_egress(self, rows: list[tuple[Any, ...]]) -> None:
        table = self.query_one("#status-egress", DataTable)
        table.clear()
        for row in rows:
            table.add_row(*row)


class ItemsPane(Pane):
    """The scraped products, filtered and paged by the widgets above the table."""

    REFRESH_BUTTON = "btn-items"

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        # The product URLs of the rows on screen, so a click can open one.
        self.urls: list[str] = []
        # The fields to draw, and the page they belong to: a field change redraws
        # that page without asking the API again.
        self.columns = _saved_columns()
        self.page: ItemsPage | None = None
        # The window the table shows. The select posts a Changed for its own initial
        # value on mount, and that one starts no load.
        self.period = Period.all.value

    def compose(self) -> ComposeResult:
        with Horizontal(id="items-controls", classes="row"):
            yield Input(placeholder="query (-q)", id="items-q")
            yield Input(placeholder="target UUID", id="items-target")
            yield Select(
                [(p.value, p.value) for p in Period],
                value=Period.all.value,
                allow_blank=False,
                id="items-period",
            )
            yield Select(
                [(s.value, s.value) for s in Sort],
                value=Sort.date_desc.value,
                allow_blank=False,
                id="items-sort",
            )
            yield Input(placeholder="page", value="0", id="items-page", classes="small")
            yield Input(placeholder="size", value="20", id="items-size", classes="small")
            yield Button("Load", id="btn-items", variant="primary")
        with Horizontal(id="items-columns", classes="row"):
            # The id is the field's position, not its name: a header may carry characters
            # that a Textual identifier cannot ("points %").
            for index, name in enumerate(ITEM_COLUMNS):
                yield Checkbox(name, value=name in self.columns, id=f"col-{index}")
        yield Static("", id="pane-status", classes="box")
        yield Static("", id="items-info")
        yield ProductTable(id="items-table")

    @on(Checkbox.Changed)
    def _on_columns(self) -> None:
        """A field choice is a preference: it is saved and the page on screen redrawn."""
        self.columns = [
            name for index, name in enumerate(ITEM_COLUMNS) if self.query_one(f"#col-{index}", Checkbox).value
        ]
        try:
            _save_columns(self.columns)
        except OSError as error:
            self._status(f"[red]{escape(str(error))}[/red]")
        if self.page is not None:
            self.show(self.page)

    @on(Select.Changed, "#items-period")
    def _on_period(self, event: Select.Changed) -> None:
        """A new window is a new list: it starts from the first page."""
        if str(event.value) == self.period:
            return
        self.period = str(event.value)
        self.query_one("#items-page", Input).value = "0"
        self.reload()

    async def fetch(self) -> ItemsPage:
        # The filters are read here, on the app's event loop: a widget read from a
        # thread worker is a widget read while it may be changing.
        query = self.query_one("#items-q", Input).value or None
        target = self.query_one("#items-target", Input).value or None
        period = str(self.query_one("#items-period", Select).value)
        sort = str(self.query_one("#items-sort", Select).value)
        page = _number(self.query_one("#items-page", Input).value, 0)
        size = _number(self.query_one("#items-size", Input).value, 20)
        async with Api() as api:
            return await _fetch_items(
                api,
                target=target,
                query=query,
                page=page,
                page_size=size,
                sort=sort,
                scraped_after=await _since(api, period),
            )

    def show(self, result: ItemsPage) -> None:
        self.page = result
        self.query_one("#items-info", Static).update(
            f"page {result.page + 1}/{max(1, result.total_pages)} — {result.total_count} total"
            + (f" (data {_age(result.data_as_of)})" if result.data_as_of else "")
            + "  [dim](click a row to open)[/dim]"
        )
        self.urls = [str(item.product_url) for item in result.items]
        table = self.query_one("#items-table", ProductTable)
        if not self.columns:
            table.clear(columns=True)
            return
        fill(table, _visible(items_section(result), self.columns))


class RunsPane(Pane):
    """The journal of runs: the target runs, the jobs and the unresolved errors."""

    REFRESH_BUTTON = "btn-runs"

    def compose(self) -> ComposeResult:
        with Horizontal(classes="row"):
            yield Button("Refresh", id="btn-runs", variant="primary")
        yield Static("", id="pane-status", classes="box")
        yield Static("Target runs", classes="title")
        yield DataTable(id="runs-targets")
        yield Static("Jobs", classes="title")
        yield DataTable(id="runs-jobs")
        yield Static("Unresolved errors", classes="title")
        yield DataTable(id="runs-errors")

    async def fetch(self) -> dict[str, Any]:
        async with Api() as api:
            return await _fetch_runs(api)

    def show(self, data: dict[str, Any]) -> None:
        sections = runs_sections(data)
        fill(self.query_one("#runs-targets", DataTable), sections[0])
        fill(self.query_one("#runs-jobs", DataTable), sections[1])
        fill(self.query_one("#runs-errors", DataTable), sections[2])


class SchedulePane(Pane):
    """The tick, the freshness window and the targets that are due."""

    REFRESH_BUTTON = "btn-schedule"

    def compose(self) -> ComposeResult:
        with Horizontal(classes="row"):
            yield Button("Refresh", id="btn-schedule", variant="primary")
        yield Static("", id="pane-status", classes="box")
        yield Static("", id="schedule-summary", classes="box")
        yield Static("Due now", classes="title")
        yield DataTable(id="sched-due")
        yield Static("Upcoming", classes="title")
        yield DataTable(id="sched-upcoming")

    async def fetch(self) -> Schedule:
        async with Api() as api:
            return await _fetch_schedule(api)

    def show(self, data: Schedule) -> None:
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


class HistoryPane(Pane):
    """The last runs, what started each of them, and when the next one is due."""

    REFRESH_BUTTON = "btn-history"

    def compose(self) -> ComposeResult:
        with Horizontal(classes="row"):
            yield Button("Refresh", id="btn-history", variant="primary")
        yield Static("", id="pane-status", classes="box")
        yield Static("", id="history-summary", classes="box")
        yield DataTable(id="history-table")

    async def fetch(self) -> dict[str, Any]:
        async with Api() as api:
            return await _fetch_history(api)

    def show(self, data: dict[str, Any]) -> None:
        nr = data["nextRun"]
        nr_text = (
            f"{nr.astimezone().strftime('%Y-%m-%d %H:%M:%S %Z')} ({_age(nr)})" if nr else "[yellow]unknown[/yellow]"
        )
        summary = f"tick: {data['cron']}  |  freshness: {data['freshnessMinutes']} min  |  next run: {nr_text}"
        if data.get("earliestDue"):
            summary += f"  |  earliest due {_age(data['earliestDue'])}"
        self.query_one("#history-summary", Static).update(summary)
        fill(self.query_one("#history-table", DataTable), history_section(data["jobs"]))


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
    #items-controls Select { width: 26; }
    #items-controls .small { width: 8; }
    /* An Input is 100% wide by default: in the log row that pushed the buttons
       off the screen, and a Stop button that cannot be pressed stops nothing. */
    #logs-lines { width: 8; }
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

    # The read-only tabs and the pane that draws each of them. The app assembles the
    # tabs, routes the common actions and refreshes the active pane; a pane loads and
    # draws its own data.
    PANES: dict[str, type[Pane]] = {
        "tab-status": StatusPane,
        "tab-items": ItemsPane,
        "tab-runs": RunsPane,
        "tab-schedule": SchedulePane,
        "tab-history": HistoryPane,
        "tab-stats": StatsPane,
    }

    # How often the active pane reloads itself. One cadence for every pane.
    REFRESH_EVERY = 30.0

    def compose(self) -> ComposeResult:
        yield Header(show_clock=True)
        with TabbedContent(initial="tab-status", id="tabs"):
            with TabPane("Status", id="tab-status"):
                yield StatusPane(classes="pane")

            with TabPane("Items", id="tab-items"):
                yield ItemsPane(classes="pane")

            with TabPane("Runs", id="tab-runs"):
                yield RunsPane(classes="pane")

            with TabPane("Schedule", id="tab-schedule"):
                yield SchedulePane(classes="pane")

            with TabPane("History", id="tab-history"):
                yield HistoryPane(classes="pane")

            with TabPane("Stats", id="tab-stats"):
                yield StatsPane(classes="pane")

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
        self._last_item_open: tuple[int, float] | None = None
        # The tab that is active when the app opens loads itself: the first
        # TabActivated is what loads the first tab. The timer keeps the active pane
        # fresh from then on, and only the active one.
        self.set_interval(self.REFRESH_EVERY, self._refresh_active_pane)

    def action_tab(self, tab_id: str) -> None:
        self.query_one("#tabs", TabbedContent).active = tab_id

    async def on_unmount(self) -> None:
        """
        The follow stops with the TUI — and the app waits for it.

        Textual cancels the workers when the app goes, but it does not wait for
        them: the `docker compose logs` would be signalled and left to die on its
        own. Here the worker is cancelled and awaited, so the child is gone before
        the app is.
        """
        for worker in self.workers.cancel_group(self, "follow"):
            with contextlib.suppress(WorkerCancelled):
                await worker.wait()

    def action_refresh(self) -> None:
        """Refresh the active tab: its pane, or the log view."""
        self.query_one("#msg", Static).update("")
        self._refresh_tab(self.query_one("#tabs", TabbedContent).active)

    def _refresh_tab(self, tab: str) -> None:
        """One tab's refresh. A pane reloads itself; the log tab is not a pane."""
        pane = self._pane(tab)
        if pane is not None:
            pane.reload()
        elif tab == "tab-logs":
            self.load_logs()

    def _refresh_active_pane(self) -> None:
        """The automatic refresh: the active pane only, so a hidden tab never polls the API."""
        pane = self._pane(self.query_one("#tabs", TabbedContent).active)
        if pane is not None:
            pane.reload()

    def _pane(self, tab: str) -> Pane | None:
        """The pane of one tab, if that tab has one."""
        cls = self.PANES.get(tab)
        return None if cls is None else self.query_one(cls)

    def _reload_status(self) -> None:
        """Reload the status pane — the action buttons change what the status says."""
        self.query_one(StatusPane).reload()

    @on(TabbedContent.TabActivated)
    def _on_tab(self, event: TabbedContent.TabActivated) -> None:
        if event.tabbed_content.id == "tabs":
            self._refresh_tab(event.pane.id or "")

    @on(Button.Pressed)
    def _on_button(self, event: Button.Pressed) -> None:
        """
        The app's own buttons: the actions of the status tab and the log buttons.

        A pane's refresh button is the pane's business — the base class reloads the
        pane that owns the button.
        """
        bid = event.button.id or ""
        if bid == "btn-logs":
            self.load_logs()
        elif bid == "btn-logs-follow":
            self.follow_logs()
        elif bid == "btn-logs-stop":
            # The follow is a worker of its own group: cancelling the group cancels
            # it, and the child process dies with it.
            self.workers.cancel_group(self, "follow")

    @on(Actions.Finished)
    def _on_action(self, event: Actions.Finished) -> None:
        """An action has finished: the status it changed is worth showing again."""
        self._reload_status()

    def _note(self, text: str) -> None:
        self.query_one("#msg", Static).update(text)

    def _open_item_by_row(self, idx: int) -> None:
        """Open the product URL for a table row in the default browser."""
        urls = self.query_one(ItemsPane).urls
        if idx < 0 or idx >= len(urls):
            return
        now = time.monotonic()
        last = getattr(self, "_last_item_open", None)
        if last and last[0] == idx and now - last[1] < 0.5:
            return
        self._last_item_open = (idx, now)
        url = urls[idx]
        try:
            opened = webbrowser.open(url)
        except Exception as exc:  # noqa: BLE001
            self._note(f"[red]Could not open browser: {escape(str(exc))}[/red]")
            return
        if opened:
            self._note(f"Opening {url}")
        else:
            self._note(f"[yellow]Could not open browser for {escape(url)}[/yellow]")

    @on(ProductTable.RowClicked)
    def _open_item_click(self, event: ProductTable.RowClicked) -> None:
        """Open the clicked product row in the default browser."""
        self._open_item_by_row(event.row)

    @on(DataTable.CellSelected, "#items-table")
    def _open_item_selected(self, event: DataTable.CellSelected) -> None:
        """Open the selected product in the default browser (e.g. Enter)."""
        self._open_item_by_row(event.coordinate.row)

    @work(exclusive=True, group="logs")
    async def load_logs(self) -> None:
        """The last lines of the application log, read without blocking the screen."""
        try:
            n = int(self.query_one("#logs-lines", Input).value or "150")
        except ValueError:
            n = 150
        view = self.query_one("#logs-view", RichLog)
        try:
            if not await _running("app"):
                view.write("app is not running — start it with Up")
                return
            result = await proc.sh(*_compose_command("logs", "--tail", str(n), "app"))
        except (Unusable, proc.ProcError) as error:
            view.write(str(error))
            return
        view.clear()
        view.write(result.stdout or result.stderr or "(no output)")

    @work(exclusive=True, group="follow")
    async def follow_logs(self) -> None:
        """
        Follow the log until the worker is cancelled — and the child process goes with it.

        The worker is exclusive in its own group: a second Follow cancels the first,
        and Stop cancels the group. The child is `docker compose logs --follow`, and
        the subprocess layer owns its life: a cancelled worker leaves no process and
        no open pipe behind.
        """
        view = self.query_one("#logs-view", RichLog)
        try:
            if not await _running("app"):
                view.write("app is not running — start it with Up")
                return
            code = await proc.stream(
                lambda line: view.write(Text.from_ansi(line)),
                *_compose_command("logs", "--follow", "--tail", "50", "app"),
            )
        except (Unusable, proc.ProcError) as error:
            view.write(str(error))
            return
        if code != EXIT_OK:
            view.write(f"`docker compose logs` failed with code {code}")


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


async def _action_logs(say: Callable[[str], None], *, follow: bool, lines: int) -> int:
    """The application log, streamed to the operator. `--follow` never returns on its own."""
    if not await _running("app"):
        raise Unusable("the application is not running — start it with `wbparser up`")
    logs = _compose_command("logs", *(["--follow"] if follow else []), "--tail", str(lines), "app")
    if await _stream(say, *logs) != EXIT_OK:
        raise Unusable("`docker compose logs` failed")
    return EXIT_OK


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
    code = asyncio.run(_action_logs(_console_say, follow=follow, lines=lines))
    if code != EXIT_OK:
        raise typer.Exit(code)


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

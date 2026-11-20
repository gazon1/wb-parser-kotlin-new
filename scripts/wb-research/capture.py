#!/usr/bin/env python3
"""Capture the network events of one Wildberries page action, as research evidence.

chrome-use already records what the browser sent and received. What it does not
do is say which events belong to which step of an experiment, separate the
Wildberries API from the analytics traffic around it, or keep the cookies out of
the file that is committed. This script is the narrow wrapper that fixes those
three things:

- it names the step, so every event belongs to one;
- it keeps the requests the config approves, drops the rest and *reports* what
  it dropped — an API call to a host that is neither approved nor marked as
  noise is an error, never a silent discard;
- it hands the capture to the redactor, which is the only thing that writes a
  sanitized fixture into the repository.

The raw capture stays in `~/.cache/wb-research/<investigation>/`, outside the
repository. Only the sanitized fixture is committed.

The capture observes: the page action it drives is a chrome-use command that
reads the page, and an action that could change the site — a click, a typed
text, a shell — is refused before the recording starts.

Usage:
    capture.py step --investigation <id> --name <step> -- <read-only chrome-use command...>
    capture.py export --investigation <id>
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path
from urllib.parse import urlsplit

REPO_ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
REDACTOR = HERE / "redact.py"
CONFIG_PATH = REPO_ROOT / "devtools" / "wb-research" / "capture-config.json"
EVIDENCE_ROOT = REPO_ROOT / "devtools" / "wb-research" / "investigations"
RAW_ROOT = Path.home() / ".cache" / "wb-research"

# A request of one of these types is an API call. An API call to a host that is
# neither approved nor marked as noise is what the filter must not drop in
# silence, so the capture fails instead of hiding it.
API_RESOURCE_TYPES = ("Fetch", "XHR")

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_UNAPPROVED_API = 2

# The id of an investigation becomes a directory name in the raw cache and in
# the repository, so it has to be one name and nothing else: a name that could
# climb out of either directory is not an id.
INVESTIGATION_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")

# The dot segments of a path, however they are written: a browser resolves
# `%2e%2e` as `..`, so a check that does not know that approves another page.
DOT_SEGMENT = re.compile(r"\.|%2e", re.IGNORECASE)
DOUBLE_DOT_SEGMENT = re.compile(r"(?:\.|%2e){2}", re.IGNORECASE)

# The port a scheme answers on when the URL does not name one. The origin of a
# URL is the scheme, the host and the port, so a page the config approves is
# the page on that port and not the same host on another one.
DEFAULT_PORTS = {"http": 80, "https": 443}

# The page actions a capture may drive. The issue that asked for the tool says
# read-only observation only: no cart, no purchase, no account. So the actions
# that read the page are allowed, and the ones that change something on the
# site — a click, a typed text, a filled form — are not.
READ_ONLY_PAGE_ACTIONS = {
    "canvas",
    "expect",
    "extract",
    "focus",
    "hover",
    "open",
    "read",
    "screenshot",
    "scroll",
    "scrollintoview",
    "snapshot",
    "wait",
}

# The page actions that navigate. A navigation is a request, and a request may
# change what it is asked to change, so the host it opens has to be approved.
NAVIGATION_PAGE_ACTIONS = {"open", "read"}

_REDACTOR = None


def load_redactor():
    """The redactor next door. Its rules are the rules here, and it is loaded once."""
    global _REDACTOR
    if _REDACTOR is not None:
        return _REDACTOR
    spec = importlib.util.spec_from_file_location("wb_research_redact", REDACTOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    _REDACTOR = module
    return module


def safe_investigation(value: str) -> str:
    """The id of an investigation, checked to be a single safe path component.

    The id names a directory in the raw cache and a directory in the
    repository. A name that carries a separator or a `..` would point at
    another directory than the one the operator named, so it is refused rather
    than sanitized into something else.
    """
    text = str(value or "").strip()
    if not text or ".." in text or not INVESTIGATION_NAME.fullmatch(text):
        raise SystemExit(f"the investigation id must be a single name, not a path: {value!r}")
    return text


def load_config(path: Path, extra_hosts: list[str]) -> dict:
    """The capture config, plus the hosts the operator approved on the command line."""
    if not path.exists():
        raise SystemExit(f"no capture config at {path} — the config file is the only source of the approved hosts")
    config = json.loads(path.read_text(encoding="utf-8"))
    config["allowedOrigins"] = list(config.get("allowedOrigins", [])) + list(extra_hosts)
    config["allowedPages"] = list(config.get("allowedPages", []))
    config["noiseHosts"] = list(config.get("noiseHosts", []))
    config["keptResourceTypes"] = list(config.get("keptResourceTypes", list(API_RESOURCE_TYPES)))
    config["maxBodyFetches"] = int(config.get("maxBodyFetches", 50))
    return config


def host_of(url: str) -> str:
    """The host of a URL, lowercased. An empty string for a URL without one."""
    return (urlsplit(url).hostname or "").lower()


def host_matches(host: str, patterns: list[str]) -> bool:
    """Whether a host is one of the patterns. `*.example.ru` covers its subdomains."""
    for pattern in patterns:
        pattern = pattern.strip().lower()
        if not pattern:
            continue
        if pattern.startswith("*."):
            root = pattern[2:]
            if host.endswith("." + root):
                return True
        elif host == pattern:
            return True
    return False


def page_path(url: str) -> str:
    """The path of a URL, resolved: the dot segments are the segments they are.

    A browser resolves the segments of a path before it asks for the page, so
    a check that does not resolve them would approve a path that the browser
    reads as another one. A dot segment is a dot segment however it is
    written: `.`, `%2e` and `..`, `%2e%2e`, `.%2e` are the same two segments.
    """
    path = urlsplit(url).path
    if not path or path == "/":
        return "/"
    segments: list[str] = []
    for segment in path.split("/"):
        if DOUBLE_DOT_SEGMENT.fullmatch(segment):
            if segments:
                segments.pop()
            continue
        if DOT_SEGMENT.fullmatch(segment):
            continue
        segments.append(segment)
    resolved = "/".join(segments)
    if not resolved.startswith("/"):
        resolved = "/" + resolved
    return resolved.rstrip("/") or "/"


def origin_of(url: str) -> tuple[str, str, int] | None:
    """The origin of a URL: the scheme, the host and the port it answers on.

    A port is part of the origin: the same host on another port is another
    service, and the page the config approves is not that one. `None` for a
    URL whose port is not a port.
    """
    parts = urlsplit(url)
    scheme = parts.scheme.lower()
    try:
        port = parts.port
    except ValueError:
        return None
    host = (parts.hostname or "").lower()
    return scheme, host, port if port is not None else DEFAULT_PORTS.get(scheme, 0)


def approved_page(url: str, pages: list[str]) -> bool:
    """Whether the page a navigation opens is one the config approves.

    A navigation is a request, and a request may change what it is asked to
    change — a page that answers a GET may add to the cart. So the config
    names the pages a capture may open, and a page that is not under one of
    them is refused.

    A named page approves the origin it names — the scheme, the host and the
    port — and the path it names and the paths under it, by path segment:
    `https://example.ru/catalog/` approves `/catalog/131954/list.aspx` and not
    `/catalogue/x`.
    """
    origin = origin_of(url)
    if origin is None:
        return False
    for page in pages:
        page_origin = origin_of(page)
        if page_origin is None or page_origin[0] not in ("http", "https"):
            continue
        if page_origin != origin:
            continue
        prefix = page_path(page)
        path = page_path(url)
        if path == prefix or (prefix != "/" and path.startswith(prefix + "/")):
            return True
    return False


def classify(entry: dict, config: dict) -> tuple[str | None, str]:
    """Why an entry is not evidence, and the host it came from.

    `None` means the entry is evidence. The reasons are the ones the report
    counts, so a dropped request is never dropped without a reason.

    The kinds of request that count as an API call are the ones the config
    keeps: one definition, so that a kind added to the config is an API call
    on an approved host and on an unapproved one alike. Two definitions would
    let a kind of request slip past the unapproved-host check as ordinary
    traffic, and the capture would not stop for it.
    """
    host = host_of(entry.get("request", {}).get("url", ""))
    resource_type = entry.get("_resourceType", "")
    if host_matches(host, config["allowedOrigins"]):
        if resource_type in config["keptResourceTypes"]:
            return None, host
        return "type-not-kept", host
    if host_matches(host, config["noiseHosts"]):
        return "noise-host", host
    if resource_type in config["keptResourceTypes"]:
        return "host-not-approved", host
    return "other-host", host


def filter_entries(entries: list[dict], config: dict) -> tuple[list[tuple[int, dict]], Counter, dict[str, set[str]]]:
    """Split the recorded entries into the evidence and the noise."""
    kept: list[tuple[int, dict]] = []
    dropped: Counter = Counter()
    dropped_hosts: dict[str, set[str]] = {}
    for index, entry in enumerate(entries):
        reason, host = classify(entry, config)
        if reason is None:
            kept.append((index, entry))
            continue
        dropped[reason] += 1
        dropped_hosts.setdefault(reason, set()).add(host)
    return kept, dropped, dropped_hosts


def join_request_ids(entries: list[dict], listed: list[dict]) -> dict[int, str] | None:
    """Map the index of a recorded entry to the request id chrome-use knows it by.

    The HAR and the request list come from the same browser session and are in
    the same order, so the mapping is positional. It is only used when every
    entry lines up: a mapping that cannot be verified is no mapping, and the
    response bodies are then not captured rather than attached to the wrong
    request.

    Two entries that look the same cannot be told apart: the list may hold
    them in another order, and a body attributed to the wrong one of them
    would be evidence of the wrong request. Such an entry is left unmapped —
    its body is not read, and the others still are.

    The same is true when the *list* holds the pair twice and the recording
    holds it once: a request made before the recording started and the same
    request made after it look alike, and the body of the first one would be
    read for the second. Such an entry is left unmapped as well.
    """
    if len(listed) < len(entries):
        return None
    twins = Counter((entry.get("request", {}).get("method"), entry.get("request", {}).get("url")) for entry in entries)
    listed_twins = Counter((item.get("method"), item.get("url")) for item in listed)
    ids: dict[int, str] = {}
    for index, entry in enumerate(entries):
        other = listed[index]
        request = entry.get("request", {})
        method = request.get("method")
        url = request.get("url")
        if other.get("method") != method or other.get("url") != url:
            return None
        if twins[(method, url)] > 1 or listed_twins[(method, url)] > 1:
            continue
        request_id = other.get("requestId")
        if not request_id:
            return None
        ids[index] = request_id
    return ids


def response_shape(detail: dict) -> dict:
    """What the response body looked like, without the body itself.

    Only the shape is kept: the names of the top-level keys and the size. The
    body of a logged-in session can carry personal data, and a shape answers the
    question the research asks. A key is a name the service chose, and a name
    can be an address or an account id, so the keys go through the same rules
    as everything else that reaches a committed file.
    """
    body = detail.get("responseBody")
    if not isinstance(body, str):
        # The browser's own error text is not written down: it may quote the
        # URL or the request, and the reason is a code the redactor knows. The
        # reason says the body was not read — that is the fact the evidence
        # carries.
        return {"available": False, "reason": "browser-error" if detail.get("responseBodyError") else "no-body"}
    shape = {
        "available": True,
        "bodyBytes": len(body.encode("utf-8")),
        "isJson": False,
        "jsonType": None,
        "topLevelKeys": None,
    }
    try:
        parsed = json.loads(body)
    except (json.JSONDecodeError, ValueError):
        return shape
    shape["isJson"] = True
    if isinstance(parsed, dict):
        shape["jsonType"] = "object"
        shape["topLevelKeys"] = sorted(load_redactor().safe_key(key) for key in parsed)
    elif isinstance(parsed, list):
        shape["jsonType"] = "array"
    else:
        shape["jsonType"] = "scalar"
    return shape


def chrome_use(*args: str) -> subprocess.CompletedProcess:
    """Run a chrome-use command. Its output is the browser's, not ours."""
    return subprocess.run(["chrome-use", *args], capture_output=True, text=True, check=False)


def chrome_use_json(*args: str) -> dict:
    """Run a chrome-use command that prints JSON and return its `data`."""
    proc = chrome_use(*args, "--json")
    text = (proc.stdout or "") + (proc.stderr or "")
    start = text.find("{")
    if proc.returncode != 0 or start < 0:
        raise SystemExit(f"chrome-use {' '.join(args)} failed: {text.strip()[:400]}")
    try:
        payload = json.loads(text[start:])
    except json.JSONDecodeError as error:
        raise SystemExit(f"chrome-use {' '.join(args)} printed no JSON: {error}") from error
    if not payload.get("success"):
        raise SystemExit(f"chrome-use {' '.join(args)} failed: {payload.get('error')}")
    return payload.get("data") or {}


def write_har(path: Path, entries: list[dict]) -> None:
    """Write the entries back as a HAR, keeping the shape chrome-use produced."""
    path.parent.mkdir(parents=True, exist_ok=True)
    document = {
        "log": {
            "version": "1.2",
            "creator": {"name": "wb-research capture", "version": "1"},
            "browser": {"name": "chrome-use", "version": "1"},
            "entries": entries,
        }
    }
    path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def read_har(path: Path) -> list[dict]:
    """The entries of a HAR file."""
    document = json.loads(path.read_text(encoding="utf-8"))
    return document.get("log", {}).get("entries", []) or []


def page_actions(command: list[str]) -> list[list[str]]:
    """The page actions of a step: one command each, split by `--`."""
    actions: list[list[str]] = []
    current: list[str] = []
    for word in command:
        if word == "--":
            if current:
                actions.append(current)
            current = []
            continue
        current.append(word)
    if current:
        actions.append(current)
    return actions


def check_read_only(actions: list[list[str]], approved: list[str], pages: list[str]) -> None:
    """Refuse a page action that could change the site.

    The tool observes. A page action is the chrome-use command that reads the
    page, and nothing else: a shell, a click on the cart, a filled form are
    refused before the recording starts, so a capture cannot change the
    account it observes.

    The program is the chrome-use command by its own name: a path to a program
    that happens to be called chrome-use is not it. And an action that opens a
    page may only open an approved host and an approved page — a navigation is
    a request, and a request may change what it is asked to change: an
    approved host answers a GET that adds to the cart as well.
    """
    for action in actions:
        if action[0] != "chrome-use":
            raise SystemExit(f"the page action must be the chrome-use command, not {action[0]!r}")
        verb = action[1] if len(action) > 1 else ""
        if verb not in READ_ONLY_PAGE_ACTIONS:
            raise SystemExit(
                f"{verb or 'a command'} is not a read-only page action — a capture observes. "
                f"The actions that read the page: {', '.join(sorted(READ_ONLY_PAGE_ACTIONS))}"
            )
        if verb not in NAVIGATION_PAGE_ACTIONS:
            continue
        url = action[2] if len(action) > 2 else ""
        if not url or url.startswith("-"):
            # The page action names no page: `read` without a URL reads the tab
            # that is open, and an option is not a page.
            continue
        scheme = urlsplit(url).scheme.lower()
        if scheme not in ("http", "https"):
            raise SystemExit(f"the page action may only open an http page, not {url!r}")
        if not host_matches(host_of(url), approved):
            raise SystemExit(f"the page action may only open an approved host, not {host_of(url)}")
        if not approved_page(url, pages):
            raise SystemExit(
                f"the page action may only open an approved page, not {url!r} — "
                f"the pages the config approves: {', '.join(pages) or 'none'}"
            )


def run_page_action(action: list[str]) -> int:
    """Run one page action of a step and return its exit code."""
    return subprocess.run(action, cwd=REPO_ROOT, check=False).returncode


def capture_step(args: argparse.Namespace, config: dict) -> int:
    """Record one step, keep the evidence, and export it."""
    redactor = load_redactor()
    step = redactor.safe_identifier(args.name, "step")
    investigation = safe_investigation(args.investigation)
    raw_dir = RAW_ROOT / investigation
    raw_har = raw_dir / "raw" / f"{step}.har"
    evidence_har = raw_dir / f"{step}.har"
    report_path = raw_dir / f"{step}.report.json"
    exported_fixture = EVIDENCE_ROOT / investigation / "evidence" / f"{step}.json"
    raw_har.parent.mkdir(parents=True, exist_ok=True)

    command = list(args.command)
    if command and command[0] == "--":
        command = command[1:]
    actions = page_actions(command)
    if not actions:
        # Checked before the recording starts: a capture that is started and
        # never stopped keeps recording into the next one.
        raise SystemExit("no command to run — put the page action after `--`")
    # Checked before the recording starts too: an action that changes the site
    # must never run inside a capture of a logged-in session.
    check_read_only(actions, config["allowedOrigins"], config.get("allowedPages", []))

    cleared = chrome_use("network", "requests", "--clear")
    if cleared.returncode != 0:
        # The request log is what the bodies are looked up in, and the join
        # with the recording is positional. A log that still holds the requests
        # of an earlier run shifts that join, and a body may be attributed to
        # the wrong request.
        raise SystemExit(
            f"chrome-use did not clear the request log: {(cleared.stderr or cleared.stdout).strip()[:400]}"
        )

    for stale in (raw_har, evidence_har, report_path, exported_fixture):
        if stale.exists():
            # The recording of the previous run of this step. It must not
            # survive into this one: a capture that was not saved would
            # otherwise be read as this step's capture — and the filtered file
            # is what the export reads. The exported fixture is what the
            # repository holds, so a capture that fails leaves no evidence
            # standing as if it were the result of this run. The report of the
            # previous run carries its notes and its counts, and a run that
            # fails before it writes one would otherwise leave them standing.
            stale.unlink()

    started = chrome_use("network", "har", "start")
    if started.returncode != 0:
        raise SystemExit(f"chrome-use did not start recording: {(started.stderr or started.stdout).strip()[:400]}")

    failed = 0
    try:
        for action in actions:
            code = run_page_action(action)
            if code:
                failed = code
        if args.settle > 0:
            # The page action returns before the requests it started are
            # answered. A HAR that is stopped too early holds a request with no
            # status and no body, and such a request is not evidence of
            # anything.
            time.sleep(args.settle)
    finally:
        stopped = chrome_use("network", "har", "stop", str(raw_har))
        if stopped.returncode != 0:
            # The capture of this run is lost. Continuing would read the file of
            # an earlier run as if it were this one.
            raise SystemExit(f"chrome-use did not save the capture: {(stopped.stderr or stopped.stdout).strip()[:400]}")

    if not raw_har.exists():
        raise SystemExit(f"chrome-use recorded nothing — no capture at {raw_har}")

    entries = read_har(raw_har)
    kept, dropped, dropped_hosts = filter_entries(entries, config)

    listed = chrome_use_json("network", "requests").get("requests", [])
    request_ids = join_request_ids(entries, listed)

    bodies: dict[int, dict] = {}
    body_problems: list[str] = []
    if request_ids is None:
        body_problems.append("the request list and the capture did not line up, so no response body was read")
    else:
        budget = config["maxBodyFetches"]
        ambiguous = 0
        for index, _entry in kept:
            if index not in request_ids:
                ambiguous += 1
                continue
            if len(bodies) >= budget:
                body_problems.append(f"the body capture stopped after {budget} requests")
                break
            detail = chrome_use_json("network", "request", request_ids[index])
            bodies[index] = response_shape(detail)
        if ambiguous:
            body_problems.append(f"{ambiguous} requests looked the same, so no body was attributed to them")

    for index, entry in kept:
        if index in bodies:
            annotation = bodies[index]
        elif request_ids is not None and index not in request_ids:
            annotation = {
                "available": False,
                "reason": "ambiguous",
            }
        else:
            annotation = {"available": False, "reason": "not-read"}
        if not entry.get("response", {}).get("status"):
            # The browser answers a request that is still in flight with a zero
            # status. That is not an HTTP status, and the evidence must not read
            # as one.
            annotation["pending"] = True
        entry["_capture"] = annotation

    write_har(evidence_har, [entry for _index, entry in kept])

    unapproved = sorted(dropped_hosts.get("host-not-approved", set()))
    notes = [
        f"step: {step}",
        f"kept {len(kept)} of {len(entries)} recorded requests",
        f"dropped {sum(dropped.values())}: "
        + ", ".join(f"{count} {reason}" for reason, count in sorted(dropped.items())),
    ]
    if body_problems:
        notes.append("the response bodies were not read: " + "; ".join(body_problems))
    else:
        notes.append("the response bodies were read and only their shape was kept")

    report = {
        "step": step,
        "recorded": len(entries),
        "kept": len(kept),
        "dropped": dict(dropped),
        "droppedHosts": {reason: sorted(hosts) for reason, hosts in dropped_hosts.items()},
        "unapprovedApiHosts": unapproved,
        "notes": notes,
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    exported = export_investigation(investigation)

    print(f"step {step}: kept {len(kept)} of {len(entries)} requests")
    for reason, count in sorted(dropped.items()):
        print(f"  dropped {count} ({reason}): {show_hosts(dropped_hosts.get(reason, set()))}")
    for host in unapproved:
        print(
            f"  an API call went to {host}, which is neither approved nor marked as noise — approve it with --allow-host"
        )

    if failed:
        print(f"the command failed with exit code {failed}", file=sys.stderr)
        return EXIT_FAILED
    if step not in exported:
        # The step that was just recorded is not among the exported fixtures.
        # Another step of the investigation being exported is not this one.
        print(f"the fixture of step {step} was not written", file=sys.stderr)
        return EXIT_FAILED
    if unapproved:
        return EXIT_UNAPPROVED_API
    return EXIT_OK


def show_path(path: Path) -> str:
    """The path as the operator should read it: inside the repository, relative."""
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def show_hosts(hosts: set[str], limit: int = 6) -> str:
    """The hosts of a group, short enough to read on a screen."""
    ordered = sorted(hosts)
    if len(ordered) <= limit:
        return ", ".join(ordered)
    return ", ".join(ordered[:limit]) + f" and {len(ordered) - limit} more"


def export_investigation(investigation: str) -> list[str]:
    """Redact every step of an investigation into a fixture the repository can hold.

    The names of the exported steps are returned: a caller that recorded one
    step must see its own step there, not only somebody else's.
    """
    investigation = safe_investigation(investigation)
    raw_dir = RAW_ROOT / investigation
    evidence_dir = EVIDENCE_ROOT / investigation / "evidence"
    exported: list[str] = []
    for step_har in sorted(raw_dir.glob("*.har")):
        report_path = raw_dir / f"{step_har.stem}.report.json"
        notes = []
        if report_path.exists():
            notes = json.loads(report_path.read_text(encoding="utf-8")).get("notes", [])
        out = evidence_dir / f"{step_har.stem}.json"
        command = [sys.executable, str(REDACTOR), str(step_har), "--out", str(out)]
        for note in notes:
            command += ["--note", note]
        proc = subprocess.run(command, capture_output=True, text=True, check=False)
        if proc.returncode != 0:
            print(
                f"the redactor refused {step_har.name}: {(proc.stderr or proc.stdout).strip()[:300]}", file=sys.stderr
            )
            continue
        print(f"  {show_path(out)}: {proc.stdout.strip()}")
        exported.append(step_har.stem)
    if not exported:
        print(f"no capture to export for {investigation} — run the step verb first", file=sys.stderr)
    return exported


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="verb", required=True)

    step = sub.add_parser("step", help="record one named step and export it")
    step.add_argument("--investigation", required=True, help="the investigation id")
    step.add_argument("--name", required=True, help="the name of the step")
    step.add_argument("--config", default=str(CONFIG_PATH), help="the capture config to use")
    step.add_argument("--allow-host", action="append", default=[], help="a host to approve for this run")
    step.add_argument("--settle", type=float, default=3.0, help="seconds to wait for the requests to be answered")
    step.add_argument("command", nargs=argparse.REMAINDER, help="the page action, after `--`")

    export = sub.add_parser("export", help="redact the captures of an investigation again")
    export.add_argument("--investigation", required=True, help="the investigation id")

    args = parser.parse_args(argv)
    if args.verb == "export":
        if not export_investigation(args.investigation):
            return EXIT_FAILED
        return EXIT_OK

    config = load_config(Path(args.config), args.allow_host)
    return capture_step(args, config)


if __name__ == "__main__":
    raise SystemExit(main())

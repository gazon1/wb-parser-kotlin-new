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

The site may answer the page the operator opens with another page. The browser
follows it, and the browser is also the one that says which frames the page
has: a frame that is still there is a frame, not a place the session went. So
the tool reads the answer in the browser: a session that left the approved
pages, or that visited a page the config does not approve, makes the step a
failed one, and the capture of a failed step is not exported.

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
from urllib.parse import SplitResult, unquote, urljoin, urlsplit

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

# A separator of a path, hidden behind an escape: `%2f` and `%5c`. A browser
# sends them as they are written, but a server or a proxy may decode them, and
# then the path is another path. The check cannot know how the page is routed,
# so it refuses a path that carries one.
ENCODED_SEPARATOR = re.compile(r"%2f|%5c", re.IGNORECASE)

# How many layers of percent-escapes are read. The redactor judges a value the
# same way, and the check has to be at least as deep as the redactor: a path
# that hides a dot segment or a separator behind two layers of escapes
# (`%252e%252e`, `%252f`) is another path to a server that decodes twice.
DECODE_LAYERS = 3

# The port a scheme answers on when the URL does not name one. The origin of a
# URL is the scheme, the host and the port, so a page the config approves is
# the page on that port and not the same host on another one.
DEFAULT_PORTS = {"http": 80, "https": 443}

# A backslash in a URL. A browser reads it as a slash in an http(s) URL, so a
# URL that carries one names another page than the parser here reads — and the
# page that is executed must be the page that was judged.
BACKSLASH = "\\"

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

# The answers that send the browser somewhere else. A page the site answers
# with a redirect is the site choosing the page — and the page it chooses may
# be an action endpoint the config does not approve.
REDIRECT_STATUSES = {301, 302, 303, 307, 308}

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
    config["allowedQueryParams"] = list(config.get("allowedQueryParams", []))
    config["noiseHosts"] = list(config.get("noiseHosts", []))
    config["keptResourceTypes"] = list(config.get("keptResourceTypes", list(API_RESOURCE_TYPES)))
    config["maxBodyFetches"] = int(config.get("maxBodyFetches", 50))
    return config


def url_parts(url: str) -> SplitResult | None:
    """The parts of a URL, or `None` when the URL cannot be read at all.

    A URL with a broken host — an unclosed IPv6 bracket — is not a URL the
    parser can read, and a URL that cannot be read names no host, so no page
    of it can be approved.
    """
    try:
        return urlsplit(url)
    except ValueError:
        return None


def host_of(url: str) -> str:
    """The host of a URL, lowercased. An empty string for a URL without one."""
    parts = url_parts(url)
    if parts is None:
        return ""
    try:
        return (parts.hostname or "").lower()
    except ValueError:
        return ""


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


def origin_label(origin: tuple[str, str, int]) -> str:
    """An origin as the operator reads it: the scheme, the host and the port."""
    scheme, host, port = origin
    return f"{scheme}://{host}:{port}"


def approved_origin(url: str, patterns: list[str]) -> bool:
    """Whether the URL is one of the approved origins.

    An approval is of an origin, and not of a host: the same host on another
    port is another service, and a request of another service is not traffic of
    the approved one — so it is not approved, and the tool says so loudly
    instead of keeping it as evidence of the approved host. The same goes for
    the scheme: the plain-http origin of an https host is another origin.

    A pattern names a host, and the origin it approves is the one the site
    serves: https on the default port. A pattern that names an origin in full
    (`https://api.example.ru:8443`) approves that origin and nothing else, so
    the operator can approve a service on a port of its own.
    """
    origin = origin_of(url)
    if origin is None:
        return False
    scheme, host, port = origin
    for pattern in patterns:
        pattern = pattern.strip()
        if not pattern:
            continue
        if "://" in pattern:
            named = origin_of(pattern)
            if named is not None and named == origin:
                return True
            continue
        if scheme != "https" or port != DEFAULT_PORTS["https"]:
            continue
        if host_matches(host, [pattern]):
            return True
    return False


def approved_host(host: str, patterns: list[str]) -> bool:
    """Whether a host is one of the hosts the patterns name.

    A pattern that names an origin in full (`https://api.example.ru:8443`)
    approves that origin, and the host of it is a host the operator has in
    mind: a request of the same host on another port is a request of an
    approved host and not of a host nobody knows. The origin comparison still
    decides whether the request is approved — this says only which host the
    operator had in mind, so that the report names the origin of such a
    request instead of an unknown host.
    """
    names: list[str] = []
    for pattern in patterns:
        pattern = pattern.strip()
        if not pattern:
            continue
        if "://" in pattern:
            named = origin_of(pattern)
            if named is not None:
                names.append(named[1])
            continue
        names.append(pattern)
    return host_matches(host, names)


def decode_layers(text: str) -> str:
    """The text as a server that decodes it again and again would read it.

    A percent-escape may hide another percent-escape: `%252e` is `%2e` to a
    server that decodes twice, and `..` to a server that decodes three times.
    The redactor judges a value this way, and the check judges a path the same
    way — a path the check reads one way and the server another is a path the
    check cannot judge.
    """
    for _ in range(DECODE_LAYERS):
        decoded = unquote(text)
        if decoded == text:
            break
        text = decoded
    return text


def hides_separator(text: str) -> bool:
    """Whether the text hides a separator of a path behind one or more escapes.

    `%2f` and `%252f` are the same separator to a server that decodes once and
    to a server that decodes twice, so the check reads the escapes layer by
    layer: it cannot know how many times the path is decoded, and a path it
    cannot read is a path it refuses.
    """
    for _ in range(DECODE_LAYERS):
        if ENCODED_SEPARATOR.search(text):
            return True
        decoded = unquote(text)
        if decoded == text:
            break
        text = decoded
    return False


def page_path(url: str) -> str:
    """The path of a URL, resolved: the dot segments are the segments they are.

    A browser resolves the segments of a path before it asks for the page, so
    a check that does not resolve them would approve a path that the browser
    reads as another one. A dot segment is a dot segment however it is
    written: `.`, `%2e` and `..`, `%2e%2e`, `.%2e` are the same two segments —
    and behind the escapes as well: `%252e%252e` is `..` to a server that
    decodes twice. The segment is judged as it is read layer by layer, and the
    path keeps the spelling the browser sees.
    """
    parts = url_parts(url)
    if parts is None:
        return "/"
    path = parts.path
    if not path or path == "/":
        return "/"
    segments: list[str] = []
    for segment in path.split("/"):
        decoded = decode_layers(segment)
        if decoded == "..":
            if segments:
                segments.pop()
            continue
        if decoded == ".":
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
    parts = url_parts(url)
    if parts is None:
        return None
    scheme = parts.scheme.lower()
    try:
        port = parts.port
        host = (parts.hostname or "").lower()
    except ValueError:
        return None
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
    url = entry.get("request", {}).get("url", "")
    host = host_of(url)
    resource_type = entry.get("_resourceType", "")
    if approved_origin(url, config["allowedOrigins"]):
        if resource_type in config["keptResourceTypes"]:
            return None, host
        return "type-not-kept", host
    if host_matches(host, config["noiseHosts"]):
        return "noise-host", host
    if approved_host(host, config["allowedOrigins"]):
        # The host is approved, and the origin is not: another port of the same
        # host, or the plain-http origin of an https one. The host is named in
        # the report, and the origin with it — the operator has to approve the
        # origin, not the host it belongs to.
        origin = origin_of(url)
        return "origin-not-approved", origin_label(origin) if origin else host
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
        # The size of the body as it would be written: a character no UTF-8
        # file can hold is replaced, and the size of the body as it is stored
        # is the size that matters. Counting the bytes of strict UTF-8 would
        # end the capture on a body nobody can read.
        "bodyBytes": len(body.encode("utf-8", "replace")),
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
    path.write_text(
        load_redactor().writable(json.dumps(document, indent=2, ensure_ascii=False) + "\n"), encoding="utf-8"
    )


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


def query_names(url: str) -> list[str]:
    """The names of the query parameters of a URL, as the server reads them.

    The name is read the way the server reads it: a percent-escape in a name
    is a character of the name, so `%73earch` is the name `search`. The value
    is not read — the name is what the server switches on, and the value of an
    approved name is free text.
    """
    parts = url_parts(url)
    if parts is None:
        return []
    names = []
    for pair in parts.query.split("&"):
        if not pair:
            continue
        names.append(unquote(pair.split("=", 1)[0]))
    return names


def check_read_only(
    actions: list[list[str]],
    approved: list[str],
    pages: list[str],
    query_params: list[str] | None = None,
) -> None:
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

    The query of the page is a request as well: a parameter can switch an
    action on, so a parameter the config does not name is refused. The names
    the config approves are the query parameters of the page.
    """
    allowed_query = list(query_params or [])
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
            # The action names no page: `read` without a URL reads the tab that
            # is open, and that page is one the tool never approved. An option
            # in that place is the same case: the page is further along the
            # command, where the tool does not read it — and a page it does
            # not read is a page it cannot approve.
            raise SystemExit(
                f"the page action must name the page it opens: {verb} takes the URL right after it, "
                f"and a page the tool cannot read is a page it cannot approve"
            )
        if BACKSLASH in url:
            # The browser reads a backslash as a slash, so the page it would
            # open is not the page this check reads. A URL we cannot read the
            # way the browser reads it is a URL we refuse to judge.
            raise SystemExit(f"the page action may only open a URL as the browser reads it, not {url!r}")
        parts = url_parts(url)
        if parts is None:
            raise SystemExit(f"the page action may only open a page, not {url!r} — the URL cannot be read")
        scheme = parts.scheme.lower()
        if scheme not in ("http", "https"):
            raise SystemExit(f"the page action may only open an http page, not {url!r}")
        if not approved_origin(url, approved):
            # The approval is of an origin, and not of a host: the same host on
            # another port is another service, and the plain-http origin of an
            # https host is another origin. A navigation is judged by the same
            # measure the API traffic is judged by, so an origin the config
            # names in full passes here as it does there.
            origin = origin_of(url)
            raise SystemExit(
                f"the page action may only open an approved origin, not {origin_label(origin) if origin else host_of(url)}"
            )
        if hides_separator(parts.path):
            # A separator behind an escape is another path to a server that
            # decodes it, and the check cannot know how the page is routed.
            raise SystemExit(
                f"the page action may only open a page whose path does not hide a separator behind an escape, not {url!r}"
            )
        if not approved_page(url, pages):
            raise SystemExit(
                f"the page action may only open an approved page, not {url!r} — "
                f"the pages the config approves: {', '.join(pages) or 'none'}"
            )
        for name in query_names(url):
            if name not in allowed_query:
                raise SystemExit(
                    f"the page action may only open a page whose query the config names, not {url!r} — "
                    f"the query parameters the config approves: {', '.join(allowed_query) or 'none'}"
                )


def redirect_target(entry: dict) -> str | None:
    """The page a redirect in the recording sends the browser to.

    The location of a redirect is what the site answers with, not what the
    operator asked for: the browser follows it, so it is a request of its own.
    """
    response = entry.get("response", {})
    if response.get("status") not in REDIRECT_STATUSES:
        return None
    for header in response.get("headers", []) or []:
        if str(header.get("name", "")).strip().lower() == "location":
            location = str(header.get("value", "")).strip()
            return location or None
    return None


def navigation_targets(entries: list[dict]) -> list[str]:
    """The pages the navigation was redirected to.

    Only a navigation is followed by the browser as a page: a redirect of an
    API call is another API call, and the traffic filter judges it like any
    other request. A redirect of the page the operator opened is the site
    answering with another page — the one place where the site, and not the
    operator, chooses where the session goes.
    """
    targets: list[str] = []
    for entry in entries:
        if entry.get("_resourceType") != "Document":
            continue
        target = redirect_target(entry)
        if target is None:
            continue
        request_url = str(entry.get("request", {}).get("url", ""))
        targets.append(urljoin(request_url, target))
    return targets


def session_page() -> str:
    """The page the browser is on now: the main frame of the tab the step used.

    The recording cannot tell a frame of another site from a navigation to it —
    both are a document in it. The browser knows: the main frame of the tab is
    one page, and a frame of another site is not it. An empty string is a
    browser that could not answer.
    """
    proc = chrome_use("get", "url", "--json")
    text = (proc.stdout or "") + (proc.stderr or "")
    start = text.find("{")
    if proc.returncode != 0 or start < 0:
        return ""
    try:
        payload = json.loads(text[start:])
    except json.JSONDecodeError:
        return ""
    data = payload.get("data") or {}
    return str(data.get("url", ""))


def browser_frames() -> list[dict] | None:
    """The frames the browser shows: the tab itself and the frames inside it.

    The recording cannot tell the main frame of the tab from a frame of
    another site — both are documents in it. The browser tells them apart: it
    names the frame of the tab and the frames inside it. `None` when the
    browser cannot say, and a step that cannot tell a frame from the session
    is a step that cannot judge where the session went.
    """
    proc = chrome_use("frames", "--json")
    text = (proc.stdout or "") + (proc.stderr or "")
    start = text.find("{")
    if proc.returncode != 0 or start < 0:
        return None
    try:
        payload = json.loads(text[start:])
    except json.JSONDecodeError:
        return None
    data = payload.get("data") or {}
    frames = data.get("frames")
    if not isinstance(frames, list):
        return None
    named: list[dict] = []
    for frame in frames:
        if not isinstance(frame, dict):
            continue
        url = str(frame.get("url", ""))
        if url:
            named.append({"kind": str(frame.get("kind", "")), "url": url})
    return named


def same_page(url: str, other: str) -> bool:
    """Whether two URLs name the same page.

    The page is the origin and the path: the query of a frame is the frame's
    own business, and it says nothing about which page this is.
    """
    left = origin_of(url)
    right = origin_of(other)
    if left is None or right is None or left != right:
        return False
    return page_path(url) == page_path(other)


def document_urls(entries: list[dict]) -> list[str]:
    """The pages the recording shows as documents.

    A document is a page: the page the operator opened, the page a redirect
    sent the browser to, and the page the page itself sent the browser to — a
    script that replaces the location is a navigation the site made, and the
    recording shows it as a document of its own. A frame of another site is a
    document as well, and the recording cannot tell the two apart: the pages
    here are named in the report, and the browser says where the session is.
    """
    urls: list[str] = []
    for entry in entries:
        if entry.get("_resourceType") != "Document":
            continue
        url = str(entry.get("request", {}).get("url", ""))
        if url:
            urls.append(url)
    return urls


def approved_navigation(url: str, approved: list[str], pages: list[str], allowed_query: list[str]) -> bool:
    """Whether a navigation the recording shows is one the config approves.

    The same three rules the page action is judged by: the host is approved,
    the page is approved, and the query carries nothing the config does not
    name. A navigation the operator did not ask for is held to the same
    measure.
    """
    if BACKSLASH in url:
        # The browser reads a backslash as a slash, so the page it would open
        # is not the page this check reads: `https://a.example\@b.example/` is
        # the host `b.example` to the parser and the host `a.example` to the
        # browser. A page the tool cannot read the way the browser reads it is
        # a page it cannot approve.
        return False
    parts = url_parts(url)
    if parts is None:
        return False
    if parts.scheme.lower() not in ("http", "https"):
        return False
    if not approved_origin(url, approved):
        # The approval is of an origin, and not of a host: the same host on
        # another port is another service, and the plain-http origin of an
        # https host is another origin. A navigation is judged by the same
        # measure the API traffic is judged by.
        return False
    if hides_separator(parts.path):
        return False
    if not approved_page(url, pages):
        return False
    return all(name in allowed_query for name in query_names(url))


def session_problem(url: str, approved: list[str], pages: list[str], allowed_query: list[str]) -> str:
    """Why the session is not on a page the config approves, and an empty
    string when it is.

    The origin, the page and the query are what is judged: a query parameter
    can switch an action on just as a path can, and the site can put one on
    the page it keeps the session on without asking — `history.pushState`
    changes the address of the page without a request of its own, and the
    address is what the session is judged by. So a parameter the config does
    not name is refused, and the operator names it in `allowedQueryParams` if
    the site needs it.

    A URL the browser reads differently than the parser reads it — a
    backslash — is not a URL this check can read, so it is not approved. A
    path that hides a separator behind an escape is refused as well: the
    browser sends it as it is written, but a server or a proxy may decode it,
    and then it is another path — the path the check reads is not the path
    the server routes.
    """
    if not url:
        # The browser could not say where the session is. A step whose evidence
        # says nothing about where the session went shows nothing about what
        # the step did, so it is not a successful one.
        return "the browser did not tell which page the session is on"
    if BACKSLASH in url:
        # The browser reads a backslash as a slash, so the page the session is
        # on is not the page this check reads: `https://a.example\@b.example/`
        # is the host `b.example` to the parser and the host `a.example` to the
        # browser. A page the tool cannot read the way the browser reads it is
        # a page it cannot approve.
        return f"the session is on a page the tool cannot read the way the browser reads it: {url}"
    parts = url_parts(url)
    if parts is None:
        return f"the session is on a page the tool cannot read at all: {url}"
    if hides_separator(parts.path):
        return f"the session is on a page whose path hides a separator behind an escape: {url}"
    if not approved_origin(url, approved):
        return f"the session left the approved origins: {url}"
    if not approved_page(url, pages):
        return f"the session left the approved pages: {url}"
    for name in query_names(url):
        if name not in allowed_query:
            return f"the session is on a page whose query the config does not name: {url}"
    return ""


def run_page_action(action: list[str]) -> int:
    """Run one page action of a step and return its exit code."""
    return subprocess.run(action, cwd=REPO_ROOT, check=False).returncode


def capture_step(args: argparse.Namespace, config: dict) -> int:
    """Record one step, keep the evidence, and export it."""
    redactor = load_redactor()
    step = redactor.safe_identifier(args.name, "step")
    if step != args.name:
        # The name of a step is the name of the file it produces. A name the
        # rules would reduce is refused: two names can reduce to one (`load/page`
        # and `load-page`), and the second step would delete the evidence of
        # the first one.
        raise SystemExit(f"the name of a step is one name, not something to reduce: {args.name!r} would be {step!r}")
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
    check_read_only(
        actions,
        config["allowedOrigins"],
        config.get("allowedPages", []),
        config.get("allowedQueryParams", []),
    )

    cleared = chrome_use("network", "requests", "--clear")
    if cleared.returncode != 0:
        # The request log is what the bodies are looked up in, and the join
        # with the recording is positional. A log that still holds the requests
        # of an earlier run shifts that join, and a body may be attributed to
        # the wrong request.
        raise SystemExit(
            f"chrome-use did not clear the request log: {(cleared.stderr or cleared.stdout).strip()[:400]}"
        )

    # The exported fixture goes first: it is the artifact the repository
    # holds, and a stale fixture read as the result of this run is worse than
    # a stale recording in the cache. The other artifacts are the recording of
    # the previous run, and they must not survive into this one — a capture
    # that was not saved would otherwise be read as this step's capture, and
    # the filtered file is what the export reads. The report of the previous
    # run carries its notes and its counts, and a run that fails before it
    # writes one would otherwise leave them standing.
    for stale in (exported_fixture, raw_har, evidence_har, report_path):
        if stale.exists():
            try:
                stale.unlink()
            except OSError as error:
                # The previous run's artifact stays. The step cannot be
                # trusted to overwrite it, so the step stops here rather than
                # leave a stale artifact standing as if it were this run's.
                raise SystemExit(
                    f"the previous run of this step could not be removed: {stale} ({error.strerror or error})"
                ) from error

    started = chrome_use("network", "har", "start")
    if started.returncode != 0:
        raise SystemExit(f"chrome-use did not start recording: {(started.stderr or started.stdout).strip()[:400]}")

    failed = 0
    action_failure = ""
    try:
        for action in actions:
            try:
                code = run_page_action(action)
            except OSError as error:
                # The page action could not be run at all — the command is not
                # there, or it cannot be executed. The step did not reach the
                # state it was supposed to reach, so it is a failed step like
                # any other: its report says so and the export skips it.
                action_failure = f"the page action could not be run: {error}"
                failed = EXIT_FAILED
                break
            if code:
                failed = code
                # The actions after the failure are not run: the step did not
                # reach the state it was supposed to reach, and the requests
                # of the later actions would be recorded as if the step had
                # completed.
                break
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

    # The site may answer the page the operator opened with another page. The
    # browser follows it, so a redirect can carry the session to an action
    # endpoint the config forbids — and the tool cannot know that before the
    # site answers.
    #
    # The recording cannot tell the main frame of the tab from a frame of
    # another site: both are documents in it. The browser tells them apart —
    # it names the frame of the tab and the frames inside it. The frame of the
    # tab is the session itself: a document of it is a navigation of the
    # session, and it is held to the same measure as any other navigation —
    # the origin, the page and the query.
    #
    # A page that is neither an approved navigation nor a frame the page
    # still shows is a page the session visited and left: the step fails,
    # because the capture of a session that went somewhere else is not
    # evidence of what the step shows.
    pages = config.get("allowedPages", [])
    noise = config.get("noiseHosts", [])
    origins = config["allowedOrigins"]
    allowed_query = config.get("allowedQueryParams", [])
    frames = browser_frames()
    frame_pages = [frame["url"] for frame in (frames or []) if not approved_page(frame["url"], pages)]
    # A frame is a frame, and not a place the session went: the browser says
    # which frames the page has, so a document of a frame is judged by the
    # rule of the frame and not by the rule of the session. A frame of another
    # site is exempt: its document request goes to a host of its own, and it
    # cannot reach an endpoint of the site the capture observes. A frame of an
    # approved host is a request of the site, and the site may keep a frame on
    # a page the config does not name — so such a frame is held to the same
    # measure as any other navigation: the page and the query.
    inline = [frame["url"] for frame in (frames or []) if frame.get("kind") == "inline"]
    own = [url for url in inline if approved_host(host_of(url), origins)]
    visited: list[str] = []
    if frames is not None:
        for url in dict.fromkeys(document_urls(entries) + navigation_targets(entries)):
            if host_matches(host_of(url), noise):
                # The traffic of a noise host carries no API, and the operator
                # has said so: a page of such a host is not where the session
                # went.
                continue
            if approved_navigation(url, origins, pages, allowed_query):
                continue
            if any(same_page(url, frame) for frame in inline):
                # A frame that is still there is a frame, and not a place the
                # session went: the browser says so.
                continue
            visited.append(url)

    # The browser tells the main frame of the tab from a frame of another site:
    # the main frame of the tab is the page the session is on. A session that
    # left the approved pages observes something else, so the step fails: the
    # capture of a failed step is not evidence of what the step shows.
    problems: list[str] = []
    if frames is None:
        # The browser could not say which frames the page has. A step that
        # cannot tell a frame from the session cannot say where the session
        # went, so it is not a successful one.
        problems.append("the browser did not tell which frames the page has")
    session_reason = session_problem(session_page(), origins, pages, allowed_query)
    if session_reason:
        problems.append(session_reason)
    if visited:
        problems.append("the session visited a page the config does not approve: " + "; ".join(visited[:3]))
    unapproved_frames = [url for url in own if not approved_navigation(url, origins, pages, allowed_query)]
    if unapproved_frames:
        # A frame of an approved origin is a request of the site, and a
        # request may change what it is asked to change: the frame is on a
        # page the config does not name, or it carries a query the config
        # does not name. The frame may have been loaded before the recording
        # started, so the recording cannot be asked about it.
        problems.append("the page shows a frame the config does not approve: " + "; ".join(unapproved_frames[:3]))
    if problems:
        failed = failed or EXIT_FAILED

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

    unapproved = sorted(dropped_hosts.get("host-not-approved", set()) | dropped_hosts.get("origin-not-approved", set()))
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
    if action_failure:
        notes.append(action_failure)
    else:
        for reason in problems:
            notes.append(reason)
        if not problems and failed:
            notes.append(f"the page action failed with exit code {failed}, so the capture is partial")
    if frame_pages:
        notes.append("the page shows frames the config does not name as pages: " + "; ".join(frame_pages[:3]))

    report = {
        "step": step,
        "recorded": len(entries),
        "kept": len(kept),
        "dropped": dict(dropped),
        "droppedHosts": {reason: sorted(hosts) for reason, hosts in dropped_hosts.items()},
        "unapprovedApiHosts": unapproved,
        "frames": frames,
        "failedExitCode": failed,
        "notes": notes,
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        load_redactor().writable(json.dumps(report, indent=2, ensure_ascii=False) + "\n"), encoding="utf-8"
    )

    exported = export_investigation(investigation)

    print(f"step {step}: kept {len(kept)} of {len(entries)} requests")
    for reason, count in sorted(dropped.items()):
        print(f"  dropped {count} ({reason}): {show_hosts(dropped_hosts.get(reason, set()))}")
    for host in unapproved:
        print(
            f"  an API call went to {host}, which is neither approved nor marked as noise — "
            "approve it with --allow-host"
        )

    if failed:
        print(
            action_failure or "; ".join(problems) or f"the command failed with exit code {failed}",
            file=sys.stderr,
        )
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


def read_report(path: Path) -> dict | None:
    """The report of a step, or None when the step did not finish.

    A step that ran to the end always leaves a report behind. A report that is
    not there, or that cannot be read, is the remains of a run that died: the
    page action raised, the step was interrupted, and nothing was written down
    about what the step showed.
    """
    if not path.exists():
        return None
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    return report if isinstance(report, dict) else None


def export_investigation(investigation: str) -> list[str]:
    """Redact every step of an investigation into a fixture the repository can hold.

    The names of the exported steps are returned: a caller that recorded one
    step must see its own step there, not only somebody else's.
    """
    investigation = safe_investigation(investigation)
    raw_dir = RAW_ROOT / investigation
    evidence_dir = EVIDENCE_ROOT / investigation / "evidence"
    exported: list[str] = []
    failed_steps: list[str] = []
    unfinished_steps: list[str] = []
    for step_har in sorted(raw_dir.glob("*.har")):
        out = evidence_dir / f"{step_har.stem}.json"
        # The fixture of the previous export is removed before the step is
        # judged: a capture that this export refuses must not leave the
        # fixture of an earlier run standing as if it were the result of it.
        if out.exists():
            try:
                out.unlink()
            except OSError as error:
                # The fixture of the previous run could not be removed, so the
                # step cannot be exported: a new fixture would stand next to
                # the old one, and the old one would be read as the result of
                # this run.
                print(
                    f"  {step_har.stem}: the fixture of the previous run could not be removed: {error}", file=sys.stderr
                )
                failed_steps.append(step_har.stem)
                continue
        report_path = raw_dir / f"{step_har.stem}.report.json"
        report = read_report(report_path)
        if report is None:
            # The step did not finish, so its capture is not evidence of what
            # the step shows — neither now nor at a later export.
            print(f"  {step_har.stem}: the step did not finish, so its capture is not exported", file=sys.stderr)
            unfinished_steps.append(step_har.stem)
            continue
        notes = report.get("notes", [])
        failed = bool(report.get("failedExitCode"))
        if failed:
            # A step whose action failed is a partial capture. It is not
            # evidence of what the step shows, so it does not become a fixture
            # of the repository — neither now nor at a later export.
            print(f"  {step_har.stem}: the step failed, so its partial capture is not exported", file=sys.stderr)
            failed_steps.append(step_har.stem)
            continue
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
        if failed_steps or unfinished_steps:
            reasons = []
            if failed_steps:
                reasons.append("every step failed: " + ", ".join(failed_steps))
            if unfinished_steps:
                reasons.append("no step finished: " + ", ".join(unfinished_steps))
            print(f"no capture to export for {investigation} — {'; '.join(reasons)}", file=sys.stderr)
        else:
            print(f"no capture to export for {investigation} — run the step verb first", file=sys.stderr)
    return exported


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="verb", required=True)

    step = sub.add_parser("step", help="record one named step and export it")
    step.add_argument("--investigation", required=True, help="the investigation id")
    step.add_argument("--name", required=True, help="the name of the step")
    step.add_argument("--config", default=str(CONFIG_PATH), help="the capture config to use")
    step.add_argument("--allow-host", action="append", default=[], help="a host or an origin to approve for this run")
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

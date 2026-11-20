#!/usr/bin/env python3
"""Validate a discovered API candidate with controlled experiments.

`capture.py` records what the site sent; `plan.py` runs an approved experiment.
Neither of them says whether a candidate endpoint tolerates a parameter, what a
sort order does to the answer, or where a page boundary is. That is this tool:
it replays the same request in the same browser context, varies one thing at a
time, and writes down what happened next to the evidence.

The rules come from the issue that asked for this workflow:

- one variation per experiment — a query parameter, the credentials mode, or a
  page reload; two differences at once prove nothing about either of them;
- every conclusion points at the observations that support it — a claim without
  evidence is a hypothesis, not a result;
- a sample of one does not validate anything: `validated` needs the baseline
  reproduced and the variation repeated with the same outcome;
- a failed, blocked or inconclusive experiment is never reported as a
  successful validation. The tool says which one it was and why.

The plan is `devtools/wb-research/investigations/<id>/experiments.yaml`, written
before the run and approved by the operator, the same way `plan.yaml` is. The
run writes three files next to it:

    validation.yaml        the machine-readable summary
    validation-report.md   the report a human reads
    evidence/validation.json   the sanitized observations

Header values are never in any of them. The plan names the headers to send, a
local file outside the repository supplies their values, and the evidence keeps
the names that went out and the names the browser dropped. The query is part of
the plan, and part of the approval: a value the record redacted has to be
written in the plan, because a value the local file supplied would be outside
the scope the operator approved.

Read-only: every experiment is a GET replay from the page's own context, with
cookies included by default. The tool does not click, does not type, does not
attempt a bypass, and waits between requests.

Usage:
    validate.py approve --investigation <id>
    validate.py run --investigation <id> [--dry-run]
"""

from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import hashlib
import importlib.util
import json
import re
import subprocess
import time
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CAPTURE = HERE / "capture.py"
REDACTOR = HERE / "redact.py"
CONFIG_PATH = REPO_ROOT / "devtools" / "wb-research" / "capture-config.json"
INVESTIGATIONS = REPO_ROOT / "devtools" / "wb-research" / "investigations"
RAW_ROOT = Path.home() / ".cache" / "wb-research"

PLAN_SCHEMA = "wb-research/validation-plan/v1"
EVIDENCE_SCHEMA = "wb-research/validation/v1"

PLAN_FILE = "experiments.yaml"
RECORD_FILE = "validation.yaml"
REPORT_FILE = "validation-report.md"
EVIDENCE_FILE = Path("evidence") / "validation.json"

SUGGESTED_PLAN = """schema: wb-research/validation-plan/v1
id: <investigation-id>
candidate: <the id of an api_candidate in investigation.yaml>
page: <the page whose context the replay runs in>
request:
  query:
    <name>: <value>
  headers: [<header names to send>]
  credentials: include
min_interval_seconds: 2
experiments:
  - name: baseline
    kind: replay
    repeat: 2
  - name: <a variant>
    vary:
      <param>: <value>
    on: [order]
    expect: changed
    repeat: 2
"""

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_BLOCKED = 2

# The answers that say the site refused the session rather than answered it.
# WB's own anti-bot answer is 498; the rest are the ordinary refusals.
BLOCKED_STATUSES = {401, 403, 429, 498}

# The failures the probe and the browser wrapper report. They are codes, not the
# browser's message: that text quotes the URL and the request, so it is never
# written down.
PROBE_ERRORS = {
    "fetch_failed",
    "body_unreadable",
    "probe_refused",
    "probe_result_unreadable",
    "browser_unavailable",
}

# The states of the validation model. `observed` is one sample, `reproduced` is
# the same answer from an independent run, `validated` is a controlled
# experiment whose expectation held more than once, `rejected` is a stable
# answer that contradicts the expectation, and `inconclusive` is everything the
# evidence does not settle.
OBSERVED = "observed"
REPRODUCED = "reproduced"
VALIDATED = "validated"
REJECTED = "rejected"
INCONCLUSIVE = "inconclusive"

COMPLETED = "completed"
FAILED = "failed"
BLOCKED = "blocked"

OUTCOMES = (COMPLETED, FAILED, BLOCKED, INCONCLUSIVE)

# The parts of an observation a variant is compared on. A status, a media type,
# whether the body is JSON, the shape of the answer, the number of products,
# their order and the total are what a parameter can plausibly change; a byte
# count is not — the same answer is not the same size twice.
COMPARED_FIELDS = ("status", "contentType", "json", "schema", "count", "order", "total")

# How many product ids one observation keeps. A catalog page is a hundred
# products; the cap is only a guard against a malformed answer, not a filter.
MAX_PRODUCT_IDS = 200

MAX_REPEAT = 5
MAX_INTERVAL = 60.0

# A value in the committed plan that stands for a value the operator did not
# write down: `<redacted:query-param>`. It is resolved from the local file, or
# the run is refused — a request replayed without the value it was seen with is
# not the same request.
PLACEHOLDER = re.compile(r"<redacted:[a-z0-9-]+>")

# The id of an investigation becomes a directory name in the repository and in
# the raw cache: one name, not a path. The same rule as in `capture.py`.
INVESTIGATION_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")

_REDACTOR = None
_CAPTURE = None

# The probe is one page-JavaScript expression. It reads the answer as text (WB
# answers the catalog API with `text/plain`, so the content type says nothing
# about the body), keeps the shape and the product ids and never returns the
# body. The forbidden-header rule is the one the site adapter checked against a
# real Chrome request: a header in that set is dropped by the browser without
# an error, and a request that looks like it sent one did not.
PROBE_SCRIPT = r"""
async (spec) => {
  const FORBIDDEN = [
    'accept-charset', 'accept-encoding', 'access-control-request-headers',
    'access-control-request-method', 'connection', 'content-length', 'cookie',
    'cookie2', 'date', 'dnt', 'expect', 'host', 'keep-alive', 'origin',
    'referer', 'set-cookie', 'te', 'trailer', 'transfer-encoding',
    'upgrade', 'via', 'user-agent'
  ];
  const FORBIDDEN_PREFIXES = ['proxy-', 'sec-'];
  const FORBIDDEN_BY_VALUE = ['x-http-method', 'x-http-method-override', 'x-method-override'];
  const FORBIDDEN_METHODS = ['connect', 'trace', 'track'];
  const asciiLower = (value) => value.replace(/[A-Z]/g, (letter) => letter.toLowerCase());

  const headers = spec.headers || {};
  const names = Object.keys(headers);
  const dropped = names.filter((name) => {
    const low = name.toLowerCase();
    if (FORBIDDEN.indexOf(low) >= 0) return true;
    if (FORBIDDEN_PREFIXES.some((prefix) => low.indexOf(prefix) === 0)) return true;
    if (FORBIDDEN_BY_VALUE.indexOf(low) < 0) return false;
    return String(headers[name]).split(',')
      .map((method) => method.replace(/^[\t\n\r ]+|[\t\n\r ]+$/g, ''))
      .some((method) => FORBIDDEN_METHODS.indexOf(asciiLower(method)) >= 0);
  });

  let resp;
  try {
    resp = await fetch(spec.url, {
      method: spec.method || 'GET',
      headers: headers,
      credentials: spec.credentials || 'include',
      cache: 'no-store',
      redirect: 'error'
    });
  } catch (e) {
    return {error: 'fetch_failed', headerNames: names, droppedByBrowser: dropped};
  }

  let text = '';
  let bodyError = null;
  try {
    text = await resp.text();
  } catch (e) {
    bodyError = 'body_unreadable';
  }

  const out = {
    url: spec.url,
    method: spec.method || 'GET',
    credentials: spec.credentials || 'include',
    headerNames: names,
    droppedByBrowser: dropped,
    status: resp.status,
    contentType: resp.headers.get('content-type') || '',
    bodyBytes: new TextEncoder().encode(text).length,
    isJson: false,
    topLevelKeys: null,
    productCount: null,
    total: null,
    productIds: [],
    error: bodyError
  };

  if (bodyError) return out;
  try {
    const data = JSON.parse(text);
    out.isJson = true;
    if (data && typeof data === 'object' && !Array.isArray(data)) {
      out.topLevelKeys = Object.keys(data);
      const products = Array.isArray(data.products) ? data.products
        : (data.data && Array.isArray(data.data.products)) ? data.data.products : null;
      if (products) {
        out.productCount = products.length;
        out.productIds = products
          .map((product) => (product && Number.isInteger(product.id)) ? product.id : null)
          .filter((id) => id !== null);
      }
      if (Number.isInteger(data.total)) out.total = data.total;
    }
  } catch (e) {
    out.isJson = false;
  }
  return out;
}
"""


def load_module(name: str, path: Path):
    """A script next door, loaded once. Its rules are the rules here."""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def redactor():
    """The redactor, which is the only thing that decides what may be committed."""
    global _REDACTOR
    if _REDACTOR is None:
        _REDACTOR = load_module("wb_research_redact", REDACTOR)
    return _REDACTOR


def capture():
    """The capture, whose origin and page checks are the checks here."""
    global _CAPTURE
    if _CAPTURE is None:
        _CAPTURE = load_module("wb_research_capture", CAPTURE)
    return _CAPTURE


def safe_investigation(value: str) -> str:
    """The id of an investigation, checked to be a single safe path component."""
    text = str(value or "").strip()
    if not text or ".." in text or not INVESTIGATION_NAME.fullmatch(text):
        raise SystemExit(f"the investigation id must be a single name, not a path: {value!r}")
    return text


def investigation_dir(investigation: str) -> Path:
    """The directory of an investigation in the repository."""
    return INVESTIGATIONS / safe_investigation(investigation)


def plan_path(investigation: str) -> Path:
    """The validation plan of an investigation."""
    return investigation_dir(investigation) / PLAN_FILE


def raw_dir(investigation: str) -> Path:
    """Where the raw observations of an investigation stay: outside the repository."""
    return RAW_ROOT / safe_investigation(investigation) / "validation"


def local_request_file(plan: dict, investigation: str) -> Path:
    """The local file that supplies the values a committed plan must not carry.

    The plan is committed, so it names the headers to send and never their
    values. The values come from this file: by default next to the raw
    captures, and a path the plan names is refused when it is inside the
    repository, because a value that reaches the repository that way is a value
    the redactor never saw.
    """
    value = plan.get("request_file")
    path = Path(value).expanduser() if value else RAW_ROOT / safe_investigation(investigation) / "request.json"
    resolved = path.resolve()
    if resolved == REPO_ROOT or REPO_ROOT in resolved.parents:
        raise SystemExit(f"the request file must stay outside the repository: {resolved}")
    return resolved


def load_json_object(path: Path) -> dict:
    """A JSON object, or an empty one when the file is not there."""
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"the request file is not readable: {path} ({error})") from error
    if not isinstance(data, dict):
        raise SystemExit(f"the request file is not a JSON object: {path}")
    return data


def load_yaml_object(path: Path, what: str) -> dict:
    """A YAML file that has to be one mapping."""
    if not path.is_file():
        raise SystemExit(f"there is no {what} at {path}\n\nwrite one first:\n\n{SUGGESTED_PLAN}")
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as error:
        raise SystemExit(f"the {what} is not readable: {path} ({error})") from error
    if not isinstance(data, dict):
        raise SystemExit(f"the {what} is not a mapping: {path}")
    return data


def save_plan(path: Path, plan: dict) -> None:
    """Write the plan back. The approval is a field of the file, not of a moment."""
    path.write_text(
        yaml.safe_dump(plan, sort_keys=False, allow_unicode=True, default_flow_style=False, width=100),
        encoding="utf-8",
    )


def investigation_record(investigation: str) -> dict:
    """The record of the investigation, which names the candidate."""
    path = investigation_dir(investigation) / "investigation.yaml"
    if not path.is_file():
        raise SystemExit(f"the investigation has no record: {path}")
    return load_yaml_object(path, "investigation record")


def candidate_of(record: dict, candidate: str) -> dict:
    """The api_candidate the plan validates. A selected candidate, not a guess."""
    for entry in record.get("api_candidates") or []:
        if isinstance(entry, dict) and entry.get("id") == candidate:
            return entry
    known = [entry.get("id") for entry in record.get("api_candidates") or [] if isinstance(entry, dict)]
    raise SystemExit(f"the record has no api_candidate {candidate!r}; it has: {', '.join(map(str, known)) or 'none'}")


def check_experiment(experiment: dict, base: dict) -> dict:
    """One experiment, checked at the door: one variation, and it has to vary.

    Two differences at once prove nothing about either of them, so an
    experiment carries exactly one source of change: a query parameter that is
    replaced or removed, the credentials mode, or a page reload. The first
    experiment is the reference, so it carries none.
    """
    name = experiment.get("name")
    if not isinstance(name, str) or not name:
        raise SystemExit("an experiment of the plan has no name")
    if redactor().safe_identifier(name, "experiment") != name:
        raise SystemExit(f"the name of an experiment is one name, not something to reduce: {name!r}")
    kind = experiment.get("kind", "replay")
    if kind not in ("replay", "reload"):
        raise SystemExit(f"the experiment {name} has an unknown kind: {kind!r}")
    if experiment.get("vary") is not None and not isinstance(experiment["vary"], dict):
        raise SystemExit(f"the experiment {name} has a malformed vary")
    if experiment.get("remove") is not None and not isinstance(experiment["remove"], list):
        raise SystemExit(f"the experiment {name} has a malformed remove")
    vary = experiment.get("vary") or {}
    remove = experiment.get("remove") or []
    if len(vary) > 1 or len(remove) > 1 or (vary and remove):
        raise SystemExit(f"the experiment {name} changes more than one thing — one experiment, one variation")
    credentials = experiment.get("credentials")
    if credentials is not None and redactor().safe_credentials(credentials) is None:
        raise SystemExit(f"the experiment {name} has a malformed credentials: {credentials!r}")
    sources = [bool(vary), bool(remove), credentials is not None, kind == "reload"]
    if sum(sources) > 1:
        raise SystemExit(f"the experiment {name} changes more than one thing — one experiment, one variation")

    for param, value in vary.items():
        if not isinstance(param, str) or not param:
            raise SystemExit(f"the experiment {name} has a malformed parameter name")
        if not isinstance(value, (str, int, float)) or isinstance(value, bool):
            raise SystemExit(f"the experiment {name} has a non-scalar value for {param}")
        if param in base["query"] and str(base["query"][param]) == str(value):
            raise SystemExit(f"the experiment {name} does not change {param} — a replay says that")
    for param in remove:
        if not isinstance(param, str) or param not in base["query"]:
            raise SystemExit(f"the experiment {name} removes {param}, which the base request does not carry")
    if credentials is not None and credentials == base["credentials"]:
        raise SystemExit(f"the experiment {name} does not change the credentials mode — a replay says that")

    expect = experiment.get("expect")
    if expect is not None and expect not in ("same", "changed"):
        raise SystemExit(f"the experiment {name} has an unknown expectation: {expect!r}")
    on = experiment.get("compare")
    if on is not None:
        if not isinstance(on, list) or not on:
            raise SystemExit(f"the experiment {name} has a malformed compare")
        unknown = [field for field in on if field not in COMPARED_FIELDS]
        if unknown:
            raise SystemExit(f"the experiment {name} compares unknown fields: {', '.join(map(str, unknown))}")
        if expect is None:
            raise SystemExit(f"the experiment {name} names fields to compare but no expectation")
    repeat = experiment.get("repeat", 1)
    if isinstance(repeat, bool) or not isinstance(repeat, int) or not 1 <= repeat <= MAX_REPEAT:
        raise SystemExit(f"the experiment {name} has a malformed repeat: {repeat!r}")
    return experiment


def check_plan(plan: dict, investigation: str) -> tuple[dict, dict, list[dict]]:
    """The plan, checked at the door. A plan that does not say what it does is not run."""
    if plan.get("schema") != PLAN_SCHEMA:
        raise SystemExit(f"the plan is not {PLAN_SCHEMA}: {plan.get('schema')!r}")
    if plan.get("id") != investigation:
        raise SystemExit(f"the plan names another investigation: {plan.get('id')}")
    candidate = plan.get("candidate")
    if not isinstance(candidate, str) or not candidate:
        raise SystemExit("the plan names no candidate")
    page = plan.get("page")
    if not isinstance(page, str) or not page:
        raise SystemExit("the plan names no page")
    if capture().url_parts(page) is None or urlsplit(page).scheme not in ("http", "https"):
        raise SystemExit(f"the page of the plan is not an http(s) URL: {page!r}")
    request = plan.get("request")
    if request is None:
        request = {}
    if not isinstance(request, dict):
        raise SystemExit("the request of the plan is not a mapping")
    headers = request.get("headers")
    if headers is not None and (not isinstance(headers, list) or not all(isinstance(h, str) and h for h in headers)):
        raise SystemExit("the headers of the request are not a list of names")
    query = request.get("query")
    if query is not None and not isinstance(query, dict):
        raise SystemExit("the query of the request is not a mapping")
    credentials = request.get("credentials", "include")
    if redactor().safe_credentials(credentials) is None:
        raise SystemExit(f"the credentials mode of the request is malformed: {credentials!r}")
    interval = plan.get("min_interval_seconds", 2)
    if isinstance(interval, bool) or not isinstance(interval, (int, float)) or not 0 <= interval <= MAX_INTERVAL:
        raise SystemExit(f"min_interval_seconds is malformed: {interval!r}")

    experiments = plan.get("experiments")
    if not isinstance(experiments, list) or not experiments:
        raise SystemExit("the plan has no experiments")
    seen: set[str] = set()
    for experiment in experiments:
        if not isinstance(experiment, dict):
            raise SystemExit("an experiment of the plan is not a mapping")
        name = experiment.get("name")
        if name in seen:
            raise SystemExit(f"two experiments are named {name}")
        seen.add(name)
    return plan, request, experiments


def check_experiments(experiments: list[dict], base: dict) -> None:
    """Every experiment, checked against the request it varies.

    The first experiment is the reference, so it carries no variation: a run
    whose reference is itself a variation has nothing to compare against.
    """
    for experiment in experiments:
        check_experiment(experiment, base)
    first = experiments[0]
    varies = bool(
        first.get("vary") or first.get("remove") or first.get("credentials") or first.get("kind", "replay") != "replay"
    )
    if varies:
        raise SystemExit("the first experiment is the reference: it cannot vary anything")
    if first.get("expect") or first.get("compare"):
        # The reference is what the others are compared with; a claim about it
        # has nothing to be judged against, and a run that accepts one would
        # reach the summary with an empty list of comparisons.
        raise SystemExit("the first experiment is the reference: it cannot declare an expectation")


def declared_request(plan: dict, request: dict, record: dict) -> dict:
    """The request the plan describes, before the local values are resolved.

    The candidate contributes its method, endpoint, query and header names; the
    plan overrides the query and names the headers it actually wants to send.
    The values of a header are not here: they are not committed, and the scope
    of the approval is the names.
    """
    candidate = candidate_of(record, plan["candidate"])
    method = request.get("method") or candidate.get("method") or "GET"
    if redactor().safe_method(method) is None:
        raise SystemExit(f"the method of the request is malformed: {method!r}")
    if redactor().safe_method(method) != "GET":
        # A replay is an observation, and an observation is a GET. The
        # investigation format carries POST candidates (the antibot service is
        # one), and a POST is not a request this tool may repeat: the page's
        # `fetch` would send it, and the site may act on it.
        raise SystemExit(
            f"the method of the request is {method!r}: a validation replays GET only — the workflow is read-only"
        )
    url = request.get("url") or candidate.get("endpoint")
    if not isinstance(url, str) or capture().url_parts(url) is None:
        raise SystemExit(f"the request has no readable URL: {url!r}")
    parts = urlsplit(url)
    query: dict[str, str] = dict(parse_qsl(parts.query, keep_blank_values=True))
    for source in (candidate.get("query") or {}, request.get("query") or {}):
        if not isinstance(source, dict):
            raise SystemExit("a query of the request is not a mapping")
        for name, value in source.items():
            if not isinstance(name, str) or not name:
                raise SystemExit("a query parameter of the request has no name")
            if not isinstance(value, (str, int, float)) or isinstance(value, bool):
                raise SystemExit(f"the query parameter {name} has a non-scalar value")
            query[name] = str(value)
    headers = request.get("headers")
    if headers is None:
        headers = list(candidate.get("headers") or {})
    return {
        "method": redactor().safe_method(method),
        "url": urlunsplit((parts.scheme, parts.netloc, parts.path, "", "")),
        "query": query,
        "headers": list(headers),
        "credentials": request.get("credentials", "include"),
    }


def header_values(declared: dict, local: dict, path: Path) -> dict[str, str]:
    """The header values, from the local file the plan points at.

    The plan is committed, so it names the headers and never their values; the
    values come from a file outside the repository. A named header with no
    value stops the run: a request replayed without the header it was seen with
    is a different request.

    The query is not resolved here. It is part of the experiment and part of
    the approval, so a query value the record redacted has to be written in the
    plan — a value that comes from a local file would change what the run asks
    without changing the fingerprint the operator approved.
    """
    for name, value in declared["query"].items():
        if PLACEHOLDER.fullmatch(value):
            raise SystemExit(
                f"the query parameter {name} is redacted in the record — write its value in the plan: "
                "a query the local file supplies would be outside the approved scope"
            )
    headers = {}
    missing = []
    values = local.get("headers") or {}
    if not isinstance(values, dict):
        raise SystemExit(f"the headers of the request file are not a mapping: {path}")
    for name in declared["headers"]:
        value = values.get(name)
        if not isinstance(value, str):
            missing.append(name)
            continue
        headers[name] = value
    if missing:
        raise SystemExit(
            f"the header(s) {', '.join(missing)} have no value in {path} — "
            "the plan names them, and the browser cannot send a header it has no value for"
        )
    return headers


def config_scope() -> dict:
    """The part of the capture config a validation run is held to.

    A validation opens one page and fetches one endpoint, so the approved
    origins and pages are what the run may reach. The query parameters of the
    capture config are about navigations and do not govern a fetch; the
    parameters of the request are in the plan and are approved with it.
    """
    if not CONFIG_PATH.is_file():
        raise SystemExit(f"no capture config at {CONFIG_PATH}")
    try:
        config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise SystemExit(f"the capture config is not readable: {CONFIG_PATH} ({error})") from error
    return {
        "allowedOrigins": sorted(config.get("allowedOrigins", [])),
        "allowedPages": sorted(config.get("allowedPages", [])),
    }


def scope_of(page: str, request: dict, experiments: list[dict], interval: float, config: dict) -> str:
    """The fingerprint of what a run is approved to do.

    The approval covers the page the session rests on, the endpoint, the query
    as the plan writes it, the names of the headers, the credentials mode, the
    experiments and the pause between them. It does not cover the values of the
    headers: they are the operator's session, not a widening of the scope.
    """
    payload = {
        "config": config,
        "page": page,
        "request": {
            "method": request["method"],
            "url": request["url"],
            "query": dict(sorted(request["query"].items())),
            "headers": sorted(name.lower() for name in request["headers"]),
            "credentials": request["credentials"],
        },
        "experiments": experiments,
        "min_interval_seconds": interval,
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()


def approved_scope(plan: dict, request: dict, experiments: list[dict], config: dict) -> str:
    """The fingerprint as `approve` writes it: the plan's own words, not the run's."""
    return scope_of(plan["page"], request, experiments, float(plan.get("min_interval_seconds", 2)), config)


def check_scope(plan: dict, request: dict, experiments: list[dict], config: dict) -> None:
    """A run holds the plan it was approved for, or it does not run."""
    status = plan.get("status")
    if status not in ("approved", "running"):
        raise SystemExit(f"the plan is not approved (status: {status})")
    approved = plan.get("approved_scope")
    if not approved:
        raise SystemExit("the plan was not approved — approve it: validate.py approve --investigation <id>")
    if approved != approved_scope(plan, request, experiments, config):
        raise SystemExit(
            "the scope of the plan is not the scope that was approved — the operator has to approve it again"
        )


def check_place(plan: dict, request: dict, config: dict) -> None:
    """The page and the endpoint have to be the ones the capture config approves.

    A validation runs in the page's own context, so the page is a navigation
    and the endpoint is a request of the site: both are held to the same
    approved origins and pages as a capture. An origin that is not approved is
    the operator's decision, not the tool's, so the run refuses before it opens
    anything. The config is read once per run and handed to every check: a file
    that changes mid-run must not widen what the run may reach.
    """
    if not capture().approved_page(plan["page"], config["allowedPages"]):
        raise SystemExit(f"the page of the plan is not approved by the capture config: {plan['page']}")
    if not capture().approved_origin(request["url"], config["allowedOrigins"]):
        raise SystemExit(f"the endpoint of the plan is not an approved origin: {request['url']}")


def with_query(url: str, query: dict[str, str]) -> str:
    """The request URL with exactly the query the experiment asks for."""
    parts = urlsplit(url)
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(sorted(query.items())), ""))


def request_for(experiment: dict, declared: dict, query: dict[str, str], headers: dict[str, str]) -> dict:
    """The request of one experiment: the base, with its one variation applied."""
    effective = dict(query)
    for name, value in (experiment.get("vary") or {}).items():
        effective[name] = str(value)
    for name in experiment.get("remove") or []:
        effective.pop(name, None)
    return {
        "method": declared["method"],
        "url": with_query(declared["url"], effective),
        "headers": dict(headers),
        "credentials": experiment.get("credentials") or declared["credentials"],
    }


def chrome_use(*args: str, session: str | None = None) -> subprocess.CompletedProcess:
    """Run a chrome-use command. Its output belongs to the browser."""
    prefix = ["chrome-use", "--session", session] if session else ["chrome-use"]
    return subprocess.run([*prefix, *args], capture_output=True, text=True, check=False)


def chrome_use_data(*args: str, session: str | None = None, stdin: str | None = None) -> dict:
    """Run a chrome-use command that prints JSON and return its `data`."""
    prefix = ["chrome-use", "--session", session] if session else ["chrome-use"]
    proc = subprocess.run([*prefix, *args, "--json"], capture_output=True, text=True, check=False, input=stdin)
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


def open_page(page: str, session: str | None, config: dict) -> None:
    """Open the page the probes run in, and check where the session ended up.

    The site may answer the page with a redirect, and the session is judged by
    where it went: a page the config does not approve is a step the tool does
    not take. The page is navigation, so it is read-only by construction.
    """
    chrome_use_data("open", page, session=session)
    landed = session_page(session)
    if not landed:
        raise SystemExit("the browser did not say which page the session is on")
    if not capture().approved_page(landed, config["allowedPages"]):
        raise SystemExit(f"the session is on a page the capture config does not approve: {landed}")


def session_page(session: str | None) -> str:
    """The page the browser is on now. An empty string when it cannot say."""
    proc = chrome_use("get", "url", "--json", session=session)
    text = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0:
        return ""
    try:
        payload = json.loads(text[text.find("{") :])
    except (ValueError, json.JSONDecodeError):
        return ""
    return str((payload.get("data") or {}).get("url", ""))


def reload_page(session: str | None) -> bool:
    """Reload the page in the same session. False when the browser refused."""
    proc = chrome_use("reload", session=session)
    return proc.returncode == 0


def probe(request: dict, session: str | None) -> dict:
    """One page-JavaScript fetch of one request, as the browser reports it.

    The body never leaves the page: what comes back is the status, the media
    type, the size, the top-level keys and the product ids. A failure comes
    back as a code, never as the browser's message — that text quotes the URL
    and the request.
    """
    script = (
        "("
        + PROBE_SCRIPT
        + ")("
        + json.dumps(
            {
                "url": request["url"],
                "method": request["method"],
                "headers": request["headers"],
                "credentials": request["credentials"],
            },
            ensure_ascii=False,
        )
        + ")"
    )
    proc = subprocess.run(
        ["chrome-use", *(["--session", session] if session else []), "eval", "--stdin", "--json"],
        input=script,
        capture_output=True,
        text=True,
        check=False,
    )
    text = (proc.stdout or "") + (proc.stderr or "")
    start = text.find("{")
    if proc.returncode != 0 or start < 0:
        return {"error": "probe_refused"}
    try:
        payload = json.loads(text[start:])
    except json.JSONDecodeError:
        return {"error": "probe_result_unreadable"}
    if not payload.get("success"):
        return {"error": "probe_refused"}
    result = (payload.get("data") or {}).get("result")
    if not isinstance(result, dict):
        return {"error": "probe_result_unreadable"}
    return result


def outcome_of(status: int | None, error: str | None, dropped: bool = False) -> str:
    """What one observation is, in the language of the validation model.

    The probe reports a code, not the browser's message, so the code is what
    the outcome is read from. A body that could not be read is not an answer
    either — the status says something and the shape says nothing. A replay
    the browser sent without a header the plan names is not the approved
    request, however clear the answer looks.
    """
    if error == "body_unreadable":
        return INCONCLUSIVE
    if error is not None:
        return FAILED
    if dropped:
        return INCONCLUSIVE
    if status is None:
        return INCONCLUSIVE
    if status in BLOCKED_STATUSES:
        return BLOCKED
    if status >= 500:
        # A server error is not an answer: a 500 twice is a broken endpoint,
        # not a reproduced one, and an expectation cannot be met by an outage.
        return FAILED
    return COMPLETED


def observation(experiment: dict, run: int, request: dict, result: dict) -> dict:
    """One observation of one experiment, sanitized as it is written down."""
    redact = redactor()
    status = redact.safe_status(result.get("status"))
    raw_error = result.get("error") if isinstance(result.get("error"), str) else None
    error = raw_error if raw_error in PROBE_ERRORS else redact.redact_error(raw_error)
    dropped = result.get("droppedByBrowser") if isinstance(result.get("droppedByBrowser"), list) else []
    dropped_lower = {str(name).lower() for name in dropped}
    sent = [name for name in request["headers"] if name.lower() not in dropped_lower]
    missing = [name for name in request["headers"] if name.lower() in dropped_lower]
    outcome = outcome_of(status, raw_error, bool(missing))
    ids = [
        value
        for value in (result.get("productIds") or [])
        if isinstance(value, int) and not isinstance(value, bool) and value >= 0
    ][:MAX_PRODUCT_IDS]
    count = result.get("productCount")
    total = result.get("total")
    return {
        "id": f"{experiment['name']}-{run}",
        "experiment": experiment["name"],
        "run": run,
        "request": {
            "method": redact.safe_method(request["method"]),
            "url": redact.redact_url(request["url"]),
            "credentials": redact.safe_credentials(request["credentials"]),
            "headerNames": redact.safe_header_names(list(request["headers"])),
            "headersSent": redact.safe_header_names(sent),
            "headersDroppedByBrowser": redact.safe_header_names(dropped),
        },
        "response": {
            "status": status,
            "contentType": redact.safe_content_type(result.get("contentType")),
            "bodyBytes": redact.safe_size(result.get("bodyBytes")),
            "isJson": redact.safe_bool(result.get("isJson")),
            "error": error,
        },
        "schema": {
            "topLevelKeys": redact.safe_keys(result.get("topLevelKeys")),
            "productCount": count if redact.is_count(count) else None,
            "total": total if redact.is_count(total) else None,
        },
        "ordering": {
            "productIds": ids,
            "orderHash": hashlib.sha256(",".join(str(value) for value in ids).encode("utf-8")).hexdigest()
            if ids
            else None,
        },
        "replay": {"complete": not missing, "missingHeaders": redact.safe_header_names(missing)},
        "outcome": outcome,
    }


def comparable(observation: dict) -> dict:
    """The parts of an observation an experiment can change."""
    return {
        "status": observation["response"]["status"],
        "contentType": observation["response"]["contentType"],
        "json": observation["response"]["isJson"],
        "schema": observation["schema"]["topLevelKeys"],
        "count": observation["schema"]["productCount"],
        "order": observation["ordering"]["productIds"],
        "total": observation["schema"]["total"],
    }


def compare(reference: dict, other: dict) -> dict:
    """How one observation differs from the reference, part by part.

    A comparison says what changed and does not judge it: the expectation is
    the experiment's, and the state is what the evidence allows. The order is
    compared as a sequence and as a set, because a parameter that reorders a
    page and one that pages through it change different things about it.
    """
    if reference["response"]["status"] is None or other["response"]["status"] is None:
        return {"status": "incomparable", "changedFields": [], "details": {}}
    before, after = comparable(reference), comparable(other)
    changed = [field for field in COMPARED_FIELDS if before[field] != after[field]]
    details = {}
    if before["order"] is not None and after["order"] is not None:
        details["sameOrder"] = before["order"] == after["order"]
        details["sameSet"] = set(before["order"]) == set(after["order"])
        details["sharedIds"] = len(set(before["order"]) & set(after["order"]))
    return {
        "status": "changed" if changed else "same",
        "changedFields": changed,
        "details": details,
    }


def expectation_met(experiment: dict, comparison: dict) -> bool | None:
    """Whether the comparison is what the experiment expected. None when it cannot tell."""
    if comparison["status"] == "incomparable":
        return None
    fields = experiment.get("compare") or list(COMPARED_FIELDS)
    changed = [field for field in fields if field in comparison["changedFields"]]
    if experiment.get("expect") == "changed":
        return bool(changed)
    return not changed


def state_of(
    experiment: dict, observations: list[dict], comparisons: list[dict], baseline_reproduced: bool
) -> tuple[str, str]:
    """The state a claim earned, and why.

    A single sample never validates anything. `validated` needs the baseline
    reproduced and the variation repeated with the same outcome; a lone result
    is `observed`, a stable contradiction is `rejected`, and a result that a
    refusal or a broken transport produced is `inconclusive` however clear it
    looks.
    """
    if any(entry["outcome"] != COMPLETED for entry in observations):
        refused = [entry["outcome"] for entry in observations if entry["outcome"] == BLOCKED]
        if refused:
            return INCONCLUSIVE, "the site refused the request — a refusal is not a validation"
        if any(entry["outcome"] == FAILED for entry in observations):
            return INCONCLUSIVE, "an observation did not complete — a failed request is not a result"
        missing = sorted(
            {name for entry in observations for name in (entry.get("replay") or {}).get("missingHeaders") or []}
        )
        if missing:
            return INCONCLUSIVE, (
                f"the browser dropped {', '.join(missing)}, which the plan names — "
                "the replay is not the request that was approved"
            )
        return INCONCLUSIVE, "the response could not be read"
    if experiment.get("expect") is None:
        # A variant without an expectation says what was seen, not whether it
        # was expected: two compatible runs are `reproduced`, and one is one.
        if len(observations) >= 2:
            if all(compare(observations[0], entry)["status"] == "same" for entry in observations[1:]):
                return REPRODUCED, ""
            return INCONCLUSIVE, "the runs of the experiment disagree"
        return OBSERVED, ""
    verdicts = [expectation_met(experiment, comparison) for comparison in comparisons]
    if not verdicts:
        return INCONCLUSIVE, "an expectation with no comparison to judge"
    if any(verdict is None for verdict in verdicts):
        return INCONCLUSIVE, "the response cannot be compared to the baseline"
    if len(set(verdicts)) > 1:
        return INCONCLUSIVE, "the runs of the experiment disagree"
    if verdicts[0]:
        if len(verdicts) >= 2 and baseline_reproduced:
            return VALIDATED, ""
        if len(verdicts) >= 2:
            return OBSERVED, "the expectation holds, but the baseline was seen once"
        return OBSERVED, "one sample, so the expectation is not established"
    if len(verdicts) >= 2:
        return REJECTED, "the answer contradicts the expectation, and it did so more than once"
    return INCONCLUSIVE, "one sample contradicts the expectation, and one sample does not settle it"


def write_raw(investigation: str, name: str, run: int, result: dict) -> None:
    """Keep the unredacted observation on the machine that made it."""
    directory = raw_dir(investigation)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{name}-{run}.json"
    path.write_text(
        redactor().writable(json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True) + "\n"), encoding="utf-8"
    )


def run_experiments(
    plan: dict, declared: dict, query: dict[str, str], headers: dict[str, str], investigation: str, session: str | None
) -> list[dict]:
    """Run every experiment, in order, one request at a time.

    The pause is the plan's: a validation is a handful of requests against a
    live site, and a burst of them is exactly what the issue says not to do.
    A page that refused the session ends the run — hammering a refusal is not
    evidence — and the experiments that were not reached say so.
    """
    interval = float(plan.get("min_interval_seconds", 2))
    observations: list[dict] = []
    stopped: str | None = None
    for experiment in plan["experiments"]:
        repeat = experiment.get("repeat", 1)
        for run in range(1, repeat + 1):
            if stopped:
                observations.append(
                    {
                        "id": f"{experiment['name']}-{run}",
                        "experiment": experiment["name"],
                        "run": run,
                        "outcome": INCONCLUSIVE,
                        "notRun": stopped,
                    }
                )
                continue
            if interval:
                time.sleep(interval)
            if experiment.get("kind") == "reload" and not reload_page(session):
                observations.append(
                    {
                        "id": f"{experiment['name']}-{run}",
                        "experiment": experiment["name"],
                        "run": run,
                        "outcome": FAILED,
                        "notRun": "the browser refused to reload the page",
                    }
                )
                stopped = "the browser refused to reload the page"
                continue
            request = request_for(experiment, declared, query, headers)
            result = probe(request, session)
            write_raw(investigation, experiment["name"], run, result)
            entry = observation(experiment, run, request, result)
            observations.append(entry)
            if entry["outcome"] == BLOCKED:
                stopped = "the site refused the request"
            elif entry["outcome"] == FAILED:
                stopped = "the browser could not make the request"
            elif entry["outcome"] == INCONCLUSIVE:
                stopped = "the replay could not be completed"
    return observations


def overall_outcome(observations: list[dict]) -> str:
    """The outcome of an experiment: the worst thing that happened in it."""
    outcomes = [entry.get("outcome") for entry in observations]
    for outcome in (BLOCKED, FAILED, INCONCLUSIVE):
        if outcome in outcomes:
            return outcome
    return COMPLETED if outcomes else INCONCLUSIVE


def summarize(plan: dict, declared: dict, observations: list[dict]) -> tuple[dict, str]:
    """The summary and the report, from the observations and nothing else.

    The reference is the first experiment. A variant is compared with the
    reference's first observation that has an answer; a second run of the
    reference is compared with its first run, which is what `reproduced` means.
    """
    by_experiment: dict[str, list[dict]] = {experiment["name"]: [] for experiment in plan["experiments"]}
    for entry in observations:
        by_experiment.setdefault(entry["experiment"], []).append(entry)

    first = plan["experiments"][0]
    baseline_runs = by_experiment[first["name"]]
    reference = next(
        (entry for entry in baseline_runs if "response" in entry and entry["response"]["status"] is not None),
        None,
    )
    baseline_state, baseline_reason = state_of(first, baseline_runs, [], False)
    baseline_reproduced = baseline_state == REPRODUCED

    experiments: list[dict] = []
    checks: list[dict] = []
    not_validated: list[dict] = []
    for index, experiment in enumerate(plan["experiments"]):
        runs = by_experiment[experiment["name"]]
        comparable_runs = [entry for entry in runs if "response" in entry and entry["response"]["status"] is not None]
        if index == 0:
            comparisons = [compare(reference, entry) for entry in comparable_runs if entry is not reference]
            state, reason = baseline_state, baseline_reason
        elif reference is None:
            comparisons = []
            for run in comparable_runs:
                run["comparison"] = {"status": "incomparable", "changedFields": [], "details": {}}
            state, reason = INCONCLUSIVE, "the baseline has no answer to compare against"
        else:
            comparisons = []
            for run in comparable_runs:
                comparison = compare(reference, run)
                run["comparison"] = comparison
                comparisons.append(comparison)
            state, reason = state_of(experiment, runs, comparisons, baseline_reproduced)
        record = {
            "name": experiment["name"],
            "kind": experiment.get("kind", "replay"),
            "variation": {
                "vary": experiment.get("vary") or None,
                "remove": experiment.get("remove") or None,
                "credentials": experiment.get("credentials"),
            },
            "repeat": experiment.get("repeat", 1),
            "outcome": overall_outcome(runs),
            "state": state,
            "reason": reason,
            "expectation": {"expect": experiment.get("expect"), "compare": experiment.get("compare")}
            if experiment.get("expect")
            else None,
            "observations": [compact_observation(run) for run in runs],
            "comparisons": comparisons,
        }
        experiments.append(record)
        if experiment.get("expect"):
            met = [expectation_met(experiment, comparison) for comparison in comparisons]
            checks.append(
                {
                    "experiment": experiment["name"],
                    "expectation": {"expect": experiment.get("expect"), "compare": experiment.get("compare")},
                    "met": met,
                    "state": state,
                }
            )
            if state != VALIDATED:
                not_validated.append(
                    {
                        "experiment": experiment["name"],
                        "state": state,
                        "reason": reason or "the expectation was not established by the experiments",
                    }
                )
        elif state == INCONCLUSIVE:
            not_validated.append({"experiment": experiment["name"], "state": state, "reason": reason})

    summary = {
        "schema": EVIDENCE_SCHEMA,
        "id": plan["id"],
        "candidate": plan["candidate"],
        "generated": dt.datetime.now(dt.UTC).isoformat(timespec="seconds"),
        "plan_scope": plan.get("approved_scope"),
        "page": redactor().redact_url(plan["page"]),
        "request": {
            "method": declared["method"],
            "url": redactor().redact_url(declared["url"]),
            "query": sorted(declared["query"]),
            "headers": redactor().safe_header_names(list(declared["headers"])),
            "credentials": declared["credentials"],
        },
        "baseline": first["name"],
        "baseline_reproduced": baseline_reproduced,
        "experiments": experiments,
        "checks": checks,
        "not_validated": not_validated,
        "evidence": EVIDENCE_FILE.as_posix(),
        "observations": len(observations),
        "outcomes": {
            outcome: sum(1 for entry in observations if entry.get("outcome") == outcome) for outcome in OUTCOMES
        },
    }
    return summary, report_of(summary)


def report_of(summary: dict) -> str:
    """The report a human reads: what ran, what it showed, and what it did not."""
    lines = [
        f"# Validation report — {summary['id']}",
        "",
        f"- Candidate: `{summary['candidate']}`",
        f"- Page: `{summary['page']}`",
        f"- Request: `{summary['request']['method']} {summary['request']['url']}`",
        f"- Query parameters: {', '.join(f'`{name}`' for name in summary['request']['query']) or 'none'}",
        f"- Headers named by the plan: {', '.join(f'`{name}`' for name in summary['request']['headers']) or 'none'}",
        f"- Evidence: [`{summary['evidence']}`]({summary['evidence']})",
        f"- Generated: {summary['generated']}",
        f"- Baseline: `{summary['baseline']}` — "
        + ("reproduced" if summary["baseline_reproduced"] else "not reproduced"),
        "",
        "The headers' values are not in `experiments.yaml` and never reach this report: the plan names "
        "the headers, and their values are read from a local file outside the repository.",
        "",
        "## Summary",
        "",
        "| Experiment | Kind | Runs | Outcome | State | Comparison |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for experiment in summary["experiments"]:
        states = ", ".join(comparison["status"] for comparison in experiment["comparisons"]) or "—"
        lines.append(
            f"| `{experiment['name']}` | {experiment['kind']} | {len(experiment['observations'])} | "
            f"{experiment['outcome']} | {experiment['state']} | {states} |"
        )
    lines += ["", "## Observations", ""]
    for experiment in summary["experiments"]:
        lines.append(f"### `{experiment['name']}` — {experiment['state']}")
        if experiment["reason"]:
            lines.append("")
            lines.append(experiment["reason"])
        lines.append("")
        for entry in experiment["observations"]:
            if "response" not in entry:
                lines.append(f"- run {entry['run']}: not run — {entry.get('notRun', 'the run stopped')}")
                continue
            response = entry["response"]
            schema = entry["schema"]
            ordering = entry["ordering"]
            comparison = entry.get("comparison")
            detail = f"{response['status']} {response['contentType'] or ''}".strip()
            detail += f", {response['bodyBytes']} bytes" if response["bodyBytes"] is not None else ""
            if schema["productCount"] is not None:
                detail += f", {schema['productCount']} products"
            if schema["total"] is not None:
                detail += f", total {schema['total']}"
            if schema["topLevelKeys"]:
                detail += ", keys [" + ", ".join(schema["topLevelKeys"]) + "]"
            if entry["request"]["headersDroppedByBrowser"]:
                detail += ", dropped " + ", ".join(entry["request"]["headersDroppedByBrowser"])
            if ordering.get("orderHash"):
                detail += f", order `{ordering['orderHash'][:12]}`"
            if comparison:
                changed = ", ".join(comparison["changedFields"]) or "nothing"
                reference = "the first run" if experiment["name"] == summary["baseline"] else "the baseline"
                detail += f"; vs {reference}: {comparison['status']} ({changed})"
            lines.append(f"- run {entry['run']}: {detail} — **{entry['outcome']}**")
        lines.append("")
    lines += ["## What was not established", ""]
    if summary["not_validated"]:
        for item in summary["not_validated"]:
            lines.append(f"- `{item['experiment']}` — {item['state']}: {item['reason']}")
    else:
        lines.append("- Nothing: every experiment either validated its expectation or reproduced its answer.")
    lines += [
        "",
        "## Safety",
        "",
        "- Read-only: every observation is a GET replay from the page's own context, with the browser's cookies.",
        "- No bypass: no CAPTCHA, no rate limit and no access control is worked around — a refusal is reported as one.",
        "- One request at a time, with the pause the plan names.",
        "- The fixture carries names, statuses, media types, sizes, the shape of the answer and the product ids; "
        "no response body, no cookie and no header value is committed.",
        "- This tool is not part of the mandatory CI: it needs a real browser and a live site.",
        "",
    ]
    return "\n".join(lines)


def compact_observation(entry: dict) -> dict:
    """An observation as the summary keeps it: the order as a hash, not the ids.

    The summary is read by a person and by a script, and a page of product ids
    per run buries the answer. The ids stay in the evidence fixture, which is
    where a claim is checked; the summary keeps the hash that ties the run to
    them.
    """
    if "response" not in entry:
        return dict(entry)
    kept = {name: entry[name] for name in ("id", "experiment", "run", "request", "response", "schema", "outcome")}
    kept["ordering"] = {"orderHash": entry["ordering"]["orderHash"]}
    if "comparison" in entry:
        kept["comparison"] = entry["comparison"]
    return kept


def write_outputs(investigation: str, summary: dict, report: str, observations: list[dict]) -> None:
    """Write the summary, the report and the sanitized evidence next to the plan."""
    directory = investigation_dir(investigation)
    evidence_path = directory / EVIDENCE_FILE
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    fixture = {
        "schema": EVIDENCE_SCHEMA,
        "notes": [
            "One observation per experiment run: the request as it was made, the status, the media type, "
            "the size and the shape of the answer, and the order of the product ids. No body and no header value."
        ],
        "captures": observations,
    }
    evidence_path.write_text(
        redactor().writable(json.dumps(fixture, indent=2, ensure_ascii=False) + "\n"), encoding="utf-8"
    )
    # The summary is written as plain JSON first: the same comparison object
    # stands under two keys, and YAML would write it as an anchor and an alias
    # — a machine file that is harder to read and easier to break.
    plain = json.loads(json.dumps(summary, ensure_ascii=False))
    (directory / RECORD_FILE).write_text(
        yaml.safe_dump(plain, sort_keys=False, allow_unicode=True, default_flow_style=False, width=100),
        encoding="utf-8",
    )
    (directory / REPORT_FILE).write_text(redactor().writable(report.rstrip("\n") + "\n"), encoding="utf-8")


def exit_code_of(summary: dict) -> int:
    """What the run says: a refusal stops a human, a failed expectation does not pass."""
    if summary["outcomes"][BLOCKED]:
        return EXIT_BLOCKED
    if summary["not_validated"]:
        return EXIT_FAILED
    if summary["outcomes"][FAILED] or summary["outcomes"][INCONCLUSIVE]:
        return EXIT_FAILED
    return EXIT_OK


def approve(investigation: str) -> int:
    """Approve the plan as it stands and write down the scope that was approved."""
    path = plan_path(investigation)
    plan = load_yaml_object(path, "validation plan")
    plan, request, experiments = check_plan(plan, investigation)
    record = investigation_record(investigation)
    declared = declared_request(plan, request, record)
    check_experiments(experiments, declared)
    config = config_scope()
    check_place(plan, declared, config)
    with investigation_lock(investigation):
        plan["approved_scope"] = approved_scope(plan, declared, experiments, config)
        plan["status"] = "approved"
        save_plan(path, plan)
    print(f"the plan is approved: {investigation}")
    print(f"the scope that was approved: {plan['approved_scope'][:12]}")
    return EXIT_OK


def run(investigation: str, session: str | None, dry_run: bool) -> int:
    """Run the experiments of the plan and write down what they showed."""
    path = plan_path(investigation)
    plan = load_yaml_object(path, "validation plan")
    plan, request, experiments = check_plan(plan, investigation)
    record = investigation_record(investigation)
    declared = declared_request(plan, request, record)
    check_experiments(experiments, declared)
    config = config_scope()
    check_place(plan, declared, config)
    check_scope(plan, declared, experiments, config)
    local_path = local_request_file(plan, investigation)
    headers_for_run = header_values(declared, load_json_object(local_path), local_path)

    if dry_run:
        print(f"the plan is approved: {investigation}")
        print(f"the scope that was approved: {plan['approved_scope'][:12]}")
        print(f"the page: {redactor().redact_url(plan['page'])}")
        print(f"the endpoint: {declared['method']} {redactor().redact_url(declared['url'])}")
        for experiment in experiments:
            planned = request_for(experiment, declared, declared["query"], headers_for_run)
            print(
                f"  {experiment['name']}: {planned['method']} {redactor().redact_url(planned['url'])} "
                f"({planned['credentials']}, {experiment.get('repeat', 1)} run(s))"
            )
        return EXIT_OK

    with investigation_lock(investigation):
        open_page(plan["page"], session, config)
        observations = run_experiments(plan, declared, declared["query"], headers_for_run, investigation, session)
        summary, report = summarize(plan, declared, observations)
        write_outputs(investigation, summary, report, observations)
    for experiment in summary["experiments"]:
        print(f"  {experiment['name']}: {experiment['outcome']} → {experiment['state']}")
    print(f"the summary: {investigation_dir(investigation) / RECORD_FILE}")
    print(f"the report: {investigation_dir(investigation) / REPORT_FILE}")
    print(f"the evidence: {investigation_dir(investigation) / EVIDENCE_FILE}")
    return exit_code_of(summary)


@contextmanager
def investigation_lock(investigation: str):
    """One run of one investigation at a time: the files are named after it."""
    directory = RAW_ROOT / safe_investigation(investigation)
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "run.lock").open("w") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise SystemExit(f"another run of {investigation} is going — wait for it to end") from error
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="verb", required=True)

    approve_parser = sub.add_parser("approve", help="approve the plan as it stands and record the scope")
    approve_parser.add_argument("--investigation", required=True, help="the investigation id")

    run_parser = sub.add_parser("run", help="run the experiments and write the summary, the report and the evidence")
    run_parser.add_argument("--investigation", required=True, help="the investigation id")
    run_parser.add_argument("--session", help="the chrome-use session to reuse")
    run_parser.add_argument("--dry-run", action="store_true", help="print the requests without opening a browser")

    args = parser.parse_args(argv)
    if args.verb == "approve":
        return approve(args.investigation)
    return run(args.investigation, args.session, args.dry_run)


if __name__ == "__main__":
    raise SystemExit(main())

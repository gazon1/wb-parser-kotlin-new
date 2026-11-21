#!/usr/bin/env python3
"""
check-proposal-fresh.py — the committed proposals are the ones the record earns.

Why this gate exists
--------------------
A proposal is generated from a record, and the record keeps changing: a new claim
reaches a validated state, an experiment is repeated, the generator's template is
edited. Nothing connects the committed proposal to any of that afterwards, so the
proposal stays green while the evidence it claims to pin has moved on.

The generator writes the hashes of what it read and what it wrote into
`manifest.json` next to the proposal. This gate compares those numbers with the
files on disk. Four things can be wrong, and each of them is a proposal that no
longer says what it says:

- the record changed after the proposal was generated;
- the generator changed, so the proposal was produced by a tool nobody reviewed;
- a generated file was edited by hand;
- the manifest no longer holds everything the proposal added — a file deleted from
  the disk and from the manifest together would otherwise pass.

It is deliberately offline and stdlib-only: no PyPI, no browser, no YAML. The
generator's command is the one command that fixes a finding.

Usage:
    python3 scripts/ci/check-proposal-fresh.py
    python3 scripts/ci/check-proposal-fresh.py --self-test
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tempfile
from collections import Counter
from pathlib import Path
from typing import Any
from urllib.parse import parse_qsl, urlsplit

ROOT = Path(__file__).resolve().parents[2]
PROPOSALS = ROOT / "devtools" / "wb-research" / "proposals"
GENERATOR_PATH = "scripts/wb-research/generate_proposal.py"
REDACTOR_PATH = "scripts/wb-research/redact.py"
GENERATOR = ROOT / GENERATOR_PATH
REGENERATE = "uv run python scripts/wb-research/generate_proposal.py --investigation <id>"

# The tools a proposal is produced by: the generator itself, and the redactor it
# loads to judge a value. A manifest has to name all of them — a dependency it does
# not name cannot be watched, so the tool behind the proposal could have changed
# without anybody noticing.
GENERATOR_DEPENDENCIES = (GENERATOR_PATH, REDACTOR_PATH)

MANIFEST_SCHEMA = "wb-research/proposal-manifest/v1"
FILE_SECTION = "## The files this proposal adds"
LISTED_FILE = re.compile(r"^- `([^`]+)`$")

# Where the record of an investigation keeps its evidence bundles. The manifest names
# the investigation, and the investigation names the directory — so the gate can look
# at the evidence even when the manifest names none of it.
EVIDENCE_DIR = "devtools/wb-research/investigations"

# A generated fixture: a file under a directory of fixtures, in the directory of its
# contract, named after the observation it holds. The generator names a fixture after
# the observation it was built from, so the name on the disk and the name inside the
# file have to be the same — a fixture that was renamed on the disk, in the manifest
# and in the proposal together still says something the generator would never say.
FIXTURE_PATH = re.compile(r"^(?P<directory>.+/fixtures/[^/]+/(?P<candidate>[^/]+))/(?P<name>[^/]+)\.json$")

# An evidence bundle of an investigation: the file that holds the captures the
# fixtures were built from. The gate reads the bundles the manifest names, so the
# fixtures are checked against the record and not against themselves.
EVIDENCE_BUNDLE = re.compile(rf"^{re.escape(EVIDENCE_DIR)}/(?P<investigation>[^/]+)/evidence/[^/]+\.json$")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_text(path: Path) -> str | None:
    """The text of a file, or None when it cannot be read as text.

    A file that is not UTF-8 is a finding, not a crash: the gate has to say what is
    wrong with the proposal, not die on it. A file that is not there at all is the
    same kind of finding — the hash comparison reports it, and the rest of the gate
    has to keep going and report what else it found.
    """
    try:
        return path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None


def listed_files(proposal_path: Path) -> set[str] | None:
    """The files the proposal says it adds.

    The proposal is the human-readable inventory of what the generator wrote, and the
    manifest is the machine-readable one. The gate compares the two, so an artifact
    that was deleted cannot also be deleted from the manifest: the proposal still
    names it.
    """
    if not proposal_path.is_file():
        return None
    text = read_text(proposal_path)
    if text is None:
        return None
    inside = False
    files: set[str] = set()
    for line in text.splitlines():
        if line.startswith("## "):
            inside = line.strip() == FILE_SECTION
            continue
        if not inside:
            continue
        match = LISTED_FILE.match(line.strip())
        if match:
            files.add(match.group(1))
    return files


def section(manifest: dict, name: str) -> dict[str, str] | None:
    """One section of the manifest, when it is a mapping of paths to hashes."""
    entries = manifest.get(name)
    if not isinstance(entries, dict):
        return None
    for key, value in entries.items():
        if not isinstance(key, str) or not isinstance(value, str):
            return None
    return entries


def proposal_directories(proposals: Path) -> list[Path]:
    if not proposals.is_dir():
        return []
    return sorted(path for path in proposals.iterdir() if path.is_dir())


def inventory_findings(entries: dict[str, str], root: Path, suffix: str) -> list[str]:
    """The files of a generated directory that the manifest does not name.

    The manifest is the inventory of what the proposal added. A file that is on the
    disk and not in the manifest is a leftover of an earlier generation: nobody
    removed it, and it is still committed as if it were evidence. The same holds
    for the evidence: a bundle added under the proposal changes what the generator
    would read, and the gate has to say so.
    """
    findings: list[str] = []
    directories = {str(Path(path).parent) for path in entries if path.endswith(suffix)}
    for directory in sorted(directories):
        folder = root / directory
        if not folder.is_dir():
            continue
        named = {path for path in entries if str(Path(path).parent) == directory}
        for path in sorted(folder.glob(f"*{suffix}")):
            relative = f"{directory}/{path.name}"
            if relative not in named:
                findings.append(
                    f"{relative}: a file the manifest does not name — regenerate the proposal, "
                    f"so the inventory says what is on the disk"
                )
    return findings


def evidence_findings(investigation: str, entries: dict[str, str], root: Path) -> list[str]:
    """The evidence bundles of the investigation that the manifest does not name.

    The directory of the evidence is derived from the id of the investigation, not
    from the manifest: a manifest that names no evidence at all would otherwise
    hide the very bundle the generator would read next time.
    """
    if not investigation:
        return []
    directory = f"{EVIDENCE_DIR}/{investigation}/evidence"
    folder = root / directory
    if not folder.is_dir():
        return []
    findings: list[str] = []
    named = {path for path in entries if str(Path(path).parent) == directory}
    for path in sorted(folder.glob("*.json")):
        relative = f"{directory}/{path.name}"
        if relative not in named:
            findings.append(
                f"{relative}: a file the manifest does not name — regenerate the proposal, "
                f"so the inventory says what is on the disk"
            )
    return findings


def query_pairs_of(url: str) -> list[tuple[str, str]]:
    """The query of a URL as pairs — every parameter the request carried, in order.

    A query read as a map keeps only the last value of a name that appears twice,
    and a request that names a parameter twice says two things about it. The
    generator refuses such a request; the gate has to name it as well, so the
    pairs are kept and the names that appear twice are a finding of their own.

    A URL that cannot be read as one has no query: `urlsplit` raises for a
    malformed authority, and a gate that dies on a malformed record says
    nothing about the proposal.
    """
    try:
        query = urlsplit(url).query
    except ValueError:
        return []
    return [(name, value) for name, value in parse_qsl(query, keep_blank_values=True)]


def is_url(url: str) -> bool:
    """Whether the URL can be read as one.

    `urlsplit` is permissive: it accepts a string without a scheme and without a
    host, and it keeps an invalid port until somebody asks for it. A URL that
    names no host is not the request the record saw, so the scheme and the host
    are asked for as well.
    """
    try:
        parts = urlsplit(url)
        # The port is asked for: `urlsplit` keeps an invalid port until somebody
        # asks for it, and a URL with a port nobody could dial is not a URL.
        _ = parts.port
    except ValueError:
        return False
    return parts.scheme in ("http", "https") and bool(parts.hostname)


def holds_type(value: Any, expect: type) -> bool:
    """Whether the value is of the type the dimension holds.

    A boolean is not a number: `isinstance(True, int)` is true in Python, and a
    status that became `true` is a status nobody recorded.
    """
    if isinstance(value, bool) is not (expect is bool):
        return False
    return isinstance(value, expect)


def type_findings(
    relative: str,
    observation: str,
    label: str,
    left: Any,
    right: Any,
    expect: type | None,
    element: type | None,
) -> list[str]:
    """Whether both sides of a dimension hold the type the dimension holds.

    A dimension that is present but of the wrong type is not the dimension: a
    fixture whose status became the text `200` says something the capture never
    said, and a comparison that reads both sides as text would call them equal.
    """
    if expect is not None:
        for side, value in (("the fixture", left), ("the evidence", right)):
            if not holds_type(value, expect):
                return [
                    f"{relative}: {side} holds the {label} {value!r}, which is not of the "
                    f"type {expect.__name__} — a dimension of the wrong type is not the dimension"
                ]
    if element is not None and isinstance(left, list) and isinstance(right, list):
        for side, values in (("the fixture", left), ("the evidence", right)):
            for value in values:
                if not holds_type(value, element):
                    return [
                        f"{relative}: {side} holds the {label} {values!r}, which does not hold "
                        f"{element.__name__} names — a name that is not a name is not a name"
                    ]
    return []


def dimension_findings(
    relative: str,
    observation: str,
    label: str,
    field: str,
    held: dict,
    recorded: dict,
    *,
    expect: type | None = None,
    element: type | None = None,
    normalize: Any = None,
    expect_list: bool = False,
) -> list[str]:
    """One dimension of a fixture against the one the capture recorded.

    A dimension that is missing on either side is not a dimension that matches: the
    comparison of two absent values would pass, and a capture that says nothing
    about the answer would witness anything. A dimension that is there but of the
    wrong type is not a dimension either.

    `normalize` says how the two values are read — the names of the headers are a
    set, the order of the products is not — and `expect_list` says that the
    dimension holds a list at all.
    """
    if field not in held:
        return [
            f"{relative}: the fixture holds no {label} — a dimension that is missing is not a dimension that matches"
        ]
    if field not in recorded:
        return [
            f"{relative}: the evidence records no {label} for the observation "
            f"'{observation}' — a dimension that is missing is not a dimension that matches"
        ]
    left, right = held[field], recorded[field]
    wrong_type = type_findings(relative, observation, label, left, right, expect, element)
    if wrong_type:
        return wrong_type
    if expect_list and (not isinstance(left, list) or not isinstance(right, list)):
        return [
            f"{relative}: the fixture holds the {label} {left!r}, the evidence records {right!r} for "
            f"the observation '{observation}' — a {label} is a list"
        ]
    if normalize is not None:
        left, right = normalize(left), normalize(right)
    if left != right:
        return [
            f"{relative}: the fixture holds the {label} '{left}', the evidence records '{right}' for "
            f"the observation '{observation}'"
        ]
    return []


def duplicate_values(values: Any) -> list[str]:
    """The values that appear more than once in a list, each of them once."""
    if not isinstance(values, list):
        return []
    counted = Counter(str(value) for value in values)
    return list(dict.fromkeys(str(value) for value in values if counted[str(value)] > 1))


def duplicate_names(pairs: list[tuple[str, str]]) -> list[str]:
    """The names that appear more than once among the pairs, each of them once."""
    counted = Counter(name for name, _ in pairs)
    return list(dict.fromkeys(name for name, _ in pairs if counted[name] > 1))


def object_field(container: dict, field: str) -> dict:
    """A field of a document, when it is an object — an empty one otherwise."""
    value = container.get(field)
    return value if isinstance(value, dict) else {}


def source_investigation_findings(investigation: str, entries: dict[str, str]) -> list[str]:
    """The sources of the proposal that belong to another investigation.

    A proposal is produced from one record: the record of the investigation the
    manifest names and the evidence of that investigation. A source of another
    investigation is a source of another record — and the captures of another
    record would witness the fixtures of this one, which is a fixture built from a
    record nobody named.
    """
    findings: list[str] = []
    for relative in sorted(entries):
        parts = Path(relative).parts
        if len(parts) < 4 or parts[:3] != ("devtools", "wb-research", "investigations"):
            continue
        owner = parts[3]
        if owner != investigation:
            findings.append(
                f"{relative}: the source belongs to the investigation '{owner}' — the proposal names "
                f"the investigation '{investigation}', and the record of another investigation is not "
                f"the record this proposal was produced from"
            )
    return findings


def evidence_captures(sources: dict[str, str], root: Path, investigation: str) -> tuple[dict[str, dict], list[str]]:
    """The captures of the evidence bundles the manifest names, by their id.

    The bundles are the record the fixtures were built from, so they are the only
    independent truth about what a fixture holds. The gate reads them as JSON and
    needs no YAML: the bundles are JSON by construction.

    A bundle that is malformed is a finding and not a crash: the gate has to say
    what is wrong with the proposal, not die on it. And a capture id that two
    bundles hold is a finding as well — the generator refuses a duplicate id, so
    the record holds one capture under one id, and a gate that quietly kept the
    last one would read a record the generator would never have read.

    A bundle of another investigation is not read at all: the captures of another
    record cannot witness the fixtures of this one, and the finding that names the
    bundle is made where the sources are read.
    """
    captures: dict[str, dict] = {}
    findings: list[str] = []
    for relative in sorted(sources):
        match = EVIDENCE_BUNDLE.match(relative)
        if not match:
            continue
        if investigation and match.group("investigation") != investigation:
            continue
        text = read_text(root / relative)
        if text is None:
            findings.append(
                f"{relative}: not readable as text — the evidence bundle holds the captures the fixtures were built from"
            )
            continue
        try:
            bundle = json.loads(text)
        except json.JSONDecodeError:
            findings.append(
                f"{relative}: not readable as JSON — the evidence bundle holds the captures the fixtures were built from"
            )
            continue
        if not isinstance(bundle, dict):
            findings.append(
                f"{relative}: the evidence bundle is not an object — the captures it holds are what the fixtures were built from"
            )
            continue
        listed = bundle.get("captures")
        if listed is None:
            findings.append(
                f"{relative}: the evidence bundle names no captures — a bundle without captures holds no evidence"
            )
            continue
        if not isinstance(listed, list):
            findings.append(
                f"{relative}: the captures of the evidence bundle are not a list — the bundle holds a list of captures"
            )
            continue
        for capture in listed:
            if not isinstance(capture, dict) or not capture.get("id"):
                findings.append(
                    f"{relative}: a capture of the evidence bundle names no id — a capture is filed under the id of its observation"
                )
                continue
            identifier = str(capture["id"])
            if identifier in captures:
                findings.append(
                    f"{relative}: the capture '{identifier}' is in two evidence bundles — the record holds "
                    f"one capture under one id, and the gate would read whichever bundle came last"
                )
                continue
            # The nested objects of a capture are read too, so their types are
            # checked: a request that is a list is a finding, not a crash of the
            # gate on the next line that asks it for its method.
            for field in ("request", "ordering"):
                nested = capture.get(field)
                if nested is not None and not isinstance(nested, dict):
                    findings.append(
                        f"{relative}: the {field} of the capture '{identifier}' is not an object — a capture holds "
                        f"the request and the order of the answer it recorded"
                    )
            captures[identifier] = {
                **capture,
                "request": object_field(capture, "request"),
                "ordering": object_field(capture, "ordering"),
            }
    return captures, findings


def fixture_findings(entries: dict[str, str], captures: dict[str, dict], root: Path, investigation: str) -> list[str]:
    """The generated fixtures whose name does not say what they hold.

    The generator names a fixture after the observation it was built from and puts it
    in the directory of the contract it belongs to. A file whose name says something
    else is a file the generator would never have written: a fixture renamed on the
    disk, in the manifest and in the proposal together still passes every hash
    comparison, and the proposal points at a fixture that holds another observation's
    answer.

    So the name on the disk is checked against the name inside the file, and the
    contract of the file against the directory it lives in.

    The name inside the file is checked against the record as well: the observation
    it names has to be one the evidence holds, and the experiment, the run, the
    request and the order of the answer have to be the ones the capture recorded. A
    fixture whose identity was changed together with its name — on the disk, in the
    manifest and in the proposal — is internally consistent and says nothing the
    record ever said, and only the record can tell.
    """
    findings: list[str] = []
    for relative in sorted(entries):
        match = FIXTURE_PATH.match(relative)
        if match is None:
            continue
        path = root / relative
        if not path.is_file():
            # A file that is gone is reported by the hash comparison.
            continue
        text = read_text(path)
        if text is None:
            # A file that is not text is reported by the hash comparison.
            continue
        try:
            document = json.loads(text)
        except json.JSONDecodeError as error:
            findings.append(
                f"{relative}: not readable as JSON ({error}) — a fixture has to name the observation it holds"
            )
            continue
        if not isinstance(document, dict):
            findings.append(
                f"{relative}: the fixture is not an object — a fixture has to name the observation it holds"
            )
            continue
        name = match.group("name")
        candidate = match.group("candidate")
        if str(document.get("observation")) != name:
            findings.append(
                f"{relative}: the fixture holds the observation '{document.get('observation')}' — "
                f"the generator names a fixture after the observation it was built from"
            )
        if str(document.get("investigation") or "") != investigation:
            findings.append(
                f"{relative}: the fixture names the investigation "
                f"'{document.get('investigation') or ''}' — the proposal names the investigation "
                f"'{investigation}', and a fixture of another investigation is a fixture of another "
                f"record"
            )
        if str(document.get("candidate")) != candidate:
            findings.append(
                f"{relative}: the fixture names the candidate '{document.get('candidate')}' — "
                f"the fixture lives in the directory of its contract, '{candidate}'"
            )
        observation = str(document.get("observation"))
        capture = captures.get(observation)
        if capture is None:
            findings.append(
                f"{relative}: the fixture holds the observation '{observation}', which the "
                f"evidence does not record — a fixture is built from an observation of the record"
            )
            continue
        findings += dimension_findings(relative, observation, "experiment", "experiment", document, capture, expect=str)
        findings += dimension_findings(relative, observation, "run", "run", document, capture, expect=int)
        request = document.get("request")
        if not isinstance(request, dict):
            findings.append(
                f"{relative}: the request of the fixture is not an object — a fixture holds the request it was built from"
            )
            request = {}
        ordering = document.get("ordering")
        if not isinstance(ordering, dict):
            findings.append(
                f"{relative}: the ordering of the fixture is not an object — a fixture holds the order of the "
                f"answer it was built from"
            )
            ordering = {}
        recorded_request = object_field(capture, "request")
        recorded_ordering = object_field(capture, "ordering")
        findings += dimension_findings(
            relative, observation, "request method", "method", request, recorded_request, expect=str
        )
        findings += dimension_findings(
            relative, observation, "request url", "url", request, recorded_request, expect=str
        )
        # The query of the request and the order of the products are what the
        # generated test reads: it compares the crawler's parameters with the
        # recorded ones and rebuilds the body of the answer from the recorded
        # order. A fixture that changed either of them changed the test, and the
        # record is the only thing that can tell.
        #
        # The query is read as pairs: a request that names a parameter twice says
        # two things about it, and the map a query is usually read as keeps only
        # the last value — so a name that appears twice is named on its own.
        recorded_url = str(recorded_request.get("url") or "")
        if not is_url(recorded_url):
            findings.append(
                f"{relative}: the evidence records a request url '{recorded_url}' that cannot be read "
                f"as a URL — the capture is the witness of the request the fixture was built from"
            )
        recorded_pairs = query_pairs_of(recorded_url)
        for name in duplicate_names(recorded_pairs):
            findings.append(
                f"{relative}: the evidence records the parameter '{name}' twice for the observation "
                f"'{observation}' — a request that names a parameter twice says two things about it"
            )
        recorded_query = dict(recorded_pairs)
        query = request.get("query")
        if "query" not in request:
            findings.append(
                f"{relative}: the fixture holds no query — a dimension that is missing is not a dimension that matches"
            )
        elif not isinstance(query, dict):
            findings.append(
                f"{relative}: the fixture holds the query {query!r}, which is not a map of names to "
                f"values — the request is committed in the fixture, and the test reads it as a map"
            )
        elif not all(isinstance(name, str) and isinstance(value, str) for name, value in query.items()):
            findings.append(
                f"{relative}: the fixture holds the query {query}, whose names and values have to be "
                f"text — the test reads the query as a map of names to values, and a value of another "
                f"type is a query the test would read differently"
            )
        elif query != recorded_query:
            findings.append(
                f"{relative}: the fixture holds the query {query}, the evidence records "
                f"{recorded_query} for the observation '{observation}'"
            )
        # The order of the products is an order: the test rebuilds the body of the
        # answer from it, so the same names in another order are another answer.
        findings += dimension_findings(
            relative,
            observation,
            "order of the products",
            "productIds",
            ordering,
            recorded_ordering,
            expect_list=True,
            element=int,
        )
        findings += dimension_findings(
            relative, observation, "order", "orderHash", ordering, recorded_ordering, expect=str
        )
        # The rest of the fixture is copied from the capture as well, and the
        # generated test reads it: the names of the headers the request sent, the
        # metadata of the answer and the shape of the answer. A fixture that
        # changed one of them changed the test, and the capture is the witness.
        # The names of the headers are a set: the generated test reads them as a set,
        # so a capture that lists the same names in another order records the same
        # request — and a name that appears twice is a malformed record, which is
        # said on its own, because a set hides the repetition.
        findings += dimension_findings(
            relative,
            observation,
            "header names",
            "headerNames",
            request,
            recorded_request,
            expect_list=True,
            element=str,
            normalize=set,
        )
        for side, values in (
            ("the evidence", recorded_request.get("headerNames")),
            ("the fixture", request.get("headerNames")),
        ):
            for name in duplicate_values(values):
                findings.append(
                    f"{relative}: {side} names the header '{name}' twice — a request that names a "
                    f"header twice is a malformed record"
                )
        response = object_field(document, "response")
        recorded_response = object_field(capture, "response")
        for name, expect in (("status", int), ("contentType", str), ("bodyBytes", int), ("isJson", bool)):
            findings += dimension_findings(
                relative, observation, f"response {name}", name, response, recorded_response, expect=expect
            )
        answer = object_field(document, "answer")
        recorded_answer = object_field(capture, "schema")
        # The top-level keys are a set as well: the test asks whether the answer
        # carries a key, not in which order the keys came.
        findings += dimension_findings(
            relative,
            observation,
            "answer topLevelKeys",
            "topLevelKeys",
            answer,
            recorded_answer,
            expect_list=True,
            element=str,
            normalize=set,
        )
        for side, values in (
            ("the evidence", recorded_answer.get("topLevelKeys")),
            ("the fixture", answer.get("topLevelKeys")),
        ):
            for key in duplicate_values(values):
                findings.append(
                    f"{relative}: {side} names the top-level key '{key}' twice — an answer that names "
                    f"a key twice is a malformed record"
                )
        for name, expect in (("productCount", int), ("total", int)):
            findings += dimension_findings(
                relative, observation, f"answer {name}", name, answer, recorded_answer, expect=expect
            )
    return findings


def check_one(manifest_path: Path, root: Path) -> list[str]:
    """The findings of one proposal. An empty list means it is current."""
    findings: list[str] = []
    relative_manifest = manifest_path.relative_to(root)

    try:
        text = read_text(manifest_path)
        if text is None:
            return [f"{relative_manifest}: not readable as text"]
        manifest = json.loads(text)
    except json.JSONDecodeError as error:
        return [f"{relative_manifest}: not readable as JSON ({error})"]

    if not isinstance(manifest, dict):
        return [f"{relative_manifest}: the manifest is not an object"]

    if manifest.get("schema") != MANIFEST_SCHEMA:
        findings.append(f"{relative_manifest}: the schema is not '{MANIFEST_SCHEMA}'")

    # The proposal names the investigation it was produced from. The field is not
    # optional: a manifest that names none would leave the gate with nothing to
    # bind the fixtures to, and a check that runs only when the manifest helps it
    # is a check that a manifest can switch off.
    investigation = manifest.get("investigation")
    if not isinstance(investigation, str) or not investigation:
        findings.append(
            f"{relative_manifest}: the manifest names no investigation of the proposal — the "
            f"fixtures and the evidence are the ones of one investigation, and the proposal has "
            f"to say which"
        )
        investigation = ""

    sections = {name: section(manifest, name) for name in ("sources", "generator", "generated")}
    for name, entries in sections.items():
        if entries is None:
            findings.append(f"{relative_manifest}: the '{name}' section is missing or malformed")
    if any(entries is None for entries in sections.values()):
        return findings

    def compare(entries: dict[str, str], message: str) -> None:
        for relative, expected in sorted(entries.items()):
            path = root / relative
            if not path.is_file():
                findings.append(f"{relative}: missing — {message}")
                continue
            actual = digest(path)
            if actual != expected:
                findings.append(f"{relative}: {message} (recorded {expected[:12]}, on disk {actual[:12]})")

    compare(sections["sources"], "the record changed after this proposal was generated")
    compare(sections["generated"], "a generated file was changed by hand")

    # The generator and the tools it reads are the tool the proposal was produced by.
    # A dependency that changed has to be reported as what it is — the tool behind
    # the proposal moved — and a dependency the manifest does not name cannot be
    # watched at all.
    for relative, expected in sorted(sections["generator"].items()):
        path = root / relative
        if not path.is_file():
            findings.append(f"{relative}: missing — the generator changed after this proposal was generated")
            continue
        actual = digest(path)
        if actual != expected:
            message = (
                "a dependency of the generator changed after this proposal was generated"
                if relative in GENERATOR_DEPENDENCIES and relative != GENERATOR_PATH
                else "the generator changed after this proposal was generated"
            )
            findings.append(f"{relative}: {message} (recorded {expected[:12]}, on disk {actual[:12]})")

    findings += inventory_findings(sections["generated"], root, ".json")
    findings += inventory_findings(sections["sources"], root, ".json")
    findings += evidence_findings(investigation, sections["sources"], root)
    findings += source_investigation_findings(investigation, sections["sources"])
    captures, capture_findings = evidence_captures(sections["sources"], root, investigation)
    findings += capture_findings
    findings += fixture_findings(sections["generated"], captures, root, investigation)

    if not sections["sources"]:
        findings.append(f"{relative_manifest}: the manifest names no source of the proposal")
    if not sections["generator"]:
        findings.append(f"{relative_manifest}: the manifest names no generator of the proposal")
    if not sections["generated"]:
        findings.append(f"{relative_manifest}: the manifest names no generated file")

    # The manifest has to name every tool the proposal was produced by. A dependency
    # it leaves out is a dependency nobody watches: the redactor could change, the
    # proposal would keep its hashes, and the gate would stay green over a proposal
    # that no tool on the disk would produce any more.
    for relative in GENERATOR_DEPENDENCIES:
        if relative not in sections["generator"]:
            findings.append(
                f"{relative_manifest}: the manifest does not name '{relative}' — the proposal names "
                f"the tool it was produced by, and a tool it does not name may have changed since"
            )

    # The manifest must hold every file the proposal says the proposal adds. Otherwise
    # a deleted file and a deleted entry would pass together, and the gate would guard
    # nothing.
    expected = listed_files(manifest_path.parent / "proposal.md")
    if expected is None:
        findings.append(f"{relative_manifest}: the proposal is missing — the generated files cannot be checked")
    elif set(sections["generated"]) != expected:
        missing = sorted(expected - set(sections["generated"]))
        extra = sorted(set(sections["generated"]) - expected)
        detail = []
        if missing:
            detail.append(f"not in the manifest: {missing}")
        if extra:
            detail.append(f"not in the proposal: {extra}")
        findings.append(f"{relative_manifest}: the manifest and the proposal disagree — {'; '.join(detail)}")

    return findings


def check(proposals: Path, root: Path) -> list[str]:
    findings: list[str] = []
    directories = proposal_directories(proposals)
    if not directories:
        findings.append(f"{proposals.relative_to(root)}: no proposal has been generated yet")
        return findings

    owners: dict[str, str] = {}
    for directory in directories:
        manifest_path = directory / "manifest.json"
        if not manifest_path.is_file():
            findings.append(f"{directory.relative_to(root)}: no manifest.json — the proposal cannot be checked")
            continue
        findings += check_one(manifest_path, root)

        text = read_text(manifest_path)
        if text is None:
            continue
        try:
            manifest = json.loads(text)
        except json.JSONDecodeError:
            continue
        generated = section(manifest, "generated") if isinstance(manifest, dict) else None
        for path in sorted(generated or {}):
            owner = owners.get(path)
            if owner:
                findings.append(
                    f"{path}: two proposals claim this file — '{owner}' and "
                    f"'{manifest.get('investigation')}' — the test and the fixtures belong to "
                    f"the contract, so one contract has one proposal"
                )
            else:
                owners[path] = str(manifest.get("investigation") or directory.name)

    return findings


def self_test() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        proposals = root / "devtools" / "wb-research" / "proposals"
        record = root / "devtools" / "wb-research" / "investigations" / "demo"
        generator = root / "scripts" / "wb-research" / "generate_proposal.py"
        record.mkdir(parents=True)
        generator.parent.mkdir(parents=True)
        (record / "investigation.yaml").write_text("status: concluded\n", encoding="utf-8")
        generator.write_text("# the generator\n", encoding="utf-8")
        redactor = root / REDACTOR_PATH
        redactor.write_text("# the redactor\n", encoding="utf-8")

        proposal = proposals / "demo"
        proposal.mkdir(parents=True)
        # A fixture lives in the directory of its contract and is named after the
        # observation it holds — the same shape the generator writes.
        fixture_relative = (
            "wbparser/src/test/resources/fixtures/catalog-search/storefront-catalog-search/baseline-1.json"
        )
        fixture = root / fixture_relative
        fixture.parent.mkdir(parents=True)
        request = {"method": "GET", "url": "https://example.test/search?resultset=catalog"}

        def fixture_document(
            observation: str = "baseline-1",
            experiment: str = "baseline",
            order_hash: str = "hash-baseline",
            run: int = 1,
            query: dict | None = None,
            product_ids: list | None = None,
            url: str | None = None,
            header_names: list | None = None,
            total: int | None = None,
            top_level_keys: list | None = None,
        ) -> str:
            """The fixture as the generator writes it — the fields it writes."""
            return (
                json.dumps(
                    {
                        "observation": observation,
                        "candidate": "storefront-catalog-search",
                        "investigation": "demo",
                        "experiment": experiment,
                        "run": run,
                        "request": {
                            "method": request["method"],
                            "url": request["url"] if url is None else url,
                            "query": {"resultset": "catalog"} if query is None else query,
                            "headerNames": ["deviceid"] if header_names is None else header_names,
                        },
                        "response": {
                            "status": 200,
                            "contentType": "text/plain",
                            "bodyBytes": 1487,
                            "isJson": True,
                        },
                        "answer": {
                            "topLevelKeys": ["metadata", "products", "total"]
                            if top_level_keys is None
                            else top_level_keys,
                            "productCount": 100,
                            "total": 113593 if total is None else total,
                        },
                        "ordering": {
                            "productIds": [1, 2, 3] if product_ids is None else product_ids,
                            "orderHash": order_hash,
                        },
                    }
                )
                + "\n"
            )

        def capture_document(
            observation: str = "baseline-1",
            experiment: str = "baseline",
            order_hash: str = "hash-baseline",
            run: int = 1,
            product_ids: list | None = None,
            url: str | None = None,
            header_names: list | None = None,
            total: int | None = None,
            top_level_keys: list | None = None,
        ) -> dict:
            """The capture as the record keeps it — the fields the gate compares."""
            return {
                "id": observation,
                "experiment": experiment,
                "run": run,
                "request": {
                    "method": request["method"],
                    "url": request["url"] if url is None else url,
                    "headerNames": ["deviceid"] if header_names is None else header_names,
                },
                "response": {
                    "status": 200,
                    "contentType": "text/plain",
                    "bodyBytes": 1487,
                    "isJson": True,
                },
                "schema": {
                    "topLevelKeys": ["metadata", "products", "total"] if top_level_keys is None else top_level_keys,
                    "productCount": 100,
                    "total": 113593 if total is None else total,
                },
                "ordering": {
                    "productIds": [1, 2, 3] if product_ids is None else product_ids,
                    "orderHash": order_hash,
                },
            }

        fixture.write_text(fixture_document(), encoding="utf-8")
        bundle = record / "evidence" / "validation.json"
        bundle.parent.mkdir(parents=True)
        bundle.write_text(
            json.dumps(
                {
                    "captures": [
                        capture_document(),
                    ]
                }
            )
            + "\n",
            encoding="utf-8",
        )

        def write_proposal(fixture_path: str = fixture_relative) -> None:
            (proposal / "proposal.md").write_text(
                "# a proposal\n\n"
                "## The files this proposal adds\n\n"
                "- `devtools/wb-research/proposals/demo/proposal.md`\n"
                f"- `{fixture_path}`\n",
                encoding="utf-8",
            )

        write_proposal()
        manifest = {
            "schema": "wb-research/proposal-manifest/v1",
            "investigation": "demo",
            "candidate": "storefront-catalog-search",
            "generator": {
                GENERATOR_PATH: digest(generator),
                REDACTOR_PATH: digest(redactor),
            },
            "sources": {
                "devtools/wb-research/investigations/demo/investigation.yaml": digest(record / "investigation.yaml"),
                "devtools/wb-research/investigations/demo/evidence/validation.json": digest(bundle),
            },
            "generated": {
                "devtools/wb-research/proposals/demo/proposal.md": digest(proposal / "proposal.md"),
                fixture_relative: digest(fixture),
            },
        }
        manifest_path = proposal / "manifest.json"

        def write_manifest() -> None:
            manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8")

        write_manifest()
        assert check(proposals, root) == [], "a proposal generated from the record as it is must pass"

        (record / "investigation.yaml").write_text("status: concluded\nupdated: today\n", encoding="utf-8")
        findings = check(proposals, root)
        assert findings, "a record changed after the proposal must fail"
        assert "the record changed" in findings[0], findings

        (record / "investigation.yaml").write_text("status: concluded\n", encoding="utf-8")
        generator.write_text("# a different generator\n", encoding="utf-8")
        findings = check(proposals, root)
        assert findings, "a changed generator must fail"
        assert "the generator changed" in findings[0], findings

        generator.write_text("# the generator\n", encoding="utf-8")

        # A dependency of the generator that changed: the redactor is loaded to judge
        # every value, so a redactor that moved behind the proposal's back makes the
        # proposal a thing nobody would produce today.
        redactor.write_text("# a different redactor\n", encoding="utf-8")
        findings = check(proposals, root)
        assert findings, "a changed dependency of the generator must fail"
        assert any("a dependency of the generator changed" in finding for finding in findings), findings
        redactor.write_text("# the redactor\n", encoding="utf-8")

        # A manifest that names no redactor at all: a dependency nobody watches is a
        # dependency that may change without anybody noticing.
        manifest["generator"].pop(REDACTOR_PATH)
        write_manifest()
        findings = check(proposals, root)
        assert any("does not name" in finding for finding in findings), findings
        manifest["generator"][REDACTOR_PATH] = digest(redactor)
        write_manifest()
        assert check(proposals, root) == [], "a proposal that names every tool it was produced by must pass"

        write_proposal()
        (proposal / "proposal.md").write_text("# an edited proposal\n", encoding="utf-8")
        findings = check(proposals, root)
        assert findings, "an edited generated file must fail"
        assert "by hand" in findings[0], findings

        write_proposal()
        manifest["generated"]["devtools/wb-research/proposals/demo/proposal.md"] = "0" * 64
        write_manifest()
        findings = check(proposals, root)
        assert findings, "a manifest whose hash does not match the file must fail"

        manifest["generated"]["devtools/wb-research/proposals/demo/proposal.md"] = digest(proposal / "proposal.md")
        write_manifest()
        assert check(proposals, root) == [], "a proposal regenerated from the record as it is must pass"

        # A file that was deleted from the disk and from the manifest together: the
        # proposal still names it, so the gate still catches it.
        manifest["generated"].pop(fixture_relative)
        write_manifest()
        findings = check(proposals, root)
        assert findings, "a generated file dropped from the manifest must fail"
        assert "not in the manifest" in findings[0], findings

        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a proposal with every file in the manifest must pass"

        # A fixture left over from an earlier generation: on the disk, not in the
        # manifest. The manifest is the inventory, so the file must be named or gone.
        obsolete = fixture.parent / "obsolete.json"
        obsolete.write_text("{}\n", encoding="utf-8")
        findings = check(proposals, root)
        assert any("the manifest does not name" in finding for finding in findings), findings
        obsolete.unlink()

        # An evidence bundle added under the proposal: the generator would read it.
        extra_bundle = bundle.parent / "extra.json"
        extra_bundle.write_text("{}\n", encoding="utf-8")
        findings = check(proposals, root)
        assert any("the manifest does not name" in finding for finding in findings), findings
        extra_bundle.unlink()
        assert check(proposals, root) == [], "a proposal whose inventory is complete must pass"

        # The same bundle, but a manifest that names no evidence at all: the directory
        # of the evidence comes from the id of the investigation, so the bundle is
        # still found.
        manifest["sources"].pop("devtools/wb-research/investigations/demo/evidence/validation.json")
        write_manifest()
        extra_bundle.write_text("{}\n", encoding="utf-8")
        findings = check(proposals, root)
        assert any("the manifest does not name" in finding for finding in findings), findings
        extra_bundle.unlink()
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        assert check(proposals, root) == [], "a proposal whose inventory is complete must pass"

        # A fixture renamed on the disk, in the manifest and in the proposal together:
        # every hash still matches and the proposal still points at a file — but the
        # name no longer says which observation the file holds, and the generator
        # would never have written such a fixture.
        renamed_relative = fixture_relative.replace("baseline-1.json", "renamed-1.json")
        renamed = root / renamed_relative
        fixture.rename(renamed)
        manifest["generated"].pop(fixture_relative)
        manifest["generated"][renamed_relative] = digest(renamed)
        write_proposal(renamed_relative)
        write_manifest()
        findings = check(proposals, root)
        assert any("names a fixture after the observation" in finding for finding in findings), findings
        renamed.rename(fixture)
        manifest["generated"].pop(renamed_relative)
        manifest["generated"][fixture_relative] = digest(fixture)
        write_proposal()
        write_manifest()
        assert check(proposals, root) == [], "a fixture named after its observation must pass"

        # The same rename, with the identity inside the fixture changed as well: the
        # file, its own field, the manifest and the proposal all agree, so every
        # internal check passes. Only the record can tell, and it never held an
        # observation of that name.
        renamed.write_text(fixture_document(observation="renamed-1"), encoding="utf-8")
        manifest["generated"].pop(fixture_relative)
        manifest["generated"][renamed_relative] = digest(renamed)
        write_proposal(renamed_relative)
        write_manifest()
        findings = check(proposals, root)
        assert any("the evidence does not record" in finding for finding in findings), findings
        renamed.rename(fixture)
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"].pop(renamed_relative)
        manifest["generated"][fixture_relative] = digest(fixture)
        write_proposal()
        write_manifest()
        assert check(proposals, root) == [], "a fixture named after its observation must pass"

        # A fixture whose order the record never recorded: the file agrees with
        # itself, but not with the capture it was built from.
        fixture.write_text(fixture_document(order_hash="hash-somewhere-else"), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        findings = check(proposals, root)
        assert any("the evidence records" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a fixture built from the recorded order must pass"

        # A fixture whose query was changed by hand: the request still points at
        # the recorded URL, so the URL comparison has nothing to say — but the
        # generated test reads the query, and it is what the request carried.
        fixture.write_text(fixture_document(query={"resultset": "filters"}), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        findings = check(proposals, root)
        assert any("the fixture holds the query" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a fixture with the recorded query must pass"

        # A fixture whose product order was made up: the hash of the order still
        # matches, but the body the test rebuilds is not the answer that was
        # recorded.
        fixture.write_text(fixture_document(product_ids=[9, 9, 9]), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        findings = check(proposals, root)
        assert any("the fixture holds the order" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a fixture with the recorded order must pass"

        # A fixture whose header names were edited: the generated test reads them
        # as the header set the request has to carry, and the capture is the only
        # thing that says which headers it sent.
        fixture.write_text(fixture_document(header_names=["deviceid", "x-userid"]), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        findings = check(proposals, root)
        assert any("the fixture holds the header names" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a fixture with the recorded header names must pass"

        # A fixture whose answer was edited: the generated test rebuilds the
        # answer from these values, so a changed one is a changed test.
        fixture.write_text(fixture_document(total=1), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        findings = check(proposals, root)
        assert any("the fixture holds the answer" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a fixture with the recorded answer must pass"

        # A dimension that is missing on both sides is not a dimension that matches:
        # the comparison of two absent values would pass, and a capture that says
        # nothing about the answer would witness anything.
        thin_fixture = json.loads(fixture_document())
        del thin_fixture["response"]["status"]
        thin_capture = capture_document()
        del thin_capture["response"]["status"]
        fixture.write_text(json.dumps(thin_fixture) + "\n", encoding="utf-8")
        bundle.write_text(json.dumps({"captures": [thin_capture]}) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        findings = check(proposals, root)
        assert any("holds no response status" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        bundle.write_text(json.dumps({"captures": [capture_document()]}) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        assert check(proposals, root) == [], "a fixture with the recorded answer must pass"

        # The names of the headers and the top-level keys of the answer are sets:
        # the generated test asks whether a name is there, not in which order the
        # names came. So a capture that lists the same names in another order
        # records the same request.
        fixture.write_text(
            fixture_document(
                header_names=["deviceid", "x-userid"],
                top_level_keys=["metadata", "products", "total"],
            ),
            encoding="utf-8",
        )
        bundle.write_text(
            json.dumps(
                {
                    "captures": [
                        capture_document(
                            header_names=["x-userid", "deviceid"],
                            top_level_keys=["total", "products", "metadata"],
                        )
                    ]
                }
            )
            + "\n",
            encoding="utf-8",
        )
        manifest["generated"][fixture_relative] = digest(fixture)
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        assert check(proposals, root) == [], "the same names in another order must pass"
        fixture.write_text(fixture_document(), encoding="utf-8")
        bundle.write_text(json.dumps({"captures": [capture_document()]}) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()

        # A dimension that is there but of the wrong type is not the dimension: a
        # status that became the text `200` is not the status the capture recorded,
        # and a comparison that reads both sides as text would call them equal.
        typed_fixture = json.loads(fixture_document())
        typed_fixture["response"]["status"] = "200"
        fixture.write_text(json.dumps(typed_fixture) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        findings = check(proposals, root)
        assert any("not of the type int" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a fixture with a number for a status must pass"

        # A name that appears twice: a set hides the repetition, so the repetition
        # is said on its own — in the fixture and in the evidence alike.
        fixture.write_text(fixture_document(header_names=["deviceid", "deviceid"]), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        findings = check(proposals, root)
        assert any("names the header 'deviceid' twice" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a fixture that names a header once must pass"

        bundle.write_text(
            json.dumps({"captures": [capture_document(header_names=["deviceid", "deviceid"])]}) + "\n",
            encoding="utf-8",
        )
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        findings = check(proposals, root)
        assert any("names the header 'deviceid' twice" in finding for finding in findings), findings
        bundle.write_text(json.dumps({"captures": [capture_document()]}) + "\n", encoding="utf-8")
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        assert check(proposals, root) == [], "a capture that names a header once must pass"

        # A fixture of another investigation: the proposal names the investigation,
        # and a fixture that names another one is a fixture of another record.
        other_investigation = json.loads(fixture_document())
        other_investigation["investigation"] = "another-record"
        fixture.write_text(json.dumps(other_investigation) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        findings = check(proposals, root)
        assert any("names the investigation" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a fixture of the proposal's investigation must pass"

        # A fixture that names no investigation: the proposal names one, and a
        # fixture that names none is a fixture of an unknown record.
        nameless_investigation = json.loads(fixture_document())
        nameless_investigation.pop("investigation")
        fixture.write_text(json.dumps(nameless_investigation) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        findings = check(proposals, root)
        assert any("names the investigation" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a fixture that names the investigation must pass"

        # A manifest that names no investigation: the check of the fixtures is
        # bound to the investigation the proposal names, so a proposal that names
        # none has to fail — a check a manifest can switch off is not a check.
        manifest["investigation"] = ""
        write_manifest()
        findings = check(proposals, root)
        assert any("names no investigation" in finding for finding in findings), findings
        del manifest["investigation"]
        write_manifest()
        findings = check(proposals, root)
        assert any("names no investigation" in finding for finding in findings), findings
        manifest["investigation"] = "demo"
        write_manifest()
        assert check(proposals, root) == [], "a manifest that names the investigation must pass"

        # A source of another investigation: the proposal is produced from one
        # record, and the captures of another record cannot witness its fixtures.
        other_record = root / "devtools" / "wb-research" / "investigations" / "other-record"
        (other_record / "evidence").mkdir(parents=True)
        other_bundle = other_record / "evidence" / "validation.json"
        other_bundle.write_text(json.dumps({"captures": [capture_document()]}) + "\n", encoding="utf-8")
        other_relative = "devtools/wb-research/investigations/other-record/evidence/validation.json"
        manifest["sources"][other_relative] = digest(other_bundle)
        write_manifest()
        findings = check(proposals, root)
        assert any("belongs to the investigation" in finding for finding in findings), findings
        del manifest["sources"][other_relative]
        write_manifest()
        assert check(proposals, root) == [], "a proposal of one investigation must pass"

        # A query whose values are not text: the test reads the query as a map of
        # names to values, so a value of another type is a query the test reads
        # differently than the request that was made.
        typed_query = json.loads(fixture_document())
        typed_query["request"]["query"] = {"resultset": "catalog", "page": 2}
        fixture.write_text(json.dumps(typed_query) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        findings = check(proposals, root)
        assert any("have to be text" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a fixture with a text query must pass"

        # A URL that names no host: `urlsplit` reads it as a path, and a request
        # that names no host is not a request the record could have made.
        hostless = "/search?resultset=catalog"
        fixture.write_text(fixture_document(url=hostless), encoding="utf-8")
        bundle.write_text(json.dumps({"captures": [capture_document(url=hostless)]}) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        findings = check(proposals, root)
        assert any("cannot be read as a URL" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        bundle.write_text(json.dumps({"captures": [capture_document()]}) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        assert check(proposals, root) == [], "a request with a readable URL must pass"

        # A URL that cannot be read as one: a finding, not a crash. The gate reads
        # the query of every capture, and a malformed authority is not a query.
        broken_url = "https://[invalid/search"
        fixture.write_text(fixture_document(url=broken_url), encoding="utf-8")
        bundle.write_text(json.dumps({"captures": [capture_document(url=broken_url)]}) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        findings = check(proposals, root)
        assert any("cannot be read as a URL" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        bundle.write_text(json.dumps({"captures": [capture_document()]}) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        assert check(proposals, root) == [], "a request with a readable URL must pass"

        # An evidence bundle whose captures are not a list: a finding, not a crash.
        bundle.write_text(json.dumps({"captures": 42}) + "\n", encoding="utf-8")
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        findings = check(proposals, root)
        assert any("are not a list" in finding for finding in findings), findings
        bundle.write_text(json.dumps({"captures": [capture_document()]}) + "\n", encoding="utf-8")
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        assert check(proposals, root) == [], "a bundle with a list of captures must pass"

        # A capture whose nested objects are not objects: a finding, not a crash.
        # The gate reads the request and the order of the answer of every capture,
        # so a request that is a list has to be named rather than raised on.
        broken_capture = capture_document()
        broken_capture["request"] = ["not", "an", "object"]
        bundle.write_text(json.dumps({"captures": [broken_capture]}) + "\n", encoding="utf-8")
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        findings = check(proposals, root)
        assert any("is not an object" in finding for finding in findings), findings
        bundle.write_text(json.dumps({"captures": [capture_document()]}) + "\n", encoding="utf-8")
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        assert check(proposals, root) == [], "a capture with an object for a request must pass"

        # The same on the side of the fixture: a finding, not a crash.
        broken_fixture = json.loads(fixture_document())
        broken_fixture["request"] = ["not", "an", "object"]
        fixture.write_text(json.dumps(broken_fixture) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        findings = check(proposals, root)
        assert any("is not an object" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a fixture with an object for a request must pass"

        # A request that names a parameter twice: the generator refuses such a
        # request, and the gate reads the query as pairs for the same reason — a
        # map would keep only the last value and say nothing about the first.
        twice = "https://example.test/search?resultset=catalog&sort=popular&sort=priceup"
        fixture.write_text(fixture_document(url=twice), encoding="utf-8")
        bundle.write_text(json.dumps({"captures": [capture_document(url=twice)]}) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        findings = check(proposals, root)
        assert any("twice" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        bundle.write_text(json.dumps({"captures": [capture_document()]}) + "\n", encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/validation.json"] = digest(bundle)
        write_manifest()
        assert check(proposals, root) == [], "a request that names a parameter once must pass"

        # The same capture id in two bundles: the record holds one capture under
        # one id, and a gate that kept the last one would read a record the
        # generator never read.
        second_bundle = bundle.parent / "second.json"
        second_bundle.write_text(
            json.dumps({"captures": [capture_document(order_hash="hash-elsewhere")]}) + "\n",
            encoding="utf-8",
        )
        manifest["sources"]["devtools/wb-research/investigations/demo/evidence/second.json"] = digest(second_bundle)
        write_manifest()
        findings = check(proposals, root)
        assert any("is in two evidence bundles" in finding for finding in findings), findings
        second_bundle.unlink()
        manifest["sources"].pop("devtools/wb-research/investigations/demo/evidence/second.json")
        write_manifest()
        assert check(proposals, root) == [], "a record with one capture under one id must pass"

        # A fixture that was deleted while the manifest still names it: a finding,
        # not a crash.
        fixture.unlink()
        findings = check(proposals, root)
        assert findings, "a missing generated file must fail"
        assert any("missing" in finding for finding in findings), findings
        fixture.write_text(fixture_document(), encoding="utf-8")
        manifest["generated"][fixture_relative] = digest(fixture)
        write_manifest()
        assert check(proposals, root) == [], "a proposal with every file on the disk must pass"

        # A manifest that is not text at all is a finding, not a crash.
        manifest_path.write_bytes(b"\xff\xfe not utf-8")
        findings = check(proposals, root)
        assert findings, "a manifest that is not text must fail"
        assert "not readable as text" in findings[0], findings
        write_manifest()
        assert check(proposals, root) == [], "a proposal whose manifest is text must pass"

        # A manifest that is not a mapping of paths to hashes is a finding, not a crash.
        manifest["generated"] = [fixture_relative]
        write_manifest()
        findings = check(proposals, root)
        assert findings, "a malformed manifest must fail"
        assert "malformed" in findings[0], findings

        manifest["generated"] = {}
        write_manifest()
        findings = check(proposals, root)
        assert findings, "a manifest without generated files must fail"

        manifest["generated"] = {
            "devtools/wb-research/proposals/demo/proposal.md": digest(proposal / "proposal.md"),
            fixture_relative: digest(fixture),
        }
        write_manifest()

        # Two proposals that claim the same file: the test and the fixtures belong to
        # the contract, so one contract has one proposal.
        second = proposals / "demo-2"
        second.mkdir()
        (second / "proposal.md").write_text(
            "# another proposal\n\n"
            "## The files this proposal adds\n\n"
            "- `devtools/wb-research/proposals/demo-2/proposal.md`\n"
            f"- `{fixture_relative}`\n",
            encoding="utf-8",
        )
        second_manifest = {
            "schema": "wb-research/proposal-manifest/v1",
            "investigation": "demo-2",
            "candidate": "storefront-catalog-search",
            "generator": {
                GENERATOR_PATH: digest(generator),
                REDACTOR_PATH: digest(redactor),
            },
            "sources": {
                "devtools/wb-research/investigations/demo/investigation.yaml": digest(record / "investigation.yaml"),
                "devtools/wb-research/investigations/demo/evidence/validation.json": digest(bundle),
            },
            "generated": {
                "devtools/wb-research/proposals/demo-2/proposal.md": digest(second / "proposal.md"),
                fixture_relative: digest(fixture),
            },
        }
        (second / "manifest.json").write_text(json.dumps(second_manifest, indent=2, sort_keys=True), encoding="utf-8")
        findings = check(proposals, root)
        assert any("two proposals claim this file" in finding for finding in findings), findings
        (second / "manifest.json").unlink()
        (second / "proposal.md").unlink()
        second.rmdir()

        (record / "investigation.yaml").unlink()
        findings = check(proposals, root)
        assert findings, "a record file that is gone must fail"

        manifest_path.unlink()
        findings = check(proposals, root)
        assert findings, "a proposal directory without a manifest must fail"

    print(
        "self-test: a changed record, a changed generator, a changed dependency of the generator, "
        "a manifest that names no dependency, an edited file, an incomplete manifest and two "
        "proposals claiming one file each fail"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--proposals", type=Path, default=PROPOSALS)
    arguments = parser.parse_args()

    if arguments.self_test:
        self_test()
        return 0

    findings = check(arguments.proposals, ROOT)
    for finding in findings:
        print(f"error: {finding}", file=sys.stderr)
    if findings:
        print(f"error: regenerate the proposal — {REGENERATE}", file=sys.stderr)
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())

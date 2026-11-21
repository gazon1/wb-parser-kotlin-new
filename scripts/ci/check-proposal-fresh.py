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
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROPOSALS = ROOT / "devtools" / "wb-research" / "proposals"
GENERATOR = ROOT / "scripts" / "wb-research" / "generate_proposal.py"
REGENERATE = "uv run python scripts/wb-research/generate_proposal.py --investigation <id>"

MANIFEST_SCHEMA = "wb-research/proposal-manifest/v1"
FILE_SECTION = "## The files this proposal adds"
LISTED_FILE = re.compile(r"^- `([^`]+)`$")

# Where the record of an investigation keeps its evidence bundles. The manifest names
# the investigation, and the investigation names the directory — so the gate can look
# at the evidence even when the manifest names none of it.
EVIDENCE_DIR = "devtools/wb-research/investigations"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_text(path: Path) -> str | None:
    """The text of a file, or None when it is not text at all.

    A file that is not UTF-8 is a finding, not a crash: the gate has to say what is
    wrong with the proposal, not die on it.
    """
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
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
    compare(sections["generator"], "the generator changed after this proposal was generated")
    compare(sections["generated"], "a generated file was changed by hand")

    findings += inventory_findings(sections["generated"], root, ".json")
    findings += inventory_findings(sections["sources"], root, ".json")
    findings += evidence_findings(str(manifest.get("investigation") or ""), sections["sources"], root)

    if not sections["sources"]:
        findings.append(f"{relative_manifest}: the manifest names no source of the proposal")
    if not sections["generator"]:
        findings.append(f"{relative_manifest}: the manifest names no generator of the proposal")
    if not sections["generated"]:
        findings.append(f"{relative_manifest}: the manifest names no generated file")

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

        proposal = proposals / "demo"
        proposal.mkdir(parents=True)
        fixture = root / "wbparser/src/test/resources/fixtures/catalog-search/baseline-1.json"
        fixture.parent.mkdir(parents=True)
        fixture.write_text("{}\n", encoding="utf-8")
        bundle = record / "evidence" / "validation.json"
        bundle.parent.mkdir(parents=True)
        bundle.write_text("{}\n", encoding="utf-8")

        def write_proposal() -> None:
            (proposal / "proposal.md").write_text(
                "# a proposal\n\n"
                "## The files this proposal adds\n\n"
                "- `devtools/wb-research/proposals/demo/proposal.md`\n"
                "- `wbparser/src/test/resources/fixtures/catalog-search/baseline-1.json`\n",
                encoding="utf-8",
            )

        write_proposal()
        manifest = {
            "schema": "wb-research/proposal-manifest/v1",
            "investigation": "demo",
            "candidate": "storefront-catalog-search",
            "generator": {"scripts/wb-research/generate_proposal.py": digest(generator)},
            "sources": {
                "devtools/wb-research/investigations/demo/investigation.yaml": digest(record / "investigation.yaml"),
                "devtools/wb-research/investigations/demo/evidence/validation.json": digest(bundle),
            },
            "generated": {
                "devtools/wb-research/proposals/demo/proposal.md": digest(proposal / "proposal.md"),
                "wbparser/src/test/resources/fixtures/catalog-search/baseline-1.json": digest(fixture),
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
        manifest["generated"].pop("wbparser/src/test/resources/fixtures/catalog-search/baseline-1.json")
        write_manifest()
        findings = check(proposals, root)
        assert findings, "a generated file dropped from the manifest must fail"
        assert "not in the manifest" in findings[0], findings

        manifest["generated"]["wbparser/src/test/resources/fixtures/catalog-search/baseline-1.json"] = digest(fixture)
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

        # A manifest that is not text at all is a finding, not a crash.
        manifest_path.write_bytes(b"\xff\xfe not utf-8")
        findings = check(proposals, root)
        assert findings, "a manifest that is not text must fail"
        assert "not readable as text" in findings[0], findings
        write_manifest()
        assert check(proposals, root) == [], "a proposal whose manifest is text must pass"

        # A manifest that is not a mapping of paths to hashes is a finding, not a crash.
        manifest["generated"] = ["wbparser/src/test/resources/fixtures/catalog-search/baseline-1.json"]
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
            "wbparser/src/test/resources/fixtures/catalog-search/baseline-1.json": digest(fixture),
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
            "- `wbparser/src/test/resources/fixtures/catalog-search/baseline-1.json`\n",
            encoding="utf-8",
        )
        second_manifest = {
            "schema": "wb-research/proposal-manifest/v1",
            "investigation": "demo-2",
            "candidate": "storefront-catalog-search",
            "generator": {"scripts/wb-research/generate_proposal.py": digest(generator)},
            "sources": {
                "devtools/wb-research/investigations/demo/investigation.yaml": digest(record / "investigation.yaml"),
                "devtools/wb-research/investigations/demo/evidence/validation.json": digest(bundle),
            },
            "generated": {
                "devtools/wb-research/proposals/demo-2/proposal.md": digest(second / "proposal.md"),
                "wbparser/src/test/resources/fixtures/catalog-search/baseline-1.json": digest(fixture),
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
        "self-test: a changed record, a changed generator, an edited file, an incomplete "
        "manifest and two proposals claiming one file each fail"
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

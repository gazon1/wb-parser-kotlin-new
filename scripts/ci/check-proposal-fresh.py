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
files on disk. Three things can be wrong, and each of them is a proposal that no
longer says what it says:

- the record changed after the proposal was generated;
- the generator changed, so the proposal was produced by a tool nobody reviewed;
- a generated file was edited by hand.

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
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROPOSALS = ROOT / "devtools" / "wb-research" / "proposals"
GENERATOR = ROOT / "scripts" / "wb-research" / "generate_proposal.py"
REGENERATE = "uv run python scripts/wb-research/generate_proposal.py --investigation <id>"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def proposal_directories(proposals: Path) -> list[Path]:
    if not proposals.is_dir():
        return []
    return sorted(path for path in proposals.iterdir() if path.is_dir())


def check_one(manifest_path: Path, root: Path) -> list[str]:
    """The findings of one proposal. An empty list means it is current."""
    findings: list[str] = []

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        return [f"{manifest_path.relative_to(root)}: not readable as JSON ({error})"]

    def compare(section: str, entries: dict[str, str], message: str) -> None:
        for relative, expected in sorted(entries.items()):
            path = root / relative
            if not path.is_file():
                findings.append(f"{relative}: missing — {message}")
                continue
            actual = digest(path)
            if actual != expected:
                findings.append(f"{relative}: {message} (recorded {expected[:12]}, on disk {actual[:12]})")

    compare(
        "sources",
        manifest.get("sources") or {},
        "the record changed after this proposal was generated",
    )
    compare(
        "generator",
        manifest.get("generator") or {},
        "the generator changed after this proposal was generated",
    )
    compare(
        "generated",
        manifest.get("generated") or {},
        "a generated file was changed by hand",
    )

    if not manifest.get("generated"):
        findings.append(f"{manifest_path.relative_to(root)}: the manifest names no generated file")

    return findings


def check(proposals: Path, root: Path) -> list[str]:
    findings: list[str] = []
    directories = proposal_directories(proposals)
    if not directories:
        findings.append(f"{proposals.relative_to(root)}: no proposal has been generated yet")
        return findings
    for directory in directories:
        manifest_path = directory / "manifest.json"
        if not manifest_path.is_file():
            findings.append(f"{directory.relative_to(root)}: no manifest.json — the proposal cannot be checked")
            continue
        findings += check_one(manifest_path, root)
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
        (proposal / "proposal.md").write_text("# a proposal\n", encoding="utf-8")
        manifest = {
            "schema": "wb-research/proposal-manifest/v1",
            "investigation": "demo",
            "candidate": "storefront-catalog-search",
            "generator": {"scripts/wb-research/generate_proposal.py": digest(generator)},
            "sources": {
                "devtools/wb-research/investigations/demo/investigation.yaml": digest(record / "investigation.yaml")
            },
            "generated": {"devtools/wb-research/proposals/demo/proposal.md": digest(proposal / "proposal.md")},
        }
        (proposal / "proposal.md").write_text("# a proposal\n", encoding="utf-8")
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
        (proposal / "proposal.md").write_text("# an edited proposal\n", encoding="utf-8")
        findings = check(proposals, root)
        assert findings, "an edited generated file must fail"
        assert "by hand" in findings[0], findings

        (proposal / "proposal.md").write_text("# a proposal\n", encoding="utf-8")
        manifest["generated"]["devtools/wb-research/proposals/demo/proposal.md"] = "0" * 64
        write_manifest()
        findings = check(proposals, root)
        assert findings, "a manifest whose hash does not match the file must fail"

        manifest["generated"]["devtools/wb-research/proposals/demo/proposal.md"] = digest(proposal / "proposal.md")
        write_manifest()
        assert check(proposals, root) == [], "a proposal regenerated from the record as it is must pass"

        (record / "investigation.yaml").unlink()
        findings = check(proposals, root)
        assert findings, "a record file that is gone must fail"

        manifest_path.unlink()
        findings = check(proposals, root)
        assert findings, "a proposal directory without a manifest must fail"

    print("self-test: a changed record, a changed generator and an edited file each fail")


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

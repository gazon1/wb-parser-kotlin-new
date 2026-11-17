#!/usr/bin/env python3
"""
check-catalog-models.py — the generated Python models name the contract they came from.

Why this gate exists
--------------------
`scripts/parser/catalog_models.py` is generated from
`contract/catalog-v1.openapi.yaml`, but once both files are committed nothing connects
them: edit the contract, forget `just parser::models`, and the stale models still pass
every test that loads them. The recipe writes the contract's SHA-256 into the generated
file; this gate compares that number with the contract on disk.

It is deliberately offline. The generator needs PyPI, and the static registry runs on a
bare interpreter; the hash is what the registry can check without the network.
Regenerating is the developer's step, and `just parser::models` is the one command.

Usage:
    python3 scripts/ci/check-catalog-models.py
    python3 scripts/ci/check-catalog-models.py --self-test
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONTRACT = ROOT / "contract" / "catalog-v1.openapi.yaml"
DEFAULT_GENERATED = ROOT / "scripts" / "parser" / "catalog_models.py"
MARKER = "# source-sha256: "


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def recorded(generated: Path) -> str | None:
    for line in generated.read_text(encoding="utf-8").splitlines():
        if line.startswith(MARKER):
            return line.removeprefix(MARKER).strip()
    return None


def check(contract: Path, generated: Path) -> list[str]:
    expected = digest(contract)
    actual = recorded(generated)
    if actual is None:
        return [f"{generated.name}: no `{MARKER.strip()}` marker — regenerate with `just parser::models`"]
    if actual != expected:
        return [
            f"{generated.name}: generated from contract {actual}, but the contract is now {expected}"
            " — run `just parser::models`"
        ]
    return []


def self_test() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        contract = root / "contract.yaml"
        generated = root / "models.py"

        contract.write_text("openapi: 3.1.0\n", encoding="utf-8")
        generated.write_text(MARKER + digest(contract) + "\n", encoding="utf-8")
        assert check(contract, generated) == [], "a file generated from this contract must pass"

        generated.write_text("", encoding="utf-8")
        assert check(contract, generated), "a file with no marker must fail"

        generated.write_text(MARKER + "0" * 64 + "\n", encoding="utf-8")
        assert check(contract, generated), "a stale marker must fail"

        contract.write_text("openapi: 3.1.0\ninfo:\n  title: changed\n", encoding="utf-8")
        assert check(contract, generated), "a contract changed after generation must fail"

        generated.write_text(MARKER + digest(contract) + "\n", encoding="utf-8")
        assert check(contract, generated) == [], "a regenerated file must pass again"

    print("self-test: a missing marker and a stale marker both fail, a current one passes")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--generated", type=Path, default=DEFAULT_GENERATED)
    arguments = parser.parse_args()

    if arguments.self_test:
        self_test()
        return 0

    findings = check(arguments.contract, arguments.generated)
    for finding in findings:
        print(f"error: {finding}", file=sys.stderr)
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())

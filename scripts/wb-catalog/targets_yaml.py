#!/usr/bin/env python3
"""
The `pages:` list of `wb_catalog_targets.yaml`, read without PyYAML.

Why this exists
---------------
The gate that checks the YAML against the measurement artifact runs on a bare Python.
The CI `static` job installs no packages — it runs `scripts/ci/static-gates.sh` and
nothing else — so `import yaml` is not available there. A gate that needs PyYAML
either does not run in CI or is run with a dependency the CI image does not have, and
a check that only runs on one surface is a check that drifts.

So the gate reads the YAML with a reader that imports nothing outside the standard
library. This reader is deliberately narrow: it understands exactly the shape of
`wb_catalog_targets.yaml` — a `pages:` list of flat mappings of scalars — and it
**fails loudly** on anything it does not understand rather than guessing. A reader
that silently skipped a line it did not understand would let a hand-edited limit
through the very gate that exists to catch it.

The shapes are compared against PyYAML by the unit tests whenever PyYAML *is*
installed, so the narrow reader cannot quietly disagree with the real one.

Usage:
    python3 -c "import sys; sys.path.insert(0, 'scripts/wb-catalog'); \\
                from targets_yaml import read_pages; print(len(read_pages('wb_catalog_targets.yaml')))"
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import List

#: A plain scalar that is a number is a number. The file quotes ids on purpose
#: (`id: '9463'`), so a quoted value stays a string.
INTEGER = re.compile(r"-?\d+")

#: The keys a mapping line may hold. Anything else is a shape this reader does not
#: know, and an unknown shape is an error rather than a skipped line.
KNOWN_KEYS = frozenset(
    {
        "id",
        "name",
        "url",
        "parent_catalog_id",
        "root_category_name",
        "search_query",
        "shard",
        "known_pages_limit",
        "known_pages_limit_measured_at",
        "known_pages_limit_boundary",
        "known_pages_limit_source",
        "wb_subject_id",
    }
)


class YamlSubsetError(ValueError):
    """The file is not the shape this reader understands."""


def parse_pages(text: str) -> List[dict]:
    """The `pages:` list of a catalog YAML.

    Raises [YamlSubsetError] on anything that is not a flat list of flat mappings:
    a nested mapping, a list value, a second top-level key. Those do not occur in
    this file, and a reader that had to understand them would be a second YAML
    implementation with all the bugs of one.
    """
    pages: List[dict] = []
    current: dict = {}
    seen_list = False

    for lineno, raw in enumerate(text.splitlines(), 1):
        line = raw.rstrip()
        if not line.strip():
            continue
        if line.lstrip().startswith("#"):
            continue

        if not seen_list:
            if line.strip() != "pages:":
                raise YamlSubsetError(
                    f"line {lineno}: expected the file to start with 'pages:', got {line.strip()!r}"
                )
            seen_list = True
            continue

        if line.startswith("- "):
            current = {}
            pages.append(current)
            _assign(current, line[2:], lineno)
            continue

        if line.startswith("  "):
            if not pages:
                raise YamlSubsetError(f"line {lineno}: a mapping before the first list item")
            _assign(current, line.strip(), lineno)
            continue

        raise YamlSubsetError(f"line {lineno}: unsupported structure {line.strip()!r}")

    if not seen_list:
        raise YamlSubsetError("the file has no 'pages:' list")
    return pages


def read_pages(path: Path) -> List[dict]:
    """The `pages:` list of the catalog YAML at `path`."""
    return parse_pages(Path(path).read_text(encoding="utf-8"))


def _assign(page: dict, text: str, lineno: int) -> None:
    """Read one `key: value` line into the mapping."""
    if ":" not in text:
        raise YamlSubsetError(f"line {lineno}: expected 'key: value', got {text.strip()!r}")
    key, _, value = text.partition(":")
    key = key.strip()
    if key not in KNOWN_KEYS:
        raise YamlSubsetError(f"line {lineno}: unknown key {key!r}")
    page[key] = _scalar(value.strip())


def _scalar(value: str) -> object:
    """The value of a mapping line: a quoted string, a number, or a plain string."""
    if not value:
        return None
    if len(value) >= 2 and value[0] == "'" and value[-1] == "'":
        return value[1:-1].replace("''", "'")
    if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
        return value[1:-1].replace('\\"', '"').replace("\\\\", "\\")
    if " #" in value:
        # A plain scalar ends at an inline comment.
        value = value.split(" #", 1)[0].rstrip()
    if INTEGER.fullmatch(value):
        return int(value)
    return value

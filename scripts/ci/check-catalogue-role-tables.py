#!/usr/bin/env python3
"""Check that every table the migrations create is decided about in the catalogue role.

`db/roles/catalog-readonly-role.sql` refuses to run when the public schema holds a
table that is in neither of its two lists: the read set the catalogue may read, and
the list of the tables nobody has decided about. That refusal is the right behaviour
— a table nobody has decided about is a table the catalogue may read by accident.

But the refusal happens at the worst possible moment: when the role is provisioned,
which is *after* the migration that created the table has already been applied. The
migration that adds a table is the place where the decision belongs, and this check
is what makes the decision part of the same change.

The check is therefore:

  * every table or view a migration creates is in one of the two lists;
  * every table of the catalogue's read set is created by a migration — a read set
    that names a table nobody creates would make the role script fail on every run.

Usage:
    python3 scripts/ci/check-catalogue-role-tables.py
    python3 scripts/ci/check-catalogue-role-tables.py --self-test
    python3 scripts/ci/check-catalogue-role-tables.py --root DIR
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

MIGRATIONS = "app/src/main/resources/db/migration"
ROLE = "db/roles/catalog-readonly-role.sql"

# CREATE TABLE [IF NOT EXISTS] name / CREATE [OR REPLACE] VIEW name
CREATE_TABLE = re.compile(
    r"^\s*CREATE\s+(?:UNLOGGED\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-z_][a-z0-9_]*)",
    re.IGNORECASE | re.MULTILINE,
)
CREATE_VIEW = re.compile(
    r"^\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:MATERIALIZED\s+)?VIEW\s+([a-z_][a-z0-9_]*)",
    re.IGNORECASE | re.MULTILINE,
)

# The two lists of the role script, by the name of the variable that holds them.
LIST_NAMES = ("catalog_tables", "known_other")


def migration_tables(root: Path) -> set[str]:
    """Every table and view the migrations create."""
    created: set[str] = set()
    directory = root / MIGRATIONS
    if not directory.is_dir():
        return created
    for migration in sorted(directory.glob("V*.sql")):
        text = migration.read_text(encoding="utf-8")
        created.update(CREATE_TABLE.findall(text))
        created.update(CREATE_VIEW.findall(text))
    return created


def role_lists(root: Path) -> dict[str, set[str]]:
    """The two lists of the role script, by the variable that holds them."""
    path = root / ROLE
    if not path.is_file():
        return {name: set() for name in LIST_NAMES}
    text = path.read_text(encoding="utf-8")
    lists: dict[str, set[str]] = {}
    for name in LIST_NAMES:
        match = re.search(
            name + r"\s+text\[\]\s*:=\s*ARRAY\[(.*?)\]",
            text,
            re.IGNORECASE | re.DOTALL,
        )
        if match is None:
            lists[name] = set()
            continue
        lists[name] = {
            quoted.strip("'\" ")
            for quoted in re.findall(r"'([^']*)'", match.group(1))
        }
    return lists


def check(root: Path) -> list[str]:
    """The problems the check exists to find. An empty list means the schema is decided."""
    problems: list[str] = []
    created = migration_tables(root)
    lists = role_lists(root)
    decided = lists.get("catalog_tables", set()) | lists.get("known_other", set())

    undecided = sorted(created - decided)
    if undecided:
        problems.append(
            "the migrations create tables that nobody has decided about in "
            f"{ROLE}: {', '.join(undecided)} — add each of them to the read set or to "
            "the known list, or the role script refuses to run"
        )

    missing = sorted(lists.get("catalog_tables", set()) - created)
    if missing:
        problems.append(
            "the catalogue's read set names tables that no migration creates: "
            f"{', '.join(missing)} — the role script fails on every run while they are there"
        )

    return problems


def run_self_test() -> int:
    """Prove the check can fail: a broken fixture must be rejected."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        migrations = root / MIGRATIONS
        migrations.mkdir(parents=True)
        (root / ROLE).parent.mkdir(parents=True, exist_ok=True)

        (migrations / "V1__init.sql").write_text(
            "CREATE TABLE IF NOT EXISTS decided_table (id uuid PRIMARY KEY);\n",
            encoding="utf-8",
        )
        (migrations / "V2__undecided.sql").write_text(
            "CREATE TABLE IF NOT EXISTS nobody_decided (id uuid PRIMARY KEY);\n",
            encoding="utf-8",
        )
        (root / ROLE).write_text(
            "DECLARE\n"
            "    catalog_tables text[] := ARRAY['decided_table'];\n"
            "    known_other text[] := ARRAY['flyway_schema_history'];\n"
            "END $$;\n",
            encoding="utf-8",
        )

        problems = check(root)
        if not any("nobody_decided" in problem for problem in problems):
            print(
                "self-test FAILED — the check did not detect a table nobody decided about:",
                file=sys.stderr,
            )
            for problem in problems:
                print(f"  {problem}", file=sys.stderr)
            return 1

        # A decided schema must pass: the check may not report a problem where there
        # is none, or it would train the reader to ignore it.
        (migrations / "V2__undecided.sql").unlink()
        if check(root):
            print(
                "self-test FAILED — the check reported a problem for a schema that is decided:",
                file=sys.stderr,
            )
            return 1

    print("✅ self-test: a table nobody decided about is caught, and a decided schema passes")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="the repository root")
    parser.add_argument("--self-test", action="store_true", help="prove the check can fail")
    args = parser.parse_args()

    if args.self_test:
        return run_self_test()

    problems = check(args.root)
    if problems:
        for problem in problems:
            print(f"✗ {problem}", file=sys.stderr)
        return 1

    print("✅ every table the migrations create is decided about in the catalogue role")
    return 0


if __name__ == "__main__":
    sys.exit(main())

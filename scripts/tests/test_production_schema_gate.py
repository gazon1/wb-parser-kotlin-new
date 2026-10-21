#!/usr/bin/env python3
"""The control for the production-schema gate: it must see the whole package.

What this catches
-----------------
`scripts/check-production-schema.py` used to read one file of the catalog's package —
`CatalogQueries.kt` — to learn which tables the API reads. That file became a facade:
it holds no SQL of its own, and the decisions it used to hold live in ten files now.
The gate kept passing, because the facade names both tables in its documentation, and
the gate was reading a sentence.

So a table added to any of the parts would be read by the API and invisible to the
gate: the gate would say "the schema has `scraped_items` and `crawl_targets`" about a
catalog that also reads a third table. A check that has quietly stopped looking is
worse than no check — it reports success about a subject it no longer observes.

The control is end-to-end on purpose: it puts a table into a part of a fixture package
and runs the real gate, unmodified, over that fixture. The gate resolves its package
from the tree it lives in, so a copy of it in a fixture tree reads the fixture. There
are no mocks and no fakes — the same script, the same code path, a different tree.
"""

import importlib.util
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
GATE = SCRIPTS / "check-production-schema.py"


def load(name: str, filename: str):
    """Import a gate script by path.

    The gate scripts are not a package and are named with dashes, so they cannot be
    imported normally. `spec_from_file_location` keeps the import honest without
    renaming files or adding an `__init__.py` that would imply one.
    """
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    assert spec and spec.loader, f"cannot load {filename}"
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


SCHEMA_GATE = load("production_schema", "check-production-schema.py")


#: The facade the split left behind: no SQL of its own, both tables named in its
#: documentation. A gate that reads this file sees two tables — and only two.
FACADE = """package ru.wbparser.app.api.catalog

/**
 * The catalog's read API: the facade over the decisions the catalog holds.
 *
 * The catalog reads the crawler's observations and the crawler's catalogs:
 * `FROM scraped_items s JOIN crawl_targets t ON t.id = s.target_id`.
 */
class CatalogQueries
"""

#: A part of the package that reads a third table — one the gate's own statements do
#: not know about.
PART_WITH_A_THIRD_TABLE = """package ru.wbparser.app.api.catalog

internal class CatalogItemQueries {
    fun listItems(): String {
        val sql = \"\"\"
            SELECT s.id, s.title
              FROM scraped_items s
              JOIN crawl_targets t ON t.id = s.target_id
             WHERE s.id IN (SELECT id FROM catalog_price_history)
        \"\"\".trimIndent()
        return sql
    }
}
"""

#: A facade whose documentation mentions a table that no query reads. Prose is not a
#: read, and a sentence must not be able to keep the gate quiet — or to make it cry.
FACADE_NAMING_A_TABLE_NOBODY_READS = """package ru.wbparser.app.api.catalog

/**
 * The catalog's read API: the facade over the decisions the catalog holds.
 *
 * Before the read model the catalog read `FROM legacy_price_history` directly. It does
 * not any more, and the schema has not carried that table for a long time.
 */
class CatalogQueries
"""


def gate_tree(tmp: str, files: dict[str, str]) -> Path:
    """A copy of the real gate in a tree whose catalog package is the fixture.

    The gate resolves its package from the tree it lives in, so a copy of it in a
    fixture tree reads the fixture. The script is the real one, byte for byte.
    """
    root = Path(tmp)
    (root / "scripts").mkdir()
    script = root / "scripts" / GATE.name
    shutil.copy(GATE, script)

    package = root / "app" / "src" / "main" / "kotlin" / "ru" / "wbparser" / "app" / "api" / "catalog"
    package.mkdir(parents=True)
    for name, content in files.items():
        (package / name).write_text(content, encoding="utf-8")

    return script


def run_gate(script: Path) -> subprocess.CompletedProcess:
    """Run the gate the way an operator would.

    The URL points nowhere on purpose: the gate has to stop at the disagreement about
    the tables *before* it reaches for a database. A gate that walks on to the
    database has not noticed the disagreement, and the output says so — there is no
    table named in it.
    """
    return subprocess.run(
        [sys.executable, str(script), "--url", "postgresql://nobody@127.0.0.1:1/none"],
        capture_output=True,
        text=True,
        check=False,
    )


class TestTheGateSeesTheWholePackage(unittest.TestCase):
    """The defect this change exists to prevent."""

    def test_a_table_that_a_part_reads_is_named(self):
        """A table in a part of the package must stop the gate, by name.

        Before the change the gate read the facade alone, so it never saw this table
        and reported nothing: the gate said the schema was fine while the API read a
        third table.
        """
        with tempfile.TemporaryDirectory() as tmp:
            script = gate_tree(
                tmp,
                {
                    "CatalogQueries.kt": FACADE,
                    "CatalogItemQueries.kt": PART_WITH_A_THIRD_TABLE,
                },
            )
            result = run_gate(script)
            output = result.stdout + result.stderr

            self.assertIn(
                "catalog_price_history",
                output,
                f"the gate did not name the table the part reads:\n{output}",
            )

    def test_the_finding_says_which_file_reads_the_table(self):
        """A finding without the file leaves the reader hunting for the read."""
        with tempfile.TemporaryDirectory() as tmp:
            script = gate_tree(
                tmp,
                {
                    "CatalogQueries.kt": FACADE,
                    "CatalogItemQueries.kt": PART_WITH_A_THIRD_TABLE,
                },
            )
            result = run_gate(script)
            output = result.stdout + result.stderr

            self.assertIn("CatalogItemQueries.kt", output, output)

    def test_a_table_named_only_in_prose_is_not_a_read(self):
        """Documentation is not a query, and it must not decide what the gate checks."""
        with tempfile.TemporaryDirectory() as tmp:
            script = gate_tree(tmp, {"CatalogQueries.kt": FACADE_NAMING_A_TABLE_NOBODY_READS})
            result = run_gate(script)
            output = result.stdout + result.stderr

            self.assertNotIn(
                "legacy_price_history",
                output,
                f"the gate treated a sentence as a read:\n{output}",
            )

    def test_the_two_tables_of_the_catalog_are_not_reported(self):
        """The tables the gate covers are not findings — the gate is about the third one."""
        with tempfile.TemporaryDirectory() as tmp:
            script = gate_tree(
                tmp,
                {
                    "CatalogQueries.kt": FACADE,
                    "CatalogItemQueries.kt": PART_WITH_A_THIRD_TABLE,
                },
            )
            result = run_gate(script)
            output = result.stdout + result.stderr

            self.assertNotIn("`scraped_items`", output, output)
            self.assertNotIn("`crawl_targets`", output, output)


class TestTheRealCatalog(unittest.TestCase):
    """The gate has to understand the catalog as it is, not as a fixture."""

    def test_the_gate_finds_no_disagreement_with_the_real_catalog(self):
        self.assertEqual(
            SCHEMA_GATE.check_schema_agreement(),
            [],
            "the gate and the catalog disagree about what the catalog reads",
        )

    def test_the_catalog_reads_two_tables_and_no_cte_names(self):
        """A CTE is not a table.

        The read model builds its rows under the names `model`, `counted`, `current`,
        `best` and `i`. A gate that reported them would report names the database has
        never heard of — and it would do so on every run, which is how a gate that
        cries wolf gets ignored.
        """
        reads = SCHEMA_GATE.tables_read_by(
            SCHEMA_GATE.catalog_sources(SCHEMA_GATE.CATALOG_PACKAGE),
        )

        self.assertEqual(sorted(reads), ["crawl_targets", "scraped_items"], reads)


if __name__ == "__main__":
    unittest.main()

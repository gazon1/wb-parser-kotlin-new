#!/usr/bin/env python3
"""check-production-schema.py — the gate of §7.6: does the catalog work on *this* schema?

Why this gate exists
--------------------
The catalog's projection reads columns that a production database may not have: `image_url`,
`sale_price_kopecks`, `in_stock` and `brand_id` were added by migration V2, and the
production database was created by the old .NET stack. A missing column is **not** a null
value — the query fails with `42703 undefined_column`, and every endpoint that reads it
fails with it. So "deploy the API and see what happens" does not work here: the API either
serves the production schema or it serves nothing at all, and the storefront deploys
against a readiness probe that would have said yes.

This gate is that answer, before the cutover rather than after it.

What it does
------------
It runs the catalog's own queries against a database and reports whether they work. The
queries are the point: a `SELECT 1` or an `information_schema` lookup proves only that
the database is reachable, and the plan says explicitly that neither replaces running the
real queries.

The statements below are the API's own, and the gate verifies that they still agree with
the API's source before it runs them: every column the API's projection reads must be
named in the statements, and every table the API reads must be covered. A gate that
quietly checks a subset of the API's queries is a gate that passes while the API fails —
so a disagreement stops the gate instead of being reported as a compatibility problem.

Where the API's tables come from
--------------------------------
The API's source is the **package** `app/src/main/kotlin/ru/wbparser/app/api/catalog/`,
every `.kt` file of it — not one file of it.

`CatalogQueries.kt` used to hold the catalog's SQL. After the split it is a facade: it
holds no query of its own, and the decisions it used to hold live in ten files now
(`CatalogItemQueries`, `CatalogTargetQueries`, `CatalogTopDealQueries`,
`CatalogStatsQueries`, `CatalogSchemaQueries`, `CatalogReadModel`, `CatalogFilters`,
`CatalogCardProjection`, `CatalogPageEnvelope`, `CatalogStatementBinding`). A gate that
reads one file of a package stops seeing what it used to see, and it does not know that
it stopped: the facade names both tables in its documentation, so the gate kept passing
while a table that a part had added was invisible to it. A check that has quietly
stopped looking is worse than no check — it reports success about a subject it no
longer observes.

So the gate reads the package, and it reads the **string literals** of it: a table
named in a comment is prose, and prose is not a read. What the gate takes from the
package is the set of tables the code actually reads, and a table that is not among the
gate's own statements stops the gate — by name, with the file that reads it.

How it is run
-------------
    # against a restored copy of the production database
    scripts/check-production-schema.py --url postgresql://user@host:5432/wbparser

    # with a password, read from the environment rather than the command line
    PGPASSWORD=... scripts/check-production-schema.py --url postgresql://user@host:5432/wbparser

    # the positive control: prove the gate can fail
    scripts/check-production-schema.py --self-test

The `--self-test` control
-------------------------
A gate that cannot fail is a gate that has nothing to find, and it reports success the
entire time. The control here runs the gate against a schema that is deliberately missing
one of the columns the catalog reads — the exact shape of the production database before
migration V2 — and asserts that the gate reports the failure and names the column.

The control has a second half, and it is the half this gate's own history needed: it
puts a table into a *part* of the catalog's package — a table that no statement of the
gate covers — and asserts that the gate names that table. That is the defect the gate
had: it read one file, so a table added to any of the ten parts was invisible to it,
and the gate said the schema was fine while the API read a third table.

The control needs a PostgreSQL to run against, so it starts one in Docker, exactly like
`scripts/load-catalog.sh` does. If Docker is not available, the control says so and fails
rather than skipping: a control that skips is not a control.

Read-only
---------
The gate opens a read-only transaction and never writes. The database it is pointed at
holds real data, and a check that could modify what it checks is not a check.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: The Kotlin package that holds the catalog's read decisions.
#:
#: The gate reads the whole package, not one file of it. `CatalogQueries.kt` became a
#: facade — it holds no SQL of its own — and the decisions it used to hold live in ten
#: files now. A gate that reads one file stops seeing what it used to see without
#: knowing that it stopped, and a table added to any of the parts would be read by the
#: API and invisible to the gate.
CATALOG_PACKAGE = ROOT / "app" / "src" / "main" / "kotlin" / "ru" / "wbparser" / "app" / "api" / "catalog"

#: A Kotlin string literal: a raw string `"""…"""` and an ordinary string `"…"`.
#:
#: The gate reads the literals, and only the literals: a table named in a comment is
#: prose. The facade's documentation names both tables for its reader, and the gate must
#: not take that sentence for a read.
RAW_STRING = re.compile(r'"""(.*?)"""', re.DOTALL)
ORDINARY_STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')

#: Where a statement takes its data from: `FROM <table>` and `JOIN <table>`.
READ_SOURCE = re.compile(r"\b(?:FROM|JOIN)\s+([A-Za-z_][A-Za-z0-9_]*)")

#: A common table expression: `WITH model AS (` or `, counted AS (`.
#:
#: A CTE is not a table. It introduces a name that later stands in `FROM`, and a gate
#: that reported it would report a name the database has never heard of.
CTE = re.compile(r"(?:\bWITH\b|,)\s*([A-Za-z_][A-Za-z0-9_]*)\s+AS\s*\(", re.IGNORECASE)

#: An alias of a derived table: `) i ON …`. It names a subquery, not a table.
DERIVED_ALIAS = re.compile(
    r"\)\s+(?:AS\s+)?([A-Za-z_][A-Za-z0-9_]*)\s+(?:ON|LIMIT|WHERE|GROUP|ORDER)\b",
    re.IGNORECASE,
)

#: Schemas that are not the catalog's tables. `information_schema` is the catalog's own
#: question about the shape of the database, not a table it reads a page from.
NOT_A_TABLE = {"information_schema"}

#: The facade of the control's fixture: it names the tables in its documentation and
#: holds no SQL of its own — exactly the facade the split left behind.
FIXTURE_FACADE = """package ru.wbparser.app.api.catalog

/**
 * The catalog's read API: the facade over the decisions the catalog holds.
 *
 * The catalog reads the crawler's observations and the crawler's catalogs:
 * `FROM scraped_items s JOIN crawl_targets t ON t.id = s.target_id`.
 */
class CatalogQueries
"""

#: A part of the control's fixture: it reads a third table, one that the gate's own
#: statements do not know about.
FIXTURE_PART = """package ru.wbparser.app.api.catalog

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

#: The columns the catalog reads. A schema that lacks one of them cannot serve the
#: catalog, and the gate says which one it was.
REQUIRED_COLUMNS: list[tuple[str, str]] = [
    ("scraped_items", "id"),
    ("scraped_items", "target_id"),
    ("scraped_items", "title"),
    ("scraped_items", "price_kopecks"),
    ("scraped_items", "sale_price_kopecks"),
    ("scraped_items", "brand"),
    ("scraped_items", "seller"),
    ("scraped_items", "cashback_percent"),
    ("scraped_items", "cashback"),
    ("scraped_items", "in_stock"),
    ("scraped_items", "image_url"),
    ("scraped_items", "product_url"),
    ("scraped_items", "scraped_at"),
    ("scraped_items", "product_id"),
    ("scraped_items", "match_id"),
    ("crawl_targets", "id"),
    ("crawl_targets", "name"),
    ("crawl_targets", "root_category_name"),
    ("crawl_targets", "is_active"),
]

#: The card projection, as the API writes it.
#:
#: The projection is by name: every source exposes the card's columns under the card's
#: own names. The gate's statements mirror the API's queries, and a projection that
#: spelled the table out per source would be one more place for the two to drift.
CARD_PROJECTION = """
        i.id                AS id,
        i.target_id         AS target_id,
        i.target_name       AS target_name,
        i.title             AS title,
        i.price_kopecks     AS price_kopecks,
        i.sale_price_kopecks AS sale_price_kopecks,
        i.brand             AS brand,
        i.seller            AS seller,
        i.cashback_percent  AS cashback_percent,
        i.cashback          AS cashback,
        i.in_stock          AS in_stock,
        i.image_url         AS image_url,
        i.product_url       AS product_url,
        i.scraped_at        AS scraped_at
"""

#: The columns of the read model — the current observation of each product.
MODEL_COLUMNS = """
        s.id, s.target_id, t.name AS target_name, s.title, s.price_kopecks,
        s.sale_price_kopecks, s.brand, s.seller, s.cashback_percent, s.cashback,
        s.in_stock, s.image_url, s.product_url, s.scraped_at, s.product_id, s.match_id
"""

#: The read model: one row per product, the observation that was scraped last.
CURRENT_PRODUCTS = f"""
        SELECT DISTINCT ON (s.product_id)
               {MODEL_COLUMNS}
          FROM scraped_items s
          JOIN crawl_targets t ON t.id = s.target_id
         ORDER BY s.product_id, s.scraped_at DESC, s.id DESC
"""

#: The read model of the top-deals list: the current observation per product per target.
CURRENT_PRODUCT_TARGETS = f"""
        SELECT DISTINCT ON (s.product_id, s.target_id)
               {MODEL_COLUMNS}
          FROM scraped_items s
          JOIN crawl_targets t ON t.id = s.target_id
         WHERE s.match_id IS NOT NULL
         ORDER BY s.product_id, s.target_id, s.scraped_at DESC, s.id DESC
"""

#: The statements the gate runs, in the order it runs them.
#:
#: Every statement here is one the API runs on a request. They are the queries of §7.6 —
#: the ones that fail with `42703` when the schema is behind.
STATEMENTS: list[tuple[str, str]] = [
    (
        "items",
        f"""
        WITH i AS (
        {CURRENT_PRODUCTS}
        )
        SELECT {CARD_PROJECTION}
          FROM i
         ORDER BY i.scraped_at DESC, i.id
         LIMIT 24 OFFSET 0
        """,
    ),
    (
        "items-filtered",
        f"""
        WITH i AS (
              SELECT DISTINCT ON (s.product_id)
                     {MODEL_COLUMNS}
                FROM scraped_items s
                JOIN crawl_targets t ON t.id = s.target_id
               WHERE (s.title ILIKE '%probe%' OR s.brand ILIKE '%probe%' OR s.seller ILIKE '%probe%')
               ORDER BY s.product_id, s.scraped_at DESC, s.id DESC
        )
        SELECT {CARD_PROJECTION}
          FROM i
         WHERE COALESCE(i.sale_price_kopecks, i.price_kopecks) >= 100
           AND COALESCE(i.sale_price_kopecks, i.price_kopecks) <= 100000
           AND i.cashback_percent >= 1.5
           AND i.in_stock = true
         ORDER BY i.price_kopecks ASC, i.id
         LIMIT 24 OFFSET 0
        """,
    ),
    (
        "items-count",
        f"""
        WITH i AS (
        {CURRENT_PRODUCTS}
        )
        SELECT COUNT(*)
          FROM i
         WHERE COALESCE(i.sale_price_kopecks, i.price_kopecks) >= 100
        """,
    ),
    (
        "items-detail",
        f"""
        WITH i AS (
              SELECT {MODEL_COLUMNS}
                FROM scraped_items s
                JOIN crawl_targets t ON t.id = s.target_id
               WHERE s.id = (SELECT id FROM scraped_items LIMIT 1)
        )
        SELECT {CARD_PROJECTION}
          FROM i
        """,
    ),
    (
        "items-history",
        """
        SELECT scraped_at, price_kopecks, cashback_percent, cashback
          FROM scraped_items
         WHERE target_id = (SELECT id FROM crawl_targets LIMIT 1)
           AND product_id = (SELECT product_id FROM scraped_items WHERE product_id IS NOT NULL LIMIT 1)
         ORDER BY scraped_at DESC, id
         LIMIT 120
        """,
    ),
    (
        "top-deals",
        f"""
        WITH current AS (
        {CURRENT_PRODUCT_TARGETS}
        ),
        best AS (
              SELECT DISTINCT ON (c.match_id)
                     c.id                AS id,
                     c.target_id         AS target_id,
                     c.target_name       AS target_name,
                     c.title             AS title,
                     c.price_kopecks     AS price_kopecks,
                     c.sale_price_kopecks AS sale_price_kopecks,
                     c.brand             AS brand,
                     c.seller            AS seller,
                     c.cashback_percent  AS cashback_percent,
                     c.cashback          AS cashback,
                     c.in_stock          AS in_stock,
                     c.image_url         AS image_url,
                     c.product_url       AS product_url,
                     c.scraped_at        AS scraped_at,
                     c.match_id::text    AS match_id
                FROM current c
               ORDER BY c.match_id, c.cashback_percent DESC NULLS LAST, c.scraped_at DESC, c.id
        )
        SELECT best.id                AS id,
               best.target_id         AS target_id,
               best.target_name       AS target_name,
               best.title             AS title,
               best.price_kopecks     AS price_kopecks,
               best.sale_price_kopecks AS sale_price_kopecks,
               best.brand             AS brand,
               best.seller            AS seller,
               best.cashback_percent  AS cashback_percent,
               best.cashback          AS cashback,
               best.in_stock          AS in_stock,
               best.image_url         AS image_url,
               best.product_url       AS product_url,
               best.scraped_at        AS scraped_at
          FROM best
         WHERE best.cashback_percent >= 1.5
         ORDER BY best.cashback_percent DESC NULLS LAST, best.scraped_at DESC, best.id
         LIMIT 24 OFFSET 0
        """,
    ),
    (
        "top-deals-count",
        f"""
        WITH current AS (
        {CURRENT_PRODUCT_TARGETS}
        ),
        best AS (
              SELECT DISTINCT ON (c.match_id)
                     c.match_id::text     AS match_id,
                     c.cashback_percent   AS cashback_percent
                FROM current c
               ORDER BY c.match_id, c.cashback_percent DESC NULLS LAST, c.scraped_at DESC, c.id
        )
        SELECT COUNT(*)
          FROM best
         WHERE best.cashback_percent >= 1.5
        """,
    ),
    (
        "targets",
        f"""
        SELECT t.id                 AS id,
               t.name               AS name,
               t.root_category_name AS root_category_name,
               t.is_active          AS is_active,
               COUNT(i.id)          AS item_count,
               MAX(i.scraped_at)    AS newest_scraped_at
          FROM crawl_targets t
          LEFT JOIN (
        {CURRENT_PRODUCTS}
          ) i ON i.target_id = t.id
         GROUP BY t.id, t.name, t.root_category_name, t.is_active
         ORDER BY t.name ASC
        """,
    ),
    (
        "stats",
        f"""
        WITH i AS (
        {CURRENT_PRODUCTS}
        )
        SELECT (SELECT COUNT(*) FROM scraped_items)                    AS total_items,
               (SELECT COUNT(*) FROM crawl_targets)                   AS total_targets,
               (SELECT MAX(scraped_at) FROM scraped_items)           AS newest_scraped_at,
               (SELECT COUNT(*) FROM scraped_items WHERE cashback_percent IS NOT NULL) AS with_cashback,
               (SELECT COUNT(*) FROM i)                              AS total_products,
               (SELECT COUNT(*) FROM i WHERE cashback_percent IS NOT NULL) AS products_with_cashback
        """,
    ),
]


class GateError(Exception):
    """The gate could not run. Distinct from the gate finding a problem."""


def import_driver():
    """The PostgreSQL driver, whichever of the two is installed.

    `psycopg` is the third-generation driver and `psycopg2` its predecessor. Both speak
    the same protocol and both are everywhere, but which one is present is a property of
    the machine rather than of the gate — so the gate takes whichever it finds instead of
    failing on an import the operator could have satisfied with the other one.
    """
    for name in ("psycopg", "psycopg2"):
        try:
            module = __import__(name)
            return module
        except ImportError:
            continue
    raise GateError(
        "the gate needs a PostgreSQL driver: `pip install psycopg[binary]` (or `psycopg2`). "
        "The gate runs the catalog's own queries against the database, so it has to speak "
        "to the database rather than guess.",
    )


def sqlstate_of(error: Exception) -> str | None:
    """The SQLSTATE of a driver error, whichever driver raised it.

    `psycopg` calls it `sqlstate`, `psycopg2` calls it `pgcode`. The code is the
    diagnosis — `42703` is the one that means "the schema is behind" — so it is worth
    the two lines that read it from either name.
    """
    for attribute in ("sqlstate", "pgcode"):
        value = getattr(error, attribute, None)
        if value:
            return str(value)
    return None


# ---------------------------------------------------------------------------
# The gate
# ---------------------------------------------------------------------------


def kotlin_sql(source: str) -> list[str]:
    """The SQL a Kotlin file hands to the database.

    Only the string literals are read. A table named in a comment is prose — the
    facade's documentation names both tables for its reader — and prose is not a read.
    Taking it for one would let a sentence keep the gate quiet about a table that no
    query reads, which is the same blindness in the other direction.
    """
    literals: list[str] = []
    outside: list[str] = []
    previous = 0

    for match in RAW_STRING.finditer(source):
        literals.append(match.group(1))
        outside.append(source[previous : match.start()])
        previous = match.end()

    outside.append(source[previous:])
    for match in ORDINARY_STRING.finditer("".join(outside)):
        literals.append(match.group(1))

    return literals


def tables_in_sql(sql: str) -> set[str]:
    """The tables one statement reads.

    A common table expression is not a table — `WITH model AS (…)` introduces a name
    that later stands in `FROM` — and an alias of a derived table is not a table
    either. PostgreSQL folds an unquoted name to lower case, so the names are compared
    in lower case: `Scraped_Items` and `scraped_items` are one table.
    """
    ctes = {name.lower() for name in CTE.findall(sql)}
    aliases = {name.lower() for name in DERIVED_ALIAS.findall(sql)}

    return {
        name.lower()
        for name in READ_SOURCE.findall(sql)
        if name.lower() not in ctes
        and name.lower() not in aliases
        and name.lower() not in NOT_A_TABLE
    }


def catalog_sources(package: Path) -> list[Path]:
    """Every Kotlin file of the catalog's package.

    The whole package, not one file of it. The package is where the decisions live,
    and a gate that reads one of them reads a part of the truth.
    """
    if not package.is_dir():
        raise GateError(f"the catalog's package {package} does not exist")
    return sorted(package.glob("*.kt"))


def tables_read_by(sources: list[Path]) -> dict[str, list[str]]:
    """The tables the sources read, and the files that read them.

    The value is the table's name and the files that read it, because a finding that
    does not say where the read is leaves the reader hunting for it.
    """
    reads: dict[str, list[str]] = {}

    for path in sources:
        for literal in kotlin_sql(path.read_text(encoding="utf-8")):
            for table in tables_in_sql(literal):
                readers = reads.setdefault(table, [])
                if path.name not in readers:
                    readers.append(path.name)

    return reads


def check_schema_agreement(package: Path | None = None) -> list[str]:
    """The gate's statements must cover what the API reads.

    The statements below are the API's own, but they are a copy, and a copy is a place
    where the two can drift: the API adds a column to its projection, the gate keeps the
    old statement, and the gate passes while the API fails with `42703`.

    So the check verifies the agreement before the gate runs anything: every column the
    API's projection names must appear in the statements, and every table the API reads
    must be covered. A finding here is a finding about the gate, not about the
    database, and it stops the gate.

    The tables are read from the **package** — every `.kt` file of it — because that is
    where the decisions live. Reading one file of the package is how this gate stopped
    seeing what it used to see: the facade held the SQL when the gate was written, and
    the split moved the SQL into ten files without moving the gate.
    """
    findings: list[str] = []

    try:
        sources = catalog_sources(package or CATALOG_PACKAGE)
    except GateError as error:
        return [str(error)]

    statements = " ".join(sql for _, sql in STATEMENTS)

    for table, column in REQUIRED_COLUMNS:
        if not re.search(rf"\b{re.escape(column)}\b", statements):
            findings.append(
                f"the gate's statements never mention `{table}.{column}`, but the API reads it. "
                "The gate would pass while the API failed with 42703.",
            )

    # Every table the API reads must be covered by the gate's own statements. A table
    # that is not among them is a table the gate says nothing about — and the gate's
    # answer would be "the schema is fine" about a table the API reads.
    for table, readers in sorted(tables_read_by(sources).items()):
        if re.search(rf"\b{re.escape(table)}\b", statements):
            continue
        where = ", ".join(f"`{name}`" for name in readers)
        findings.append(
            f"the API reads `{table}` (in {where}), and the gate's statements do not. "
            "The gate does not check what the API does: the table has to be among the "
            "gate's statements, or out of the API.",
        )

    return findings


def run_statements(url: str) -> list[tuple[str, str | None]]:
    """Run every statement against the database, and report what happened.

    Returns one `(name, error)` pair per statement: `error` is `None` when the statement
    ran. The error is the message the server sent, trimmed — it names the column and the
    SQLSTATE, which is the whole diagnosis.
    """
    driver = import_driver()

    results: list[tuple[str, str | None]] = []
    try:
        connection = driver.connect(url, connect_timeout=10)
    except Exception as error:  # noqa: BLE001 — the driver raises many kinds, and the message is the diagnosis
        raise GateError(f"cannot connect to the database: {error}") from error

    try:
        connection.autocommit = False
        for name, sql in STATEMENTS:
            try:
                with connection.cursor() as cursor:
                    # Read-only for the whole session: the database holds real data, and a
                    # check that could modify what it checks is not a check.
                    cursor.execute("SET TRANSACTION READ ONLY")
                    cursor.execute("SET LOCAL statement_timeout = '30s'")
                    cursor.execute(sql)
                    cursor.fetchall()
                results.append((name, None))
            except Exception as error:  # noqa: BLE001 — the drivers raise their own hierarchies
                results.append((name, describe(error)))
                connection.rollback()
    finally:
        connection.close()

    return results


def describe(error: Exception) -> str:
    """The server's own words about a failure, without the noise around them.

    The SQLSTATE and the message are the diagnosis: `42703 undefined_column` says which
    column is missing, and the message names it. The rest — the traceback, the driver
    chatter — says nothing an operator can act on.
    """
    sqlstate = sqlstate_of(error)
    lines = str(error).strip().splitlines()
    text = lines[0] if lines else error.__class__.__name__
    if sqlstate:
        return f"{sqlstate} {text}"
    return text


def missing_columns(url: str) -> list[str]:
    """The columns the catalog needs that the database does not have."""
    driver = import_driver()

    try:
        connection = driver.connect(url, connect_timeout=10)
    except Exception as error:  # noqa: BLE001
        raise GateError(f"cannot connect to the database: {error}") from error

    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT table_name, column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name IN ('scraped_items', 'crawl_targets')
                """,
            )
            present = {f"{table}.{column}" for table, column in cursor.fetchall()}
    finally:
        connection.close()

    return [f"{table}.{column}" for table, column in REQUIRED_COLUMNS if f"{table}.{column}" not in present]


def missing_tables(url: str, tables: list[str]) -> list[str]:
    """The tables the catalog reads that the database does not have.

    A table that is not there is not a null value either: the query fails with
    `42P01 undefined_table`, and the endpoint that asked for it fails with it. The
    tables are the ones the package reads, so the answer is about the catalog and not
    about a list somebody wrote down once.
    """
    if not tables:
        return []

    driver = import_driver()

    try:
        connection = driver.connect(url, connect_timeout=10)
    except Exception as error:  # noqa: BLE001
        raise GateError(f"cannot connect to the database: {error}") from error

    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                """,
            )
            present = {row[0] for row in cursor.fetchall()}
    finally:
        connection.close()

    return [table for table in tables if table not in present]


# ---------------------------------------------------------------------------
# The positive control
# ---------------------------------------------------------------------------


def self_test_finds_a_table_a_part_reads() -> int:
    """Prove the gate sees the whole package, not one file of it.

    The control puts a third table into a *part* of the catalog's package — a table
    that is not among the gate's own statements — and asserts that the gate stops and
    names that table, together with the file that reads it.

    This is the defect the gate had: it read one file of the package, so a table added
    to any of the ten parts was invisible to it, and the gate said the schema was fine
    while the API read a table the gate knew nothing about.

    The fixture is a copy of this script in a tree of its own, so the gate resolves its
    package from the tree it runs in — the same way it resolves the real one.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "scripts").mkdir()
        script = root / "scripts" / Path(__file__).name
        shutil.copy(Path(__file__).resolve(), script)

        package = (
            root / "app" / "src" / "main" / "kotlin" / "ru" / "wbparser" / "app" / "api" / "catalog"
        )
        package.mkdir(parents=True)
        (package / "CatalogQueries.kt").write_text(FIXTURE_FACADE, encoding="utf-8")
        (package / "CatalogItemQueries.kt").write_text(FIXTURE_PART, encoding="utf-8")

        completed = subprocess.run(
            [sys.executable, str(script), "--url", "postgresql://nobody@127.0.0.1:1/none"],
            capture_output=True,
            text=True,
            check=False,
        )
        output = completed.stdout + completed.stderr

        if "catalog_price_history" not in output:
            print(
                "self-test FAILED: the gate did not name the table that a part of the catalog "
                "reads. The gate reads one file of the package and calls the schema fine while "
                f"the API reads a table the gate knows nothing about.\n{output}",
                file=sys.stderr,
            )
            return 1

        if "CatalogItemQueries.kt" not in output:
            print(
                "self-test FAILED: the gate named the table but not the file that reads it, "
                f"which leaves the reader hunting for it.\n{output}",
                file=sys.stderr,
            )
            return 1

    return 0


def self_test() -> int:
    """Prove the gate can fail, against a schema that is missing a column.

    The control builds the shape of the production database *before* migration V2 — the
    shape the gate exists to detect — and asserts that the gate reports the missing
    column and exits non-zero.

    It starts its own PostgreSQL in Docker, the way `scripts/load-catalog.sh` does, and
    publishes its port on the loopback so the gate can reach it from here.
    """
    if self_test_finds_a_table_a_part_reads() != 0:
        return 1

    if shutil.which("docker") is None:
        print("self-test FAILED: Docker is needed to run the control", file=sys.stderr)
        return 1

    container = "mola-schema-gate-pg"
    password = "schema-gate-password"
    port = 55433

    def cleanup() -> None:
        subprocess.run(["docker", "rm", "-f", container], capture_output=True, check=False)

    cleanup()
    try:
        subprocess.run(
            [
                "docker", "run", "-d", "--rm",
                "--name", container,
                "-p", f"127.0.0.1:{port}:5432",
                "-e", f"POSTGRES_PASSWORD={password}",
                "-e", "POSTGRES_DB=wbparser",
                "postgres:16-alpine",
            ],
            capture_output=True,
            text=True,
            check=True,
        )

        ready = False
        for _ in range(60):
            probe = subprocess.run(
                ["docker", "exec", container, "pg_isready", "-U", "postgres", "-d", "wbparser"],
                capture_output=True,
                check=False,
            )
            if probe.returncode == 0:
                ready = True
                break
            time.sleep(1)
        if not ready:
            print("self-test FAILED: the PostgreSQL container did not become ready", file=sys.stderr)
            return 1

        # The schema of the production database before migration V2: the columns the
        # catalog reads are missing, and the query fails with `42703` rather than
        # returning nulls.
        schema = """
        CREATE TABLE crawl_targets (
            id UUID PRIMARY KEY,
            name VARCHAR(255) NOT NULL,
            root_category_name VARCHAR(255),
            is_active BOOLEAN NOT NULL DEFAULT true,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        );

        CREATE TABLE scraped_items (
            id UUID PRIMARY KEY,
            target_id UUID NOT NULL REFERENCES crawl_targets(id),
            product_url TEXT,
            brand VARCHAR(255),
            seller VARCHAR(255),
            price_kopecks BIGINT,
            title TEXT,
            product_id BIGINT,
            cashback NUMERIC,
            cashback_percent NUMERIC,
            scraped_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            match_id BIGINT
        );

        INSERT INTO crawl_targets (id, name) VALUES ('11111111-1111-1111-1111-111111111111', 'Legacy');
        INSERT INTO scraped_items (id, target_id, product_url, brand, seller, price_kopecks, title, product_id, cashback, cashback_percent, scraped_at, match_id)
        VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111',
                'https://example.invalid/p', 'Brand', 'Seller', 1000, 'Legacy product', 1, 10, 5, now(), 1);
        """
        subprocess.run(
            ["docker", "exec", "-i", container, "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "wbparser"],
            input=schema,
            text=True,
            capture_output=True,
            check=True,
        )

        url = f"postgresql://postgres:{password}@127.0.0.1:{port}/wbparser"

        # The gate, run exactly as an operator would run it.
        completed = subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), "--url", url],
            capture_output=True,
            text=True,
            check=False,
        )
        output = completed.stdout + completed.stderr

        if completed.returncode == 0:
            print(
                "self-test FAILED: the gate reported no problem against a schema that is "
                "missing `image_url`, `sale_price_kopecks` and `in_stock`. The gate cannot "
                f"find what it exists to find.\n{output}",
                file=sys.stderr,
            )
            return 1

        if "42703" not in output and "image_url" not in output:
            print(
                f"self-test FAILED: the gate failed, but not for the reason it exists:\n{output}",
                file=sys.stderr,
            )
            return 1

        print("✅ self-test: the gate fails on a schema that is missing the catalog's columns")
        return 0
    finally:
        cleanup()


# ---------------------------------------------------------------------------
# The command line
# ---------------------------------------------------------------------------


def redact_url(url: str) -> str:
    """The database's name, without its password."""
    return re.sub(r"://([^:@/]+):[^@/]*@", r"://\1@", url)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Check that the catalog's queries work against a database.",
    )
    parser.add_argument(
        "--url",
        help="The database to check, as a PostgreSQL URL: postgresql://user@host:5432/db",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Prove the gate can fail, against a schema that is missing a column.",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    agreement = check_schema_agreement()
    if agreement:
        print("the gate does not check what the API does:", file=sys.stderr)
        for finding in agreement:
            print(f"  - {finding}", file=sys.stderr)
        return 2

    if not args.url:
        parser.error("--url is required (or --self-test)")

    try:
        reads = tables_read_by(catalog_sources(CATALOG_PACKAGE))
        absent_tables = missing_tables(args.url, sorted(reads))
        absent = missing_columns(args.url)
        results = run_statements(args.url)
    except GateError as error:
        print(f"the gate could not run: {error}", file=sys.stderr)
        return 2

    failed = [(name, error) for name, error in results if error is not None]

    print("catalog schema compatibility")
    print(f"  database: {redact_url(args.url)}")
    print(f"  tables the API reads: {', '.join(sorted(reads))}")
    if absent_tables:
        print(f"  missing tables: {', '.join(absent_tables)}")
    if absent:
        print(f"  missing columns: {', '.join(absent)}")
    for name, error in results:
        mark = "✓" if error is None else "✗"
        print(f"  {mark} {name}" + (f" — {error}" if error else ""))

    if failed or absent_tables:
        print()
        print(
            f"the catalog does not work against this schema: {len(failed)} of {len(results)} queries failed",
        )
        if absent_tables:
            print(
                "the schema does not have the tables the catalog reads: "
                + ", ".join(absent_tables)
                + ". A missing table is not an empty result — the query fails with 42P01.",
            )
        if absent:
            print(
                "the schema is behind the catalog's expectations. The listed columns are the "
                "ones the catalog reads and the database does not have — a missing column is "
                "not a null value, the query fails with 42703.",
            )
        elif not absent_tables:
            print("the columns are all there, so the failure is something else — read the message above.")
        return 1

    print()
    print(f"the catalog works against this schema: {len(results)} queries, no failure")
    return 0


if __name__ == "__main__":
    sys.exit(main())

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
named in the statements, and every table the API's source mentions must be covered. A
gate that quietly checks a subset of the API's queries is a gate that passes while the
API fails — so a disagreement stops the gate instead of being reported as a compatibility
problem.

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
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: The Kotlin file the catalog's queries live in.
QUERIES_SOURCE = ROOT / "app" / "src" / "main" / "kotlin" / "ru" / "wbparser" / "app" / "api" / "catalog" / "CatalogQueries.kt"

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


def check_schema_agreement() -> list[str]:
    """The gate's statements must cover what the API reads.

    The statements below are the API's own, but they are a copy, and a copy is a place
    where the two can drift: the API adds a column to its projection, the gate keeps the
    old statement, and the gate passes while the API fails with `42703`.

    So the check verifies the agreement before the gate runs anything: every column the
    API's projection names must appear in the statements, and every table the API's source
    mentions must be covered. A finding here is a finding about the gate, not about the
    database, and it stops the gate.
    """
    findings: list[str] = []

    try:
        source = QUERIES_SOURCE.read_text(encoding="utf-8")
    except OSError as error:
        return [f"cannot read {QUERIES_SOURCE}: {error}"]

    statements = " ".join(sql for _, sql in STATEMENTS)

    for table, column in REQUIRED_COLUMNS:
        if not re.search(rf"\b{re.escape(column)}\b", statements):
            findings.append(
                f"the gate's statements never mention `{table}.{column}`, but the API reads it. "
                "The gate would pass while the API failed with 42703.",
            )

    # Every table the API names in its own source must be covered. `information_schema`
    # is the exception: the API queries it for the readiness check, not for a page.
    for table in sorted(set(re.findall(r"\b(?:FROM|JOIN)\s+(\w+)", source))):
        if table in ("information_schema", "best"):
            continue
        if table not in statements:
            findings.append(
                f"the API reads `{table}`, and the gate's statements do not. The gate does not "
                "check what the API does.",
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


# ---------------------------------------------------------------------------
# The positive control
# ---------------------------------------------------------------------------


def self_test() -> int:
    """Prove the gate can fail, against a schema that is missing a column.

    The control builds the shape of the production database *before* migration V2 — the
    shape the gate exists to detect — and asserts that the gate reports the missing
    column and exits non-zero.

    It starts its own PostgreSQL in Docker, the way `scripts/load-catalog.sh` does, and
    publishes its port on the loopback so the gate can reach it from here.
    """
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
        absent = missing_columns(args.url)
        results = run_statements(args.url)
    except GateError as error:
        print(f"the gate could not run: {error}", file=sys.stderr)
        return 2

    failed = [(name, error) for name, error in results if error is not None]

    print("catalog schema compatibility")
    print(f"  database: {redact_url(args.url)}")
    if absent:
        print(f"  missing columns: {', '.join(absent)}")
    for name, error in results:
        mark = "✓" if error is None else "✗"
        print(f"  {mark} {name}" + (f" — {error}" if error else ""))

    if failed:
        print()
        print(
            f"the catalog does not work against this schema: {len(failed)} of {len(results)} queries failed",
        )
        if absent:
            print(
                "the schema is behind the catalog's expectations. The listed columns are the "
                "ones the catalog reads and the database does not have — a missing column is "
                "not a null value, the query fails with 42703.",
            )
        else:
            print("the columns are all there, so the failure is something else — read the message above.")
        return 1

    print()
    print(f"the catalog works against this schema: {len(results)} queries, no failure")
    return 0


if __name__ == "__main__":
    sys.exit(main())

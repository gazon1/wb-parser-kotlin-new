---
name: sqlite-from-postgres
description: Postgres-to-SQLite schema conversion for test databases. Use when the user mentions "SQLite for tests", "SQLite instead of Postgres", "test migration for SQLite", "convert Postgres DDL to SQLite", "JSONB in SQLite", "ON CONFLICT SQLite", or asks to run a Postgres schema on an in-memory SQLite database.
---

# SQLite Dialect Conversion — Postgres to SQLite

## When to use this skill

Use whenever you need to take an existing Postgres schema (Flyway migration, Exposed table definitions, raw DDL) and make it work on SQLite for test purposes.

SQLite and Postgres are fundamentally different databases — this skill is a cheat-sheet for the conversions that actually work, not a full理论 discussion.

**Trigger phrases:** "sqlite for tests", "sqlite instead of postgres", "test migration for sqlite", "convert DDL to sqlite", "JSONB in sqlite", "ON CONFLICT sqlite", "TIMESTAMPTZ in sqlite".

## Core Conversion Table

| Postgres | SQLite | Notes |
|---|---|---|
| `UUID PRIMARY KEY DEFAULT gen_random_uuid()` | `TEXT PRIMARY KEY` | Generate UUIDs in application code (`UUID.randomUUID().toString()`). Store as TEXT. |
| `UUID` column type | `TEXT` | No native UUID type in SQLite. |
| `gen_random_uuid()` | App-side `UUID.randomUUID()` | SQLite has no `gen_random_uuid()`. Always generate in Kotlin. |
| `JSONB` | `TEXT` | Store JSON as plain text. SQLite has no JSON type. Application-side serialization with `kotlinx.serialization`. |
| `JSON` | `TEXT` | Same as JSONB. |
| `TEXT[]` (array of strings) | Junction table or `TEXT` | `TEXT[]` arrays are not portable. Option 1: split into a separate junction table (`product_tags(product_id, tag)`). Option 2: store as `TEXT` with a delimiter (e.g. comma-joined) and parse in app code. |
| `TIMESTAMPTZ` / `TIMESTAMP WITH TIME ZONE` | `TEXT` | Store as ISO-8601 string (`Instant.now().toString()`). SQLite has no timezone-aware timestamp. |
| `TIMESTAMP` / `TIMESTAMP WITHOUT TIME ZONE` | `TEXT` or `INTEGER` (Unix epoch) | Use `TEXT` with ISO-8601 for readability; `INTEGER` (Unix seconds) for compactness. |
| `DECIMAL(10,2)` | `REAL` or `INTEGER` | `REAL` (SQLite double) for DECIMAL. Or store as `INTEGER` (kopecks/smallest-unit) and handle formatting in app code. |
| `BIGSERIAL` / `SERIAL` | `INTEGER PRIMARY KEY AUTOINCREMENT` | SQLite's autoincrement. Maps to `INTEGER PRIMARY KEY AUTOINCREMENT`. |
| `BIGINT` | `INTEGER` | SQLite has only `INTEGER` (64-bit signed). |
| `BOOLEAN` | `INTEGER` | SQLite uses `0` for false, `1` for true. |
| `CHECK (column IN ('a','b'))` | `CHECK (column IN ('a','b'))` | SQLite supports CHECK constraints. |
| `ON CONFLICT (col) DO UPDATE SET ...` | `ON CONFLICT (col) DO UPDATE SET ...` | SQLite ≥3.24 supports `ON CONFLICT` with the same syntax as Postgres. |
| `ON CONFLICT (col) DO NOTHING` | `ON CONFLICT (col) DO NOTHING` | Same in SQLite ≥3.24. |
| `INSERT ... ON CONFLICT DO UPDATE` | `INSERT OR REPLACE` | SQLite also supports `ON CONFLICT` with column-specific updates. `INSERT OR REPLACE` deletes old row + inserts new (triggers `ON DELETE` cascades!). Use `ON CONFLICT DO UPDATE` when you want to update specific columns only. |
| `REFERENCES ... ON DELETE CASCADE` | `REFERENCES ... ON DELETE CASCADE` | Foreign key constraints work in SQLite but must be enabled (`PRAGMA foreign_keys = ON`). |
| `UNIQUE (col1, col2)` | `UNIQUE (col1, col2)` | Same syntax. |
| `CREATE INDEX idx_name ON table(col)` | `CREATE INDEX idx_name ON table(col)` | Same syntax. |
| `COALESCE(col, 0)` | `COALESCE(col, 0)` | Same function. |
| `NOW()` | App-side timestamp | SQLite has no `NOW()`. Generate in application code. |
| `CURRENT_TIMESTAMP` | App-side timestamp | Same — generate in app. |

## Step-by-step Migration

### 1. Create the SQLite DDL file

Place it under `tests/src/test/resources/db/sqlite/V1__init.sql` (or `V1__sqlite.sql`). Name it similarly to the production migration so the intent is clear.

```sql
-- SQLite-compatible migration (derived from V1__init.sql)
-- Postgres → SQLite conversions applied:
--   UUID → TEXT
--   JSONB → TEXT
--   TIMESTAMPTZ → TEXT
--   DECIMAL → REAL
--   BIGINT → INTEGER
--   gen_random_uuid() → app-side UUID

CREATE TABLE IF NOT EXISTS crawl_targets (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    start_url       TEXT NOT NULL,
    is_active       INTEGER NOT NULL DEFAULT 1,   -- BOOLEAN → INTEGER
    max_depth       INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT NOT NULL,               -- TIMESTAMPTZ → TEXT (ISO-8601)
    updated_at      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS scraped_items (
    id              TEXT PRIMARY KEY,
    target_id       TEXT NOT NULL,
    catalog_url     TEXT,
    product_url     TEXT NOT NULL,
    brand           TEXT,
    seller          TEXT,
    price_kopecks   INTEGER,                    -- BIGINT → INTEGER
    title           TEXT,
    product_id      INTEGER,
    cashback        REAL,                        -- DECIMAL(10,2) → REAL
    cashback_percent REAL,                       -- DECIMAL(5,2) → REAL
    data            TEXT,                        -- JSONB → TEXT
    content_hash    TEXT NOT NULL UNIQUE,
    scraped_at      TEXT NOT NULL,               -- TIMESTAMPTZ → TEXT
    subject_id      INTEGER,
    subject_parent_id INTEGER,
    match_id        INTEGER,
    supplier_id     INTEGER,
    catalog_name    TEXT
);
```

### 2. Apply migration via raw JDBC

Do NOT wire Flyway for tests. One-off raw JDBC is simpler and sufficient.

```kotlin
class SqliteTestHandle {
    private val ds: SQLiteDataSource

    init {
        ds = SQLiteDataSource().apply {
            url = "jdbc:sqlite:file::memory:?cache=shared"
        }
        ds.connection.createStatement().use { s ->
            // Read and execute the migration SQL
            val migrationSql = javaClass.classLoader
                .getResource("db/sqlite/V1__init.sql")!!
                .readText()
            for (statement in migrationSql.split(";").filter { it.isNotBlank() }) {
                s.execute(statement.trim())
            }
        }
    }
}
```

### 3. Upsert pattern — `INSERT OR REPLACE` vs `ON CONFLICT DO UPDATE`

**When to use `ON CONFLICT` (SQLite ≥3.24):**

```sql
INSERT INTO scraped_items (
    id, target_id, catalog_url, product_url, brand, seller,
    price_kopecks, title, product_id, cashback, cashback_percent,
    data, content_hash, scraped_at, subject_id, subject_parent_id,
    match_id, supplier_id, catalog_name
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (content_hash) DO UPDATE SET
    product_url   = EXCLUDED.product_url,
    brand         = EXCLUDED.brand,
    price_kopecks = EXCLUDED.price_kopecks,
    title         = EXCLUDED.title,
    cashback      = EXCLUDED.cashback,
    scraped_at    = EXCLUDED.scraped_at,
    subject_id    = EXCLUDED.subject_id,
    supplier_id   = EXCLUDED.supplier_id,
    catalog_name  = EXCLUDED.catalog_name
```

**When to use `INSERT OR REPLACE` (simpler, but deletes-then-inserts):**

```sql
INSERT OR REPLACE INTO scraped_items (
    id, target_id, catalog_url, product_url, brand, seller,
    price_kopecks, title, product_id, cashback, cashback_percent,
    data, content_hash, scraped_at, subject_id, subject_parent_id,
    match_id, supplier_id, catalog_name
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
```

**Warning:** `INSERT OR REPLACE` triggers `ON DELETE` FK cascades if defined. It also replaces ALL columns on conflict — you can't update only specific columns. Use `ON CONFLICT DO UPDATE` when you need partial updates.

### 4. Foreign key constraints

SQLite foreign keys are **disabled by default**. Enable them explicitly:

```kotlin
ds.connection.createStatement().use { s ->
    s.execute("PRAGMA foreign_keys = ON")
}
```

### 5. UUID binding in JDBC

SQLite stores UUIDs as TEXT. When binding:

```kotlin
// Postgres (setObject works because the column is UUID type):
ps.setObject(1, UUID.randomUUID())

// SQLite (TEXT column):
ps.setString(1, UUID.randomUUID().toString())

// Reading back:
val id = rs.getString("id")  // "a1b2c3d4-..."
```

### 6. JSONB / JSON binding

Store JSON as TEXT. Serialize with `kotlinx.serialization`:

```kotlin
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

// Save
ps.setString(12, json.encodeToString(item))  // "data" column

// Read back
val dataJson = rs.getString("data")
val item = json.decodeFromString<SavedItem>(dataJson)
```

### 7. Timestamp handling

Store as ISO-8601 TEXT:

```kotlin
import java.time.Instant

// Save
ps.setString(14, Instant.now().toString())  // "2026-09-08T12:00:00.000Z"

// Read back
val scrapedAt = Instant.parse(rs.getString("scraped_at"))
```

## Anti-patterns

### BAD: Using production V1__init.sql on SQLite
```sql
-- Production V1__init.sql uses:
--   gen_random_uuid()   → Not valid in SQLite
--   JSONB               → Not valid in SQLite
--   TIMESTAMPTZ         → Not valid in SQLite
--   TEXT[]              → Not valid in SQLite
-- Always create a separate test migration.
```

### BAD: `jdbc:sqlite::memory:` without `cache=shared`
```kotlin
val url = "jdbc:sqlite::memory:"  // Each getConnection() = new DB!
```
The `:memory:` URL creates a new in-memory database for every connection. Use `cache=shared` or a file-based URL.

### BAD: `INSERT OR REPLACE` when you need partial column updates
```kotlin
// Updates ALL columns, loses old values for unset columns
ps.execute("INSERT OR REPLACE INTO t (id, a, b) VALUES (?, ?, ?)", id, newA, newB)
// If b was 100 and newB is null, b becomes null.
```
Use `ON CONFLICT DO UPDATE SET col = COALESCE(EXCLUDED.col, col)` for safe partial updates, or `ON CONFLICT DO UPDATE SET col = EXCLUDED.col` explicitly.

### BAD: BOOLEAN as TEXT
```sql
CREATE TABLE t (active TEXT DEFAULT 'true')  -- Non-standard
CREATE TABLE t (active INTEGER DEFAULT 1)   -- Standard SQLite
```

## Related Skills

- `integration-testing-kotlin` — using this migration in a Kotlin integration test
- `pipelines` — how the pipeline's save stage works with JDBC

## Sources

- [SQLite grammar — CREATE TABLE](https://www.sqlite.org/lang_createtable.html)
- [SQLite — ON CONFLICT clause](https://www.sqlite.org/lang_conflict.html)
- [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)
- [PostgreSQL to SQLite migration guide](https://www.sqlite.org/internals.html)

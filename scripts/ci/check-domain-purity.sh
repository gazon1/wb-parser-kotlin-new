#!/usr/bin/env bash
# Checks that the domain layer within the merged wbparser module contains no imports
# of infrastructure frameworks (java.sql, javax.sql, org.springframework, kafka, redis,
# ktor) — and no Exposed types either.
#
# ExposedTables.kt used to be exempt: it held the schema metadata inside the domain.
# It does not any more: the mapping of the database schema belongs to the
# infrastructure layer, where the queries that use it live, and the domain model is
# a set of Kotlin types that know nothing about the database.
set -euo pipefail
cd "$(dirname "$0")/../.."

PATTERN="^import[[:space:]]+(java\.sql|javax\.sql|org\.springframework|kafka|redis|ktor|org\.jetbrains\.exposed)"
# After the merge: domain sources live under wbparser/src/main/kotlin/ru/wbparser/domain/
SOURCES=$(find wbparser/src/main/kotlin/ru/wbparser/domain -name "*.kt" 2>/dev/null)

if [[ -z "$SOURCES" ]]; then
  exit 0
fi

# grep -l returns 0 (match) or 1 (no match); with pipefail the pipeline propagates grep's exit
if echo "$SOURCES" | xargs grep -HE "$PATTERN" > /dev/null 2>&1; then
  echo "Domain-purity violation — forbidden infrastructure imports found in domain sources:" >&2
  echo "$SOURCES" | xargs grep -HE "$PATTERN" >&2
  exit 1
fi

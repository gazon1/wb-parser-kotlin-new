#!/usr/bin/env bash
# Checks that the domain layer within the merged wbparser module contains no imports
# of infrastructure frameworks (java.sql, javax.sql, org.springframework, kafka, redis,
# ktor).  ExposedTables.kt is exempt: it holds compileOnly schema metadata.
set -euo pipefail
cd "$(dirname "$0")/../.."

PATTERN="^import[[:space:]]+(java\.sql|javax\.sql|org\.springframework|kafka|redis|ktor)"
# After the merge: domain sources live under wbparser/src/main/kotlin/ru/wbparser/domain/
SOURCES=$(find wbparser/src/main/kotlin/ru/wbparser/domain -name "*.kt" 2>/dev/null | grep -v "ExposedTables\.kt\$")

if [[ -z "$SOURCES" ]]; then
  exit 0
fi

# grep -l returns 0 (match) or 1 (no match); with pipefail the pipeline propagates grep's exit
if echo "$SOURCES" | xargs grep -HE "$PATTERN" > /dev/null 2>&1; then
  echo "Domain-purity violation — forbidden infrastructure imports found in domain sources:" >&2
  echo "$SOURCES" | xargs grep -HE "$PATTERN" >&2
  exit 1
fi

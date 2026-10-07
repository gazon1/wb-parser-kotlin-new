#!/usr/bin/env bash
# check.sh — the single local verification entry point for wb-parser-kotlin.
#
# `AGENTS.md` and `.just/tests/mod.just` both say `./check.sh`. Until this file
# existed they were both wrong: the recipe resolved `./check.sh` to a path that
# was not there, and the copy in `scripts/` computed ROOT as `scripts/` and then
# ran `./gradlew` from inside it. Both entry points failed and neither was
# covered, because each was the only one — which is why the static-gate registry
# now has two callers (this script and ci.yml's `static` job) and Part G of
# scripts/check-gate-wiring.py fails if that ever stops being true.
#
# Usage:
#   ./check.sh              # everything
#   SKIP_DB=1 ./check.sh    # skip the Testcontainers suites (no Docker)
#
# CI invokes Gradle directly and manages its own lifecycle; this script is the
# local loop, and the two are kept honest by Part E of check-gate-wiring.py,
# which requires any difference between them to be declared with a reason.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

SKIP_DB="${SKIP_DB:-0}"
TOTAL=8

step() { echo -e "\n${YELLOW}=== [$1/$TOTAL] $2 ===${NC}"; }
fail() { echo -e "${RED}$1${NC}"; exit 1; }

# --- [1] static gates --------------------------------------------------------
# Delegating here is the point of that file, not a convenience. Every gate that
# needs no JVM and no build output lives in scripts/ci/static-gates.sh, which
# ci.yml's `static` job also runs.
step 1 "static gates (the shared registry)"
bash scripts/ci/static-gates.sh || fail "a static gate failed — see scripts/ci/static-gates.sh"

# --- [2] domain tests --------------------------------------------------------
# The domain module carries no Spring, no database and no I/O. If its tests fail,
# every failure above it is downstream, so they run first.
step 2 "domain:test"
./gradlew :domain:test --quiet || fail "domain:test FAILED"
echo -e "${GREEN}domain:test passed${NC}"

# --- [3] infrastructure tests -------------------------------------------------
step 3 "infrastructure:test"
./gradlew :infrastructure:test --quiet || fail "infrastructure:test FAILED"
echo -e "${GREEN}infrastructure:test passed${NC}"

# --- [4] integration tests ----------------------------------------------------
if [[ "$SKIP_DB" == "1" ]]; then
    step 4 "tests:test — SKIPPED (SKIP_DB=1)"
    echo -e "${YELLOW}    skipped: these suites start Testcontainers${NC}"
else
    step 4 "tests:test (Testcontainers)"
    ./gradlew :tests:test --quiet || fail "tests:test FAILED"
    echo -e "${GREEN}tests:test passed${NC}"
fi

# --- [5] application compiles -------------------------------------------------
step 5 "app:compileKotlin"
./gradlew :app:compileKotlin --quiet || fail "app:compileKotlin FAILED"
echo -e "${GREEN}app:compileKotlin passed${NC}"

# --- [6] executed test counts -------------------------------------------------
# "Tests passed" is not "the tests ran". Gradle reports BUILD SUCCESSFUL whether a
# suite executed 227 tests or none, and a task that is UP-TO-DATE does not
# rewrite its results directory at all. A floor compared against a committed
# count is the only thing that notices. Run this AFTER the test steps, never
# before — it reads the XML they produced.
step 6 "executed test counts"
python3 scripts/check-test-runs.py || fail "a test source set ran fewer tests than its recorded floor"

# --- [7] lint ------------------------------------------------------------------
# ktlint and detekt are applied in the root build's `subprojects { }` block, so
# every module has both tasks. They were declared in the version catalogue and
# never applied, which is why ci.yml's `lint` job could only ever fail with
# "Task not found" — a gate that reports success forever because it cannot run
# is the class of defect Part A of check-gate-wiring.py now prevents.
step 7 "ktlint + detekt (all modules)"
./gradlew ktlintCheck detekt --quiet || fail "lint reported violations"
echo -e "${GREEN}lint passed${NC}"

# --- [8] gates are reachable and can fail -------------------------------------
# Part A2 asks whether every Gradle task a gate names actually exists — the direct
# guard on the "Task not found" failure that let ktlintCheck and detekt report
# success forever. Part A3 asks the follow-up a static scan cannot: whether
# `./gradlew check` really schedules the lint tasks, or whether they run only
# where someone remembered to invoke them. It found exactly that: the ktlint
# plugin does not add `ktlintCheck` to `check` the way detekt adds `detekt`, so
# `./gradlew build` was compiling and testing without enforcing style.
#
# Part B proves each script gate can fail, by running its own control against a
# deliberately broken input. It lives here rather than in the static registry
# because Parts A2/A3 need a JVM, and a gate that proves another gate works
# inherits that gate's preconditions and runs at its own time.
step 8 "gates are reachable, in the lifecycle, and can fail"
python3 scripts/check-gate-wiring.py --tasks-exist --can-fail \
    || fail "gate wiring check FAILED — an unreachable gate, or one that cannot fail"

echo
echo -e "${GREEN}=== ALL CHECKS PASSED ===${NC}"
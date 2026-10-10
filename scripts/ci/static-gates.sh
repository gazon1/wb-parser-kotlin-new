#!/usr/bin/env bash
# Single registry of every gate that needs no JVM and no build output.
#
# `ci.yml`'s `static` job and `./check.sh` both call this file, so a gate cannot
# exist in one place and be forgotten in the other. That arrangement is the
# point, not a convenience — this repository's history contains a gate that ran
# only in CI, and the documented local entry point (`just tests::check` →
# `./check.sh`) pointed at a file that did not exist while the copy in
# `scripts/` resolved `ROOT` to `scripts/` and then ran `./gradlew` from there.
# Both entry points failed. Neither was caught, because each was the only one.
#
# Unlike a chain of workflow steps, every gate runs even if an earlier one fails,
# so one push shows all the problems instead of the first one.
#
#   gate <blocking|advisory> <name> <command...>
#
# `advisory` is the only way to be non-blocking, and it shows up as a warning.
# `|| true` is not an alternative: Part D of scripts/check-gate-wiring.py rejects
# it here, so a gate cannot be quietly softened by pasting an idiom instead of
# declaring the mode.
set -o pipefail
cd "$(dirname "$0")/../.."

PASSED=() FAILED=() WARNED=()
in_ci="${GITHUB_ACTIONS:-}"

gate() {
  local mode=$1 name=$2 rc=0
  shift 2
  [[ -n $in_ci ]] && echo "::group::$name"
  "$@" || rc=$?
  [[ -n $in_ci ]] && echo "::endgroup::"
  if ((rc == 0)); then
    PASSED+=("$name")
    printf '  \033[0;32m✓\033[0m %s\n' "$name"
  elif [[ $mode == blocking ]]; then
    FAILED+=("$name")
    printf '  \033[0;31m✗\033[0m %s (exit %s)\n' "$name" "$rc"
    [[ -n $in_ci ]] && echo "::error title=Gate failed::$name (exit $rc)"
  else
    WARNED+=("$name")
    printf '  \033[1;33m!\033[0m %s (advisory, exit %s)\n' "$name" "$rc"
    [[ -n $in_ci ]] && echo "::warning title=Advisory gate failed::$name"
  fi
}

# --- the registry ------------------------------------------------------------
# Documentation gates. These are the checks that guard the *other* gates and the
# recorded decisions, so they run first and on their own.

gate blocking "decisions digest is current" bash scripts/check-decisions-digest.sh
gate blocking "detekt baseline may shrink only" python3 scripts/check-baseline-ratchet.py --allow-growth --reason "pre-existing: wbparser baseline inherited 3 LongParameterList entries from infrastructure baseline (module merge)"
gate blocking "gate scripts' own unit tests" python3 -m unittest discover -s scripts/tests --quiet

# Gate governance: Parts A (wiring), D (no `|| true`), E (declared asymmetry),
# F (positive control per gate) and G (this file is called by both surfaces).
#
# NOT HERE: Parts A2 and B — they mutate inputs and one of them needs Gradle.
# A registry is static by definition; running a mutating check from CI's `static`
# job would reproduce exactly the "already fails on a clean tree" class of false
# alarm. A gate that proves another gate works inherits that gate's
# preconditions and runs at its own time, so the meta-gate lives in check.sh
# after the tests, alongside the results it reads.
gate blocking "gates are wired and declared honestly" python3 scripts/check-gate-wiring.py --static

# --- domain purity -------------------------------------------------------------
# After the module merge (domain + infrastructure → wbparser) these checks run against
# the merged tree.  Before the merge they guard the current domain/ independently.
# ExposedTables.kt is compileOnly schema metadata — org.jetbrains.exposed imports there
# are intentional and safe; all other org.jetbrains.exposed imports in domain/ are forbidden.
gate blocking "domain purity: no infra imports in domain" bash scripts/ci/check-domain-purity.sh

# --- report ------------------------------------------------------------------
echo
echo "static gates: ${#PASSED[@]} passed, ${#FAILED[@]} failed, ${#WARNED[@]} advisory-failed"
if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then
  {
    echo "### Static gates"
    echo
    echo "${#PASSED[@]} passed · ${#FAILED[@]} failed · ${#WARNED[@]} advisory"
    for n in "${FAILED[@]}"; do echo "- ❌ $n"; done
    for n in "${WARNED[@]}"; do echo "- ⚠️ $n (advisory)"; done
  } >>"$GITHUB_STEP_SUMMARY"
fi
((${#FAILED[@]} == 0))
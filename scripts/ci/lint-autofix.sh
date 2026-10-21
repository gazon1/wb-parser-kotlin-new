#!/usr/bin/env bash
# lint-autofix.sh — apply the linters' own corrections to the working tree.
#
# Two linters, two mechanisms:
#
#   detekt  → the `detekt` task with `--auto-correct`; detekt rewrites the
#             rules it knows how to fix (formatting, spacing, unused imports).
#   ktlint  → the `ktlintFormat` task; ktlint rewrites what its own rules say.
#
# One Gradle invocation per module, and one task per invocation. This is not
# tidiness: `--auto-correct` is an option of the `detekt` *task*, and a task
# option given on the command line reaches only the last matching task of the
# invocation. `./gradlew detekt --auto-correct` therefore corrects one module
# and silently skips the rest — which is indistinguishable from "the flag does
# nothing" unless the files are read afterwards. The rule this script follows
# is the one recorded in
# docs/decisions/2026-10-20-ci-security-gates-and-lint-autofix.md.
#
# What this script does NOT do:
#
#   * It does not decide whether the tree is acceptable. `ktlintCheck` and
#     `detekt` remain the gates; this only rewrites what can be rewritten.
#     A violation that no rule can correct stays in the tree, and the lint
#     gate still fails on it.
#   * It does not touch the detekt baseline. The baseline is debt, not
#     formatting; a correction that removes a baseline entry is a separate,
#     reviewed change (scripts/check-baseline-ratchet.py).
#
# Exit code:
#
#   0 — the linters ran. Findings that no rule can correct are reported, not
#       raised: the caller decides what to do with them.
#   1 — a linter could not run at all (the task is missing, the build failed
#       before the task, Gradle could not start). A corrector that silently
#       corrected nothing must not report success.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

GRADLE=(--no-daemon --console=plain)

# The modules are discovered, not listed. A module added to settings.gradle.kts
# must be corrected without anyone remembering to edit this file — the same
# rule the migrations follow ("Always discover test schemas from the
# classpath; Never enumerate migration files").
mapfile -t MODULES < <(
    sed -n 's/^[[:space:]]*include("\([^"]*\)").*/\1/p' settings.gradle.kts
)

if [[ ${#MODULES[@]} -eq 0 ]]; then
    echo "no modules found in settings.gradle.kts — nothing to correct" >&2
    exit 1
fi

# run_task <module> <task> [extra gradle args...]
#
# Runs one task and classifies the outcome by whether the task actually
# executed, not by the exit code. detekt exits non-zero when it has findings —
# which is exactly what auto-correct is for, and treating it as a failure of
# the corrector would make this script report "the corrector is broken" every
# time it corrected something.
run_task() {
    local module=$1 task=$2
    shift 2
    local log rc=0
    log="$(mktemp)"
    ./gradlew "${GRADLE[@]}" ":$module:$task" "$@" >"$log" 2>&1 || rc=$?

    if ! grep -qE "^> Task :$module:$task( |$)" "$log"; then
        echo "  ✗ :$module:$task did not run (exit $rc)" >&2
        sed -n '1,40p' "$log" >&2
        rm -f "$log"
        return 1
    fi

    if ((rc != 0)); then
        echo "  · :$module:$task reported findings (exit $rc) — what it could correct is in the tree"
        # The rules that could not be corrected are worth naming: they are the
        # ones a human has to look at.
        grep -E "^\s+[A-Za-z][A-Za-z0-9]* - " "$log" | sed 's/^/      /' | head -20 || true
    else
        echo "  · :$module:$task — nothing to correct"
    fi
    rm -f "$log"
    return 0
}

echo "lint auto-fix: ${#MODULES[@]} module(s) — ${MODULES[*]}"

# The tree is snapshotted before and after, so the report names the files the
# linters changed — not the ones the developer had already changed before the
# script ran. A report that mixes the two is a report nobody can read.
#
# `cksum` rather than `sha1sum`: the first is in POSIX and is on macOS, the
# second is a GNU tool and is not. `sort` without a key: `comm` compares whole
# lines and insists that its input is sorted by exactly that comparison.
snapshot() {
    git ls-files -m -o --exclude-standard -z 2>/dev/null \
        | xargs -0 -r cksum 2>/dev/null \
        | sort
}

before="$(mktemp)"
after="$(mktemp)"
trap 'rm -f "$before" "$after"' EXIT
snapshot >"$before"

for module in "${MODULES[@]}"; do
    # detekt first: ktlint rewrites formatting, and detekt's rules read the
    # result. The other order works too, but this one has the property that
    # the second linter sees what the first one produced.
    run_task "$module" detekt --auto-correct
    run_task "$module" ktlintFormat
done

snapshot >"$after"

# The lines that are in "after" and not in "before": a file that appeared, or
# a file whose content changed. `cksum` prints "checksum size path".
corrected="$(comm -13 "$before" "$after" | cut -d' ' -f3- | sort)"

if [[ -z "$corrected" ]]; then
    echo "lint auto-fix: the linters changed nothing — the tree is as it was"
else
    mapfile -t corrected_files <<<"$corrected"
    echo
    echo "lint auto-fix: the linters corrected ${#corrected_files[@]} file(s):"
    for file in "${corrected_files[@]}"; do
        [[ -n "$file" ]] || continue
        echo "  · $file"
    done
    echo
    git --no-pager diff --stat -- "${corrected_files[@]}"
fi

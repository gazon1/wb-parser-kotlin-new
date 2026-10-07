#!/usr/bin/env bash
# check-decisions-digest.sh — DIGEST.md must match what the generator produces.
#
# ## The defect this catches
#
# `refresh-decisions-digest.sh` was a *refresh* command: it regenerated
# `docs/decisions/DIGEST.md` in place and ci.yml ran it. Regenerating is not
# checking. CI therefore rewrote a tracked file on every run and compared
# nothing, so a digest that no longer matched its decisions shipped green — and
# the agent that reads DIGEST.md before every non-trivial task would have been
# reading stale rules while every check passed.
#
# The fix is to make the comparison explicit: snapshot, regenerate, diff, and
# restore. The working tree is left exactly as it was found whether the check
# passes or fails, so this is safe to run from any gate.
#
# Why not simply commit the regenerated file from CI and let git diff catch it:
# because a CI job that mutates the checkout cannot fail on the mutation, and a
# leftover dirty tree is invisible to the next reader.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DIGEST="docs/decisions/DIGEST.md"

# --- positive control --------------------------------------------------------
# Proves this gate can fail. A check whose passing is indistinguishable from a
# check that cannot run is the defect class this whole change is about, so the
# control appends a line to the digest and requires a non-zero exit.
if [[ "${1:-}" == "--self-test" ]]; then
    [[ -f "$DIGEST" ]] || { echo "self-test: $DIGEST missing" >&2; exit 1; }
    BACKUP="$(mktemp)"
    cp "$DIGEST" "$BACKUP"
    # `trap` rather than a bare cleanup call, so an interrupt leaves the file
    # alone: a control that can corrupt the working tree is not a control.
    trap 'cp "$BACKUP" "$DIGEST"; rm -f "$BACKUP"' EXIT

    printf '\n<!-- positive control: this line must be detected as drift -->\n' >>"$DIGEST"

    if bash "$ROOT/scripts/check-decisions-digest.sh" >/dev/null 2>&1; then
        echo "self-test FAILED: a stale digest was accepted" >&2
        exit 1
    fi
    echo "✅ self-test: a stale digest is rejected"
    exit 0
fi

BACKUP="$(mktemp)"
RESTORE=0

cleanup() {
    # Restore unconditionally: a gate that leaves the tree dirty teaches people
    # to distrust `git status`, which is a tool they need for other reasons.
    if [[ $RESTORE -eq 1 ]]; then
        cp "$BACKUP" "$DIGEST"
    fi
    rm -f "$BACKUP"
}
trap cleanup EXIT

if [[ ! -f "$DIGEST" ]]; then
    echo "❌ $DIGEST does not exist — run ./scripts/refresh-decisions-digest.sh and commit it" >&2
    exit 1
fi

cp "$DIGEST" "$BACKUP"
RESTORE=1

./scripts/refresh-decisions-digest.sh >/dev/null

if ! diff -q "$BACKUP" "$DIGEST" >/dev/null; then
    echo "❌ $DIGEST is out of date relative to docs/decisions/*.md" >&2
    echo "   It is generated; the checked-in copy is stale." >&2
    echo "   Run: ./scripts/refresh-decisions-digest.sh && git add $DIGEST" >&2
    echo "" >&2
    diff -u "$BACKUP" "$DIGEST" | head -40 >&2
    exit 1
fi

echo "✅ $DIGEST matches docs/decisions/*.md"
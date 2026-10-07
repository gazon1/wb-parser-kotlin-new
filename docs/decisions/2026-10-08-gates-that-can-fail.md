---
title: "A gate that cannot fail is worse than no gate — and that is a shared rule"
date: 2026-10-08
tags: [testing, ci, architecture]
---

## Context

Four defects in one day, across two repositories, were all the same defect wearing
different clothes:

1. `wb-shop/tests/global-setup.ts` listed the crawler's migrations as a two-element
   array. When V3 lands, the sync gate turns green, the dev database applies it, and
   the suite keeps asserting against a schema production will never have.
2. `tests/.../SavedItemsPersistencePostgresTest` did the same thing with the crawler's
   *own* migrations. Here it is worse: `V2__catalog_columns.sql` drops a unique
   constraint and adds four columns that `upsertSavedItems` writes by name, so the
   whole write path is coupled to the migration set.
3. `wb-shop/tests/architecture.test.ts` scanned raw source, prose included, and
   rejected the word "copy" in a JSDoc as SQL `COPY`. The available fix was to reword
   the comment — which is the wrong fix for a gate, because it passes by changing what
   it inspects.
4. `npm test` in the storefront exits 0 and prints a summary whether 145 tests ran or
   one file was excluded by a pattern. The file that needs a container is the first a
   broken pattern loses.

In every case the build was green and had verified less than it claimed. None was
caught by the gate that should have caught it — that is the part that matters. Each
was found by writing the thing and then trying to break it on purpose.

Meanwhile the crawler's own gate-wiring checker had already been hardened to skip
comments for the same underlying reason (Part A), and the rule it embodies was written
down only inside a Python docstring.

## Idea

- **(a)** Fix each occurrence and leave the pattern unwritten. Cheapest, and it is what
  both repositories were doing.
- **(b)** State the rule once in the crawler's ADR log, where `DIGEST.md` surfaces it to
  every agent session, and have the storefront point at it.
- **(c)** Duplicate the rule in both repositories' documentation so neither depends on
  the other being read.

## Decision

The rule is written down once, in this ADR, with Critical markers so it lands in the
generated Critical section of `DIGEST.md`.

- **Always** state what a gate inspects, and prove it fires on the input it claims to
  catch — by breaking that input deliberately and observing the failure.
- **Never** make a gate pass by editing the thing it inspects. A rewording that silences
  a check is a regression wearing a fix's clothes.
- **Never** let a check degrade to silence when it cannot run. Missing Docker, a missing
  classpath entry, an unreadable directory: raise. A gate that reports "all clear"
  having verified nothing is worse than no gate, because it is trusted.
- **Always** prefer a discovered input over a declared one — files, migrations, test
  suites — wherever a list can silently fall behind reality.
- **Always** read comments out before scanning source for code. A comment is prose
  about code, not code.

### The rule crosses repositories, deliberately asymmetrically

The storefront has no ADR log and this rule does not become a second copy there. It is
a pointer plus a short statement of the two places where the pair knowingly differs:

- **Per-test-file test floors (storefront) vs per-module floors (crawler).** A total lets
  growth elsewhere mask one suite's disappearance, and the suite in question is the only
  one that talks to a database. The crawler's exposure — "someone adds a test and the
  task stops selecting it" — does not justify the weaker check, so it keeps the weaker
  one and records why.
- **Storefront migrations are copies under `cmp`; crawler migrations are the source.**
  The storefront therefore needs `check-dev-schema-sync.sh` and the crawler does not.
  Editing a comment inside `dev/db/*.sql` fails the storefront's gate and is forbidden.

The asymmetry is recorded here and in the storefront README rather than left to whoever
reads next, because the failure mode of this rule is precisely a rule that one side
quietly stopped following.

## Rationale

(b) over (c). A rule duplicated in two documents is a rule that will be updated in one
of them, and the copy nobody reads is the one that quietly licenses the next defect. One
statement with two named exceptions is easier to keep true than two statements that
drift apart — and the exceptions are the part that carries information anyway, since
"the rule" without them is too coarse to apply.

The "prove it can fail" clause is the load-bearing one. Every defect above was found by
a deliberate break, and every one of them was green the moment before. A gate whose
failure path has never been observed is a hypothesis about a gate.

## Consequences

- **Always** break the input on purpose when adding a gate, and record the observed
  failure in the commit message. `check-gate-wiring.py --self-test` and the controls in
  `scripts/tests/test_gate_scripts.py` are this rule applied to the gates themselves.
- **Always** discover inputs rather than list them. `Migrations.discover()` in the
  crawler's `tests/` and the migration glob in `tests/global-setup.ts` are the same
  decision, made twice, days apart.
- **Never** treat a zero exit as evidence. Ask what was inspected; if the answer is "an
  empty list" or "nothing there to find", the check has not run.
- The executed-test floors are raised in the same commit that adds tests, in both
  repositories. Verified by control in each: deleting the new suite leaves the test task
  reporting success while the floor gate exits 1.
- Crawler: `tests` floor 31 → 44. Storefront: per-file floors in
  `config/test-runs-floor.tsv`, 10 files, 145 tests.
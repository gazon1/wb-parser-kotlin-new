---
title: "The rules live where they are enforced: the mechanical ones in a check, the rest in a review-time file"
date: 2026-10-23
tags: [process, gates, standards, tooling]
---

## Context

The repository's rules were fifteen numbered prohibitions in `AGENTS.md`, under the
heading "❌ Что НЕ делать". Two things were wrong with that.

**They cost context on every turn and enforced nothing.** `AGENTS.md` is loaded into
every agent's context in every session, whether the session touches the rule or not.
A rule that is only prose is followed by whoever reads the document — and the
document is read by the agent that writes the code, while the code is usually
reviewed by somebody else.

**Most of them were mechanical.** A force-unwrap in production code, a
`delay(-1L)` sentinel, a Spring import in a module that must not know Spring, a
`CHANGELOG.md`, a `fixDependencies` in CI, a pinned JDK path: each of these is a
pattern. A pattern can be checked, and a checked rule is impossible to forget.

The rules were not all mechanical. "A side effect must go through the registry",
"an error caught must reach a `StageFailure` or a log", "a save stage needs a
retry loop" — these are judgements about the code, and no linter can decide them.

## Alternatives considered

1. **Leave the rules in `AGENTS.md`.** Rejected: they stay prose, and they keep
   costing context on every turn of every session.
2. **Mechanise everything, including the judgements.** Rejected: a rule a machine
   cannot decide becomes a false positive or a suppression, and both teach the
   reader to ignore the rule.
3. **Split them by kind (chosen).**

## Decision

The rules that a machine can decide are decided by a machine.
`scripts/ci/check-repo-rules.py` checks the force-unwrap, the negative delay, the
Spring import outside the application, the purity of the browser module, the
`CHANGELOG.md`, the `fixDependencies` in CI and the pinned JDK. It has a
`--self-test` that proves it can fail on a deliberately broken tree.

The rules that a machine cannot decide live in `CODING_STANDARDS.md`, which is read
during review. `AGENTS.md` keeps what an agent needs on every turn — the
boundaries, the decision workflow, the gates — and points at both.

Three detekt rules were enabled with the same argument, and one was not:

- `SwallowedException` — a caught exception that is never used is an error nobody
  can see. The repository's own rule said so, and the rule was only prose.
- `UseCheckOrError` — `check`/`require` say what the code expects; a bare `throw
  IllegalStateException` says only that something was wrong.
- `TooGenericExceptionThrown` — a `throw RuntimeException(...)` tells the reader
  nothing about what to catch.
- `TooGenericExceptionCaught` — **not** enabled. It was measured first: 19 sites,
  against 12 for `UseCheckOrError` and 9 for `SwallowedException`. Nineteen broad
  catches in a codebase whose policy is "catch broadly, log honestly" is not a
  signal, it is a number. A rule whose findings are mostly deliberate is a rule
  that gets switched off.

The rules that were measured and left off are recorded here, because "we looked
and decided" is worth more than silence.

## Rationale

A rule's cost is not only the finding it produces — it is also what the reader
learns about the rule. A rule with nineteen findings in a codebase that catches
broadly on purpose teaches the reader to dismiss it; the same rule with one
finding is read carefully.

The measurement is what made the difference. The candidate rules were enabled one
after another and counted, and the counts decided the outcome. That is the same
argument the repository already accepts for the coverage floors: a number that was
measured is worth more than a number that was guessed.

The split also respects who does what. The agent that writes the code is under
context pressure and cannot hold fifteen rules in its head while it is exploring;
the reviewer has the diff and no exploration to do, and is the one who can judge
"is this the right decision".

## Consequences

- **Always** put a rule where it is enforced: a pattern in a check, a judgement in
  `CODING_STANDARDS.md`, and neither in `AGENTS.md`.
- **Never** duplicate a rule between the check and the standards file. The copy
  goes stale silently, and the stale copy is the one that is read.
- **Always** measure a rule before enabling it, and record the rules that were
  measured and left off.
- **Never** write a rule into `AGENTS.md` that only a reviewer could enforce.

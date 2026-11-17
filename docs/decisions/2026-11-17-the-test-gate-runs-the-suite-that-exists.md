---
title: "The scripts' test gate runs pytest, so the gate guards the suite that exists"
date: 2026-11-17
tags: [gates, python]
---

## Context

The static registry has one gate for the scripts' own tests:

```
gate blocking "gate scripts' own unit tests" uv run python -m unittest discover -s scripts/tests --quiet
```

`unittest discover` collects `unittest.TestCase` classes. It does not collect
plain test functions. The suite in `scripts/tests/` has both shapes, and the
plain functions are the newer half: the whole Pane contract — the tests of the
`Pane` base class, the `StatsPane` pilot, and now the five migrated tabs —
is written as pytest-style functions.

The gate therefore ran 281 of the 307 tests. Nineteen tests in
`scripts/tests/test_wbparser.py` alone — every test of the contract the TUI
migration extends — had never been executed by the pipeline that is supposed to
prove the client works. `scripts/tests/test_wbparser.py` said so itself, in the
docstring of its `unittest` cases: "a pytest-style function here would never be
executed by the pipeline that is supposed to prove the client works." The
sentence was written as a warning about the style; the style it warned about was
the one the new tests were written in.

Nothing failed. The gate was green, and it was green about a suite that did not
exist.

## Idea

- Write every new test as a `unittest.TestCase`, so it runs under the gate as
  it is. The suite stays mixed, and the half that does not run stays unrun.
- Run both: keep the `unittest discover` gate and add a pytest gate beside it.
  Two runners over one directory, and the slower one is a strict subset of the
  other.
- Make the gate run pytest. It collects both shapes, so the whole suite runs
  under one command, and the suite stops being divided into the part the
  pipeline sees and the part it does not.

## Decision

The gate runs pytest:

```
gate blocking "gate scripts' own unit tests" uv run pytest scripts/tests --quiet
```

pytest collects `unittest.TestCase` classes and plain test functions alike, so
the gate now runs the whole suite — 323 tests after this change, against the 285
the old runner would have collected (it never saw the 38 plain functions). The
`unittest`-style cases stay `unittest` cases: they need `mock.patch` around the
transport, and pytest collects them without a change.

`scripts/check-gate-wiring.py` names the gate's runner in two places, because
the test-suite gate is exempt from the positive-control requirement: its
control is the suite itself, a failing test fails the gate. The exemption now
matches both runners through `TEST_SUITE_MARKERS`, so the wiring check keeps
describing the gate that is actually registered.

The wiring check recognises that gate by what the command *runs*, not by what
the command says. `runs_the_test_suite()` reads the command as the chain it is —
`uv run <program>`, `uvx <program>`, `python -m <module>` — and requires the
program behind it to be pytest (or `unittest discover`) with the suite as its
argument. A command that merely names the suite — `echo pytest scripts/tests`,
or `uv run echo pytest scripts/tests` — is a finding, because a gate like that
would be "proved" while testing nothing at all.

The registry is read with `shlex.split`, not `str.split`. The registry is a
shell registry: `bash -c 'test ! -e CHANGELOG.md'` and
`--reason "pre-existing: …"` carry spaces inside quotes, and `str.split` tears
them into fragments the shell would never have produced. The checker has to see
the command the runner sees, or it is checking a different command than the one
that runs.

The gate cannot pass vacuously. `unittest discover` exits 0 when it finds
nothing, which is the trap `scripts/tests/test_gate_scripts.py` was written to
catch; pytest exits 5 in that case, so an empty suite fails the gate on its
own. The test stays, guarding the runner a developer reaches for by hand.

The comments that named the gate's command were updated with it. They made a
second claim — that the tested modules import nothing outside the standard
library, so they run on any Python — and that claim is the one that matters; it
is unchanged.

## Rationale

A gate that runs part of the suite is a gate that says less than it appears to.
The nineteen tests that were skipped covered the exact contract the TUI
migration extends, so the tests written for this issue would have joined them:
present in the repository, green in the local run, and never executed by the
pipeline. Writing them in the other style would have made them run — and left
the same hole for the next person, in the same file, with the same docstring
warning them away from it.

Two runners would not have been better. The `unittest discover` run is a strict
subset of the pytest run, so the second gate would cost time and prove nothing
new; and the split would stay, with two numbers to compare and one of them
always smaller.

The exemption from the positive-control requirement is right, and it was right
before: a failing test fails this gate, which is the control. The wiring check
only needs to know which command the registry names, so it is told both.

## Consequences

- **Always** write a test in `scripts/tests/` in the shape the gate's runner
  collects, and check that it is collected: a test the pipeline never executes
  guards nothing, whatever the local run says.
- **Never** treat a green gate as proof that a suite ran. Ask what the runner
  collected; if the answer is "the classes and not the functions", the gate is
  guarding a subset.
- **Never** rely on `unittest discover` to find the suite: it does not collect
  plain test functions, and it exits 0 when it finds nothing.
- **Never** let a gate be recognised by a phrase in its command. A gate is
  recognised by what it runs.
- **Always** keep `scripts/tests/test_gate_scripts.py` green. It is the guard
  against a suite that reports success having verified nothing.

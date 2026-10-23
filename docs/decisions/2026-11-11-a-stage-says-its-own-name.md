---
title: "A stage says its own name"
date: 2026-11-11
tags: [architecture, refactoring, observability]
---

## Context

The crawl pipeline has five stages: download, parse, filter, enrich, save. Every one of
them was built through the secondary constructor of `Stage`, which fills in
`StageInfo("unnamed")` — so no stage knew what it was called.

The only place the names lived was the code that *called* the stages. The page processor
wrote `"filter"` and `"enrich"` into its log lines, and `"Save stage"` into three more.
Those strings were a second copy of a decision: rename a stage in the factory and the
log keeps calling it by the old name, and nobody notices until an operator reads a
report about a stage that does not exist.

The machinery for saying otherwise was already there and unused: `StageInfo` and the
`stage(...)` DSL. The catalog's own stage used the DSL — `wb-catalog-download` — and
the crawl's five stages did not.

## Idea

1. **Pass the names into the processor as parameters.** Rejected: that moves the strings
   one step further from the stages. The stage's name is a property of the stage.
2. **Keep the strings in the processor and make sure they are right.** Rejected: that is
   what the code does now, and it is a copy that drifts.
3. **Give every stage its name, and let the report read the name off the stage.**
   Chosen.

## Decision

**A stage carries its name, and the code that reports what it did reads the name off the
stage.**

- The crawl pipeline's five stages are built through the `stage(...)` DSL, each with a
  name and a description: `download`, `parse`, `filter`, `enrich`, `save`.
- The page processor asks the stage what it is called: `pipeline.filter.info.name`,
  `pipeline.enrich.info.name`, `pipeline.save.info.name`. There is no list of names
  anywhere else.
- The stage that answers with something a pure stage cannot do is named in the report
  by its own name. A stage called `rules` is reported as "rules stage failed" — the
  report does not know what the stage does, only what it is called.
- The unnamed constructor is **internal**. A stage outside the domain module has to
  say what it is; the module's own tests may build a nameless one, because nobody
  reads a test stage's name.
- The DSL is no longer `inline`. An inlined function's body is executed in the caller,
  so the DSL's own lines were never covered by any run — the report showed a function
  that the production code calls as uncovered. It is not inlined any more, and it is
  covered.

## Rationale

The name is the one thing a report needs and cannot invent: the operator reads "the
filter stage failed" and knows which decision to look at, but "a stage failed" is not a
sentence anybody can act on. Putting the name on the stage is the smallest change that
makes the report say the truth — and it makes the report say the truth *by
construction*, because there is no second copy to fall out of date.

The DSL was already the way to say this. It was used once (the catalog's stage) and
not in the crawl pipeline, where five stages were built by hand. Using it in both
places makes the difference between them a matter of taste rather than of convention.

Why the unnamed constructor became `internal` rather than being removed: 159 test
stages are built with it, and a test stage's name is not read by anybody. Making it
internal keeps the tests short and closes the door on production code outside the
module.

Why the DSL lost `inline`: it bought nothing here. The stage is built once per target,
so the closure it allocates is noise, and what it cost was a report that said a
production function was never executed.

## Consequences

- **Always** put the name on the thing the report talks about. A report that repeats a
  name from a list is a copy that drifts.
- **Never** keep a stage's name in the code that calls it. The caller knows what it
  asked for; the stage knows what it is.
- **Always** name the stages of a pipeline. A stage without a name cannot be reported.
- **Never** read a report that names a stage as a list of strings: the list is where
  the lie lives.
- **Never** inline a function whose coverage the report has to show. An inlined body
  is executed in the caller, and the report cannot see it.

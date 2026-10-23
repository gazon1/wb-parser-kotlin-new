---
title: "The adapters that speak to Playwright are checked against a real Chrome"
date: 2026-10-23
tags: [architecture, coverage, ci]
---

## Context

`PlaywrightCdpSession` and `PlaywrightCdpConnector` are the seam itself. Playwright's
`Browser`, `Page` and `Playwright` are final classes, so no double can stand in for
them: the decisions above the seam are made against `CdpSession`/`CdpConnector`, and
everything below it was uncovered.

That hole was recorded, not forgotten. The ADR «пробелы закрыты или записаны»
(2026-10-22) wrote it down with the reason and with what had to change to close it:
*«покрытие потребовало бы настоящего Chrome в CI»*. A decision that is not acted on
is a hypothesis that looks like a fact, and this one had been sitting in the record
since the day it was made.

The decisions below the seam are small and real:

- the connector connects to a CDP endpoint, and an endpoint that does not answer is
  an error — not an empty session that looks like a browser that is up;
- the session opens a page in the browser context, and the title of that page is the
  title the session reports;
- a session without a page does not invent a title;
- a closed page is reported as closed.

## Idea

1. **Leave the hole recorded.** Rejected: the record says what has to change, and
   nothing changed.
2. **Write a double for Playwright's final classes.** Rejected: they cannot be
   implemented, and a proxy over them is what the seam already removed.
3. **Give the CI a real Chrome and check the adapters against it.** Chosen.

## Decision

**The CI installs the browser Playwright drives, and the browser module's tests drive
it over CDP.**

- `./gradlew playwrightInstall` installs the browser (the Playwright CLI, through the
  module's own test classpath). It is a setup step, like the JDK toolchain the build
  downloads for itself.
- The CI runs it before the tests, in the jobs that run the tests: `build` and
  `coverage`. The second one matters as much as the first — the coverage report is the
  union of the modules' reports, and the browser module's own report is part of it.
- `PlaywrightCdpSessionTest` starts a real Chrome with a DevTools port, connects to it
  exactly the way the crawler does, and pins the four decisions above.

**The test fails loudly when the browser is missing.** A test that skips when the
resource is absent is a hole that reopens silently, and the rule «никогда не считать
«прогон не запустился» успехом» applies to a test as much as to a job. The failure
message says what to run.

The asymmetry is declared: the CI installs the browser on every run because a runner
is a new machine every time; a developer installs it once. It is the same asymmetry as
the JDK toolchain, and it lives in `config/gates/asymmetries.tsv` where the gate that
found it can see it.

## Rationale

The adapters are the boundary between the crawler's own code and a third-party
library. A test that stops at the seam proves the crawler's decisions and says nothing
about the boundary itself — and the boundary is where a version of Playwright changes
what "a session without a page" means.

A real Chrome is not expensive here: the browser is downloaded once per machine, and
the test starts one Chrome per test case, which takes about a second each.

## Consequences

- **Always** check the adapters below a seam against the real thing, when the seam
  exists because the real thing cannot be faked.
- **Never** let a test skip silently because a resource is missing: it must fail with
  the command that fixes it.
- **Always** declare an asymmetry between the local loop and CI, with the reason.
- **MUST** install the browser before the tests in every CI job that runs them —
  including the one that only measures coverage.

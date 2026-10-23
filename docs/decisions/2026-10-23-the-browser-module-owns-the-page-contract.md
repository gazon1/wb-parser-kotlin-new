---
title: "The browser module owns the page contract, and Playwright stops at its edge"
date: 2026-10-23
tags: [browser, architecture, dependencies, playwright]
---

## Context

The browser stack became a module of its own: `browser` is a `java-library`, and
`app → wbparser → browser` is the only direction. The boundary was declared — the
build refused the reverse edge — but it was not *enforced*, because the module's
public API named Playwright's types:

- `BrowserAutomation.connect(cdpUrl): Page`
- `BorrowedPage.page: Page`
- `BrowserProfileState.getOrCreatePage(): Page`

A public type that names a type of a third-party library is a hole in the boundary:
every consumer of the module needed Playwright on its compile classpath to call it.
The dependency-analysis plugin said so plainly — `wbparser` had to declare
`implementation(libs.playwright)` because `BrowserDownloader` and `EgressProbe` name
`Page` in their own sources — and the crawler module was carrying a browser
library it did not own.

What did the crawler actually need from a page? Four calls: navigate, wait, read
the cookies, evaluate a script. Everything else about Playwright's hundred-method
`Page` was inherited by accident.

## Alternatives considered

1. **Leave it: the module boundary is about where the code lives, not about types.**
   Rejected: the boundary was a packaging convention, and the plugin was already
   reporting the consequence — a Playwright upgrade stayed an event of the crawler
   module, and the crawler's tests had to know a browser library to fake a page.
2. **Let the crawler use Playwright's API directly and keep the dependency.** That is
   the same as (1) with an extra step.
3. **The browser module owns a page contract (chosen).** `:browser` declares what a
   page is; the crawler says what it needs.

## Decision

`:browser` owns `BrowserPage` — four calls, all of which the crawler makes:

- `navigate(url, timeoutMs)` — navigate and wait for the load event;
- `waitFor(ms)` — wait, for the anti-bot challenge;
- `cookies(url)` — the cookies of the context for a URL;
- `evaluate(script, argument)` — run a script and return what it answers.

`BrowserAutomation` and `BorrowedPage` speak `BrowserPage`. Playwright's `Page`
exists in exactly one place, `PlaywrightBrowserPage`, and the failures are
translated rather than swallowed: a Playwright error becomes a `BrowserPageException`
with the same message, because the caller tells an anti-bot answer from a dead
browser by what the message says.

Playwright is `implementation` in `:browser` and is declared nowhere else. The
crawler module does not mention it — not even in a catch clause.

The test fixture follows: `FakePlaywrightPage` was a JDK proxy over Playwright's
`Page`, because Playwright's `Page` has around a hundred methods. It is now
`FakeBrowserPage`, a plain class over four methods — and the last Playwright type
the crawler's tests had to know about is gone.

## Rationale

The surface was the point. Every call the crawler does not make is a call the
boundary does not have to carry, and a contract of four methods is one a fake can
implement honestly: the old proxy had to refuse every method it did not know, and
the refusal was a trap for any new call.

The message of a failure was worth preserving rather than rewriting: the downloader
tells an anti-bot refusal from a dead browser by the words in the message, and a
translated message would throw that away.

The name of the fixture changed with the contract. A fake called
`FakePlaywrightPage` that no longer touches Playwright would be a name that lies
about what it stands for — and the ADR that recorded the fixtures had to be
corrected too, because a record that is not true is worse than no record.

## Consequences

- **Never** let a public type of a module name a type the module hides. The
  boundary is enforced by the compiler, not by a convention.
- **Always** translate the failures of the hidden library into the module's own
  type, keeping the message: the caller decides what the failure means.
- **Always** keep the surface of a contract to what the consumer actually calls.
- **Never** let a consumer of a module declare the module's hidden dependency.
  DAGP reports it, and the report is right.

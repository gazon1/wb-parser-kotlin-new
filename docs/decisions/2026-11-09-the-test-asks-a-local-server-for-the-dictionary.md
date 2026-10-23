---
title: "The test asks a local server for the dictionary, not WB's CDN"
date: 2026-11-09
tags: [testing, coverage, architecture]
---

## Context

`WbSubjectBootstrap` downloads WB's product-subject dictionary at startup. It was the
largest uncovered block left in the application module, and it could not be tested
because the address was a `const val` in the companion object: there was no way to point
the code at anything but WB's CDN.

That is the wrong shape for two reasons at once. A test that depends on WB's CDN is a
test that fails when WB is down, and it does not exercise the shapes that actually
break the boot — an error answer, an empty answer, a gzipped body, a body that is not a
dictionary at all, a table that cannot be written. Those are the answers the boot has to
survive, and none of them can be arranged against a CDN that answers the same way every
time.

The class was also the last unclosed hole of the crawler's dictionary path: 15
uncovered lines, and the one place where the application talks to the outside world
before it crawls at all.

## Idea

1. **Test against the real CDN.** Rejected: the test would depend on a third party's
   availability, and it could only ever see the answer WB happens to give that day.
2. **Mock the HTTP client.** Rejected: the JDK's `HttpClient` is final, and the code
   builds it inline — so the mock would have to be a mock of the code's own shape, and
   the shape would be the thing under test.
3. **A seam for the address, and a local server that speaks HTTP.** Chosen.

## Decision

**The address of the dictionary is a seam, and the test serves the dictionary itself.**

`WbSubjectBootstrap.subjectsUrl` is an internal seam, defaulted to WB's published file —
a seam, not a knob, in the same shape as `AdsPowerPreflightRunner.profileCacheDir` and
`BrowserPoolJanitor.clock`. The test points it at a local `HttpServer` on a real
socket, and tells that server what to answer.

Six tests now cover the whole class:

- the dictionary is downloaded and written — and only the entries that can be read are
  written: a blank name, an id that is not a number, an entry without an id and an
  entry that is not an object are all skipped;
- a gzipped body is decoded, because the CDN serves the file gzipped and may or may not
  have decoded it;
- an empty dictionary is not written;
- an error answer does not stop the boot;
- a body that is not a dictionary does not stop the boot — WB changes the file's shape
  from time to time;
- a dictionary that cannot be written does not stop the boot.

## Rationale

The dictionary is a file on a CDN: what matters about it is that it is HTTP with a
body, a status and an encoding. A local server speaks exactly that, and it can be told
to answer with the wrong thing — which is the whole point, because the boot's contract
is that no answer from the dictionary is fatal.

The class's own comment says what the failure means: the products are stored without
category names. A boot that throws on a missing dictionary is a deployment that does
not start, and the crawl does not happen at all.

Why a seam and not a constructor parameter: the class is a Spring `@Component`, and a
constructor parameter of type `String` is a bean the container has to resolve. A seam
that only a test changes does not change the deployment.

## Consequences

- **Always** give a path that fetches from the outside a seam for its address, and
  check it against a local server. The address is not the decision; the answers are.
- **Never** let a test depend on a third party's CDN. It fails when the third party is
  down, and it cannot arrange the answers that break the code.
- **Always** test a boot path with the answers it must survive, not only the happy one.
  The happy answer is the one that arrives by itself; the others are the ones that
  decide whether a deployment starts.

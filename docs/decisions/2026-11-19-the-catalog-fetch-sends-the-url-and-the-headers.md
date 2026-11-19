---
title: "The catalog fetch sends the URL and the headers, and nothing else"
date: 2026-11-19
tags: [crawler, browser, wildberries]
---

## Context

The crawler asks WB's internal catalog API from inside the browser page. The request was
assembled by passing one JSON map to the page script: the real headers, plus `__apiUrl`,
`__catalogId` and `__cookies`. The script forwarded every key except `__cookies` as an HTTP
header, so the internal metadata was sent to WB as request headers, and the cookie jar was
built into a `Cookie` header by hand.

The header list itself (`WbCatalogApi.buildWbHeaders`) mixed two different kinds of value:
headers the browser lets page JavaScript set (`deviceid`, `x-requested-with`) and headers it
does not (`cookie`, `user-agent`, `referer`, the `sec-ch-ua*` hints). The Fetch standard
forbids the second group, and the browser drops such a value without an error — the request
carried a hand-written `Chrome/143` user agent that never left the page, while the browser
sent its own.

Nothing in the test suite could see this: the tests asserted the configured values, not the
headers that reached WB.

## Idea

1. Keep the metadata out of the header map by giving the URL a field of its own.
2. Let the browser own the cookies (`credentials: 'include'` on a same-origin request) and
   stop building a `Cookie` header by hand.
3. Keep only the headers that WB's own storefront request carries and the browser lets us set.
4. Verify all of it against a real browser instead of against the constants.

## Decision

The fetch script takes `{url, headers}` and sends exactly those. The manual cookie jar and
the metadata keys are gone; `WbCatalogApi.buildWbHeaders()` returns only `deviceid`,
`x-requested-with`, `x-spa-version` and `x-userid`.

The verification is a read-only chrome-use site adapter in
`devtools/chrome-use-sites/wildberries/`, kept outside the crawler runtime.

## Rationale

Observed on 2026-11-19 in a real Chrome session (chrome-use network capture):

- `__apiUrl` and `__catalogId` did reach WB as request headers.
- `cookie`, `user-agent`, `referer` and `sec-ch-ua*` were dropped by the browser; the wire
  carried the browser's own user agent and referer.
- A catalog request without `deviceid` answers 403; with it, 200.

So the old list was mostly theatre, and one real header (`deviceid`) was hiding in it. A
`{url, headers}` argument removes the whole class of leak instead of filtering it: a key
that is not under `headers` cannot become a header.

## Consequences

- **Always** keep internal metadata out of the request header map — a URL is a field of the
  fetch argument, not a header.
- **Never** set `cookie`, `user-agent`, `referer` or `sec-*` from page JavaScript: the
  browser owns them and drops ours without an error.
- **Always** verify a browser-fetch assumption against a real browser session, not against
  the configured constants.
- The session cookies travel with `credentials: 'include'`; the browser context's cookie
  jar is no longer read by the downloader.

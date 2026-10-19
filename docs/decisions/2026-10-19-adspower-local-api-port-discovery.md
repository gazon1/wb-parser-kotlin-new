---
title: "AdsPower Local API — runtime port discovery instead of a hardcoded port"
date: 2026-10-19
tags: [browser, infrastructure, debugging]
---

## Context

`BrowserPool` talked to the AdsPower Local API at a hardcoded `http://127.0.0.1:50325`.
That port is not stable across AdsPower distributions:

- the classic desktop app serves **50325**;
- the `adspower-browser` CLI serves **50725**.

On a machine running the CLI, the hardcoded base URL never answered. `checkActiveV2()` and
`startProfileV2()` both returned null, every profile was reported as "unusable", and the
crawl finished with zero pages — with no indication that the cause was the *port* rather than
the browser.

A second, independent problem: the AdsPower Local API and the Chrome DevTools ports it hands
back (`ws://127.0.0.1:<port>/...`) are reachable **only through the host's loopback**. The
`127.0.0.1` inside a container's bridge network is the container's own. A ws URL returned by
the API therefore points at the wrong loopback, and no amount of correct configuration fixes
it — the container has to share the host's network namespace.

## Idea

1. Resolve the API base URL at runtime instead of pinning it: try the known distribution
   ports, then scan.
2. Make the debug stack use `network_mode: host` so the ws URLs the API returns actually
   connect.
3. Keep the explicit override as a *preference* rather than a pin, so a stale value falls
   through to discovery instead of pinning the crawler to a dead port.

Alternatives considered and rejected:

- **`socat` port-forward (50325 → 50725)** — works, but adds a host daemon that has to be
  kept alive and reconfigured whenever AdsPower moves again. It treats the symptom.
- **Hardcode 50725** — same brittleness, one distribution ahead of the current failure.
- **Read the port from AdsPower's config file** — the port is not written down anywhere; it
  is a property of the running process.

## Decision

Introduced `AdsPowerApiResolver` (`wbparser/.../infra/http/AdsPowerApiResolver.kt`).

Resolution order:

1. `ADSPOWER_API_BASE` — an explicit override, tried first. It is a *preference*, not a pin:
   if it does not answer, the resolver logs a warning and continues to discovery.
2. The known distribution ports (50325, 50725), probed in order.
3. Every listening port above the ephemeral range, probed for the AdsPower response signature.

A port "answers" when `<base>/api/v2/browser-profile/active` replies with the AdsPower JSON
envelope (`code` + `msg`). AdsPower returns `code: 0` even for a profile id that does not
exist, so the probe needs neither a real profile nor an API key, and the signature is
specific enough not to match an unrelated HTTP server on the same port.

`RealAdsPowerApi` now resolves the base URL **lazily** through a provider function, caches
the result, and drops the cache as soon as a call against it fails — so a runtime that
restarts on a different port is re-discovered on the next borrow rather than retried
forever against a dead endpoint.

`AdsPowerConfig.apiBase` became nullable and is read from `ADSPOWER_API_BASE`; `null` means
"discover it".

`deploy/docker-compose.debug.yml` puts the app on `network_mode: host`, and the AdsPower
Local API port is deliberately **not** pinned in the compose file — the resolver finds it.

## Rationale

- The failure mode this fixes is silent and misattributed: "no pages crawled" looked like a
  parser bug, an anti-bot block, or a WB-side change, and none of those were true.
- Probing is cheap. A refused loopback connection returns immediately, so scanning a few
  dozen listening ports costs milliseconds. The scan only runs when neither the override nor
  the known ports answer.
- The probe needs no secrets, so discovery works identically with and without
  `ADSPOWER_API_KEY` set.
- Injection points (`knownPorts`, `candidatePorts`, `probe`) keep the resolver unit-testable
  without a real AdsPower runtime — a developer box usually *has* one running, so a test that
  touched the network would be both flaky and environment-dependent.

## Consequences

- **Always** reach AdsPower through the host's loopback. In Docker that means
  `network_mode: host` (or an equivalent host-network setup); a bridge network silently
  breaks every profile.
- **Never** treat a failing `checkActiveV2()` as "the browser is gone". It may mean the port
  moved. `RealAdsPowerApi` now re-resolves on failure for exactly this reason.
- `ADSPOWER_API_BASE` overrides discovery when set, but a stale value is not fatal — the
  resolver logs a warning and falls back to scanning.
- The debug stack must set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. We drive AdsPower's own
  Chrome over CDP, so Playwright's bundled Chromium is never launched; without the flag
  `Playwright.create()` downloads 161 MB on every browser borrow and re-downloads it on every
  retry. `deploy/docker-compose.adspower.yml` already had this.
- Profile state under `~/.adspowerCli/source/cache/<id>_<hash>/` must be owned by the user
  running the AdsPower runtime. A container that mounted `~/.adspowerCli` and ran as root
  leaves root-owned profile directories and Chrome kernels behind; the host runtime then
  cannot write `Preferences` and the browser refuses to start with `EACCES`. See
  `docs/decisions/2026-10-19-local-debug-stack-repairs.md`.

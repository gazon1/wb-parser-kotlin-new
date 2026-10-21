---
title: "AdsPower — API-first browser resolution, one client, and a pre-flight check"
date: 2026-10-19
tags: [browser, infrastructure, reliability]
---

## Context

Three defects in how the crawler found and used AdsPower browsers, all of them
discovered the hard way — a crawl that fetched nothing, with no cause attached.

1. **The Local API was consulted only when `ADSPOWER_API_KEY` was set.** The key
   was never sent anywhere; the Local API listens on loopback and answers
   unauthenticated. The key only decided *whether to try*. Without it the crawler
   fell back to a blind DevTools port scan, which assigns browsers to profiles by
   index — and can hand two profiles the same browser. The code's own comment
   explains why that matters: five profiles on one browser is one anti-bot session
   wearing five names.

2. **The response envelope was parsed in three places.** `checkActiveV2`,
   `startProfileV2` and the port probe each opened a connection and parsed
   `{code, msg, data}` on their own. The probe in particular could disagree with
   the two that mattered.

3. **A broken profile looked like an unreachable browser.** The real causes —
   a profile directory owned by another user, a `SingletonLock` left by a killed
   browser — surfaced as "no DevTools port is reachable", hours away from their
   actual cause.

## Idea

- Ask the Local API first, always. Fall back to the port scan only when the API
  itself is down.
- Parse the envelope once.
- Name the actual problem before the crawl starts.

## Decision

**The API is asked first, unconditionally.** `BrowserPool.ProfileState.resolveCdpUrl()`
calls `checkActiveV2` then `startProfileV2` with no key gate. When the API
answers but the profile is unavailable, that is an error to report — the pool
raises a message naming the profile and the likely cause — not a reason to
attach the profile to an arbitrary browser. The port scan now runs only when
`AdsPowerApi.isAvailable()` says the API is down.

`AdsPowerApi` grew that one method, so the distinction between "the API is down"
and "the API is up but this profile is not" is part of the contract rather than
an inference from a null return.

**One client.** `AdsPowerApiClient` owns the HTTP call and the envelope parsing;
`RealAdsPowerApi` and the port probe both go through it. `AdsPowerEnvelope` makes
the `{code, msg, data}` shape a named type instead of three hand-rolled
extractions.

**One port resolution, cached for the process.** `AdsPowerApiResolver.resolve()`
caches the discovered base URL; `invalidate()` drops it, and a failed API call
drops it too, so a runtime that restarts on a different port is re-discovered
rather than retried forever. The pure resolution lives in `resolveUncached()` and
is unit-tested with injected seams — a test that touched the network would be
flaky on a developer machine, which usually *has* AdsPower running.

**A pre-flight check at startup.** `AdsPowerPreflight` looks for each configured
profile's directory under `~/.adspowerCli/source/cache/`, checks that it is
writable, checks that the profile's `Default/Preferences` file can be written,
and checks for a `SingletonLock` that no live browser holds. The
findings are logged as errors with the fix in the message. It never fails the
boot: AdsPower may legitimately start after the app.

**The `Preferences` file is checked, not only its directory.** This is a
separate case because it is invisible from the outside: a `Preferences` file
that belongs to root sits inside a directory that is writable, so the browser
starts, the pages are fetched and the crawl saves its items — while AdsPower
cannot read or write the profile's settings and says so in a window inside the
browser. The crawl's own output says nothing about it. The check reports it as
`PROFILE_PREFERENCES_UNWRITABLE`, with the fix in the message.

**A file owned by another user is fixed by renaming it, not by `chown`.** The
directory around it is owned by the current user, so the file can be renamed
and the browser writes a new one as the current user. That fixes the state
without a root shell, which matters because the machine's `sudo` is
password-protected and the operator is not always in front of it.

**The check is about ownership, not about whether this process can write.**
The container reads the host's AdsPower directory: it is neither the owner of
those files nor, with a read-only mount, able to write them, and neither fact
says anything about the profile's health. What says something is a file that
belongs to a different user than the directory around it — that profile was
created by another session, and AdsPower, running as the current user, cannot
write its settings. So the profile directory is compared with the cache it sits
in, and `Default/Preferences` with the `Default` directory around it.

**A renamed backup is not a profile.** The fix above leaves the old file next
to the new one, and the old directory next to the profile directory. Matching a
profile by its id prefix alone would pick the backup — it sorts first — and
then report a healthy profile as broken. Paths carrying a `.rootowned` marker
are skipped.

**The container is told where AdsPower keeps its state.** `ADSPOWER_HOME` points
at the host's `~/.adspowerCli`, mounted read-only into the container. Without
it the check looks at `/root/.adspowerCli`, finds nothing, and reports every
profile as missing.

## Rationale

- The Local API is the only source that knows *which* browser belongs to *which*
  profile. The port scan throws that away, and the difference shows up as
  anti-bot damage rather than as an error.
- A stale key is a plausible thing for someone to have set, and it silently
  disabled the correct path. The key no longer gates anything.
- Preflight costs a handful of filesystem calls at startup and turns a
  half-day of log-reading into a single line that says what to fix.

## Consequences

- **Always** consult the Local API before falling back to the port scan, and
  **never** fall back while the API is reachable.
- `ADSPOWER_API_KEY` no longer gates the API call. It is read from the
  environment and reported by the pre-flight check, but the Local API on
  loopback is unauthenticated and the key is not sent anywhere.
- `AdsPowerApi` has three methods now. A new implementation — the test fake
  included — must answer `isAvailable()` honestly, or the pool will fall back to
  the scan when it should have reported a broken profile.
- The profile directory is found by prefix (`<profileId>_<hash>`); the hash is
  AdsPower's own and is not reconstructed.
- The pre-flight check reads `~/.adspowerCli` by default; `ADSPOWER_HOME`
  overrides it for installations that keep their state elsewhere.
- **Never** run AdsPower under `sudo`. A profile created by such a run keeps a
  `Preferences` file and a cache that belong to root, and AdsPower then reports
  that it could not read the settings. The fix is a rename of the file, and the
  pre-flight check says so by name.
- A profile directory that was never materialised and a profile whose
  `Preferences` belong to another user are different problems with different
  fixes, and the pre-flight check names them apart.

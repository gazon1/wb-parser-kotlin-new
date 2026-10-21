---
title: "Local debug stack — paths, ports and the runtime classpath the app actually needs"
date: 2026-10-19
tags: [docker, debugging, infrastructure]
---

## Context

`just app::build bootJar && docker compose -f deploy/docker-compose.debug.yml up` did not
work. Each failure had a different cause and none of them named the real problem, so the
stack looked broken in four independent ways at once:

1. `Error: Unable to access jarfile app.jar`
2. `Error: Invalid or corrupt jarfile app.jar`
3. `failed to solve: failed to read dockerfile: open Dockerfile.debug: no such file or directory`
4. `ADMIN_API_KEY environment variable is not set` followed by a crash on startup

and then, once the container was up, `java.lang.ClassNotFoundException: java.text.ListFormat`
and a 161 MB Chromium download on every browser borrow.

## Idea

The debug stack had drifted from what the code and the image actually need. Rather than
patching each symptom, align the four things that had to agree:

- where the JAR is, relative to the compose file;
- where the Dockerfile is, relative to the compose file;
- which credentials and ports the app expects;
- which JVM the image provides.

## Decision

**The root `docker-compose.yml` is an `include` of the debug stack.** There were two
local stacks — the root one and `deploy/docker-compose.debug.yml` — and the root
copy could never work: it built the debug image (which does NOT contain a jar; it
expects the host-built one mounted in) without mounting one, it never passed
`ADMIN_API_KEY`, and it used the bridge network, so AdsPower was unreachable from
it. The two copies also disagreed about the database credentials and fought over
port 5433 with different users. The root file now contains nothing but
`include: [deploy/docker-compose.debug.yml]`, so `docker compose up` from the
project root and `docker compose -f deploy/docker-compose.debug.yml up` run the
same stack. The debug file sets an explicit project `name:` and explicit
`container_name`/`volume name`, because the project name is otherwise derived
from the directory of whichever compose file was used — and a project-derived
volume name would silently hand one invocation an empty database.

**`just app::run-docker` builds the jar first.** The recipe ran
`docker compose up --build`, and `--build` builds the *image*, not the jar. The
documented one-liner therefore produced a container that died with "Unable to
access jarfile app.jar".

**Compose paths are relative to the compose file, not to the CWD.** Docker Compose resolves
both `build.context` and relative volume paths against the directory holding the compose
file. `deploy/docker-compose.debug.yml` therefore uses `context: ..` and mounts
`../app/build/libs/app.jar`. Both errors above (1, 2, 3) came from this single
misunderstanding: `./app/...` became `deploy/app/...`, and `context: .` became `deploy/`.

**The jar is mounted as a file, not a directory.** `app/build/libs:/app/app.jar` mounts a
directory over the jar's path and produces "Invalid or corrupt jarfile" for a perfectly good
build. This was already documented in `Dockerfile.debug`; the path was simply wrong.

**`ADMIN_API_KEY` is passed through.** `AdminApiKeyFilter` reads it from the environment and
fails fast at startup when it is absent — deliberately, so the admin API is never reachable
with a well-known credential. The compose file supplies a dev default; production must not.

**The image runs Java 22, not 21.** `java.text.ListFormat` arrived in Java 22, and Tomcat
10.1.x (Spring Boot 3.4.1) requires it. The `ClassNotFoundException` was caught by Tomcat,
which degraded to a non-APR mode — so it looked like a warning rather than the version
mismatch it was. `Dockerfile.debug` now uses `eclipse-temurin:22-jre`.

**`iproute2` is installed.** The browser port scan runs `ss -tlnp`; without `iproute2` the
scan fails with "Cannot run program ss" and the pool has no way to find a browser.

**`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`.** We drive AdsPower's own Chrome over CDP, so
Playwright's bundled Chromium is never launched — but `Playwright.create()` downloads it
anyway, 161 MB, on every borrow and again on every retry. `deploy/docker-compose.adspower.yml`
already set this; the debug stack did not.

**The app runs on the host network.** AdsPower's Local API and the DevTools ports it returns
are reachable only through the host's loopback. See
`docs/decisions/2026-10-19-adspower-local-api-port-discovery.md`.

**`SERVER_PORT=8082`.** Nextcloud owns `8080` on this host.

## Rationale

- Every one of these is a *contract* between two files that drifted apart — the compose file
  and the code, or the image and the runtime. Fixing the contract fixes the whole class of
  symptom, instead of the one error that happened to surface.
- The `ss`-missing and Chromium-download failures were both invisible in the "obvious" place:
  one only appears as a scan fallback warning, the other as a slow first request that looks
  like a network problem.
- Failing fast on `ADMIN_API_KEY` is a feature, not a bug. The fix is to supply the key, not
  to weaken the check.

## Consequences

- **Always** build the jar before `docker compose up` — the debug image does not build it.
  `just app::run-docker` does it for you.
- **Never** mount a directory over `/app/app.jar`.
- **Never** assume a relative path in a compose file is relative to where you ran the
  command. It is relative to the compose file.
- **Never** add a second local compose file. There is one stack, and it is
  `deploy/docker-compose.debug.yml`; the root file includes it.
- The debug image needs `iproute2` for the browser port scan. Adding a dependency to the
  image is cheaper than the alternative — a browser that cannot be found.

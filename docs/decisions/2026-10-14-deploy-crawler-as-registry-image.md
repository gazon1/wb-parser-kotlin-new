---
title: Deploy the crawler as a registry image, built on the operator's machine
date: 2026-10-14
tags: [deployment, infrastructure, ansible, docker]
---

## Context

The crawler had to be deployed to the VPS at 91.188.212.39, and every manual
attempt failed for reasons that did not have anything to do with the application:

- **The VPS has no outbound network.** HTTPS times out. A `git clone` and a
  `./gradlew bootJar` there cannot work — not slowly, but at all.
- **The SSH link carries about 35 KB/s.** The first delivery of the 252 MB jar
  took roughly ninety minutes.
- **The GHCR token had no `packages:write` scope**, so an image could not be
  pushed from the existing token.
- `gazon1/Caddy-vps` already had a `wbparser` Ansible role, but it could never
  have run: its defaults pointed `git clone` at a non-empty directory, and both
  its clone step and its build step needed the network the host does not have.

So the two candidate shapes were "build on the VPS" and "ship an artifact to
the VPS", and the first is impossible while the second is painful.

## Idea

- Build on the VPS from a git checkout. Cleanest artifact story — nothing to
  transfer — but needs egress and a warm Gradle cache.
- Build locally, `rsync` the jar over SSH. Works, but every deploy pays the
  bandwidth, and 252 MB at 35 KB/s is not a deploy path.
- Build locally into a Docker image, push to GHCR, let the VPS pull it by tag.
  The VPS transfers nothing; the artifact is the thing the deploy names.

## Decision

The crawler ships as a container image built on the machine that has the network
and the source, pushed to GHCR, and pulled by tag on the VPS. `just deploy` in
this repository runs the gates, builds, pushes, and calls
`gazon1/Caddy-vps` with the tag.

The contract is split by ownership rather than by convenience:

- This repository owns the image (`Dockerfile.app`) and the runtime contract
  (`deploy/docker-compose.vps.yml`). How the crawler runs is the crawler's
  business, and a change to it belongs in review here.
- `Caddy-vps` owns the host. It copies that compose file verbatim, writes the
  environment, pulls, starts, verifies, and publishes the route through its
  existing route machinery.

Compose credentials and the image tag live in two different files on the host.
Compose interpolates `${…}` out of `.env`, and `$` in a password would be
expanded there; the credentials go through `env_file`, which passes values
literally.

## Rationale

An image by tag makes the transfer free, the artifact reproducible, and the
rollback a matter of naming the previous tag rather than re-uploading anything.

The split of ownership matters more than the transport. When one playbook owns
the image, the compose file, the systemd unit and the Caddy snippet, there are
four places to look for why the crawler is down and no way to tell which
repository's change caused it.

Both failures that made the first deploys look successful are now structural. A
deploy that only restarts the container and reports success is the exact shape of
the failure we hit: a process that started and died seventy milliseconds later,
with Ansible reporting a green run and the site down. The health gate and the
rollback make that state unreachable.

### Trade-offs accepted

- The GHCR token now needs `packages:write`. It did not have it, and the first
  `just deploy` will fail at the push until it does.
- The VPS must reach `ghcr.io`. Verified by a DNS preflight at the top of the
  role so a blocked network fails in seconds with an explanation instead of
  hanging on the pull.
- One more moving part: the deploy path now spans two repositories, and the
  compose file has to exist on the control node for the role to copy.

## Consequences

- **Always** treat a deploy as unproven until the container reports healthy.
  A restart is not a deploy.
- **Never** put a credential in the `.env` that compose interpolates. Secrets go
  in `app.env` and are passed through `env_file`.
- **Never** give an image tag a `latest` fallback. A deploy that forgot to name a
  tag ships whatever the registry considers newest.
- **Always** pass the compose file's path explicitly to the role
  (`wbparser_compose_src`); it is owned by this repository and there is no safe
  default for where somebody's checkout lives.
- The crawler reaching Wildberries from the VPS is a separate question from
  reaching a registry, and it is still unverified: the outbound restriction has
  only ever been observed against HTTPS in general.

## Prevention

- `wbparser/build.gradle.kts` declares every Exposed artifact as `implementation`.
  `exposed-java-time` was `compileOnly` and therefore absent at runtime, while
  the `tests` module added it back — every test passed and the crawler died on
  its first query. A build-configuration error that no unit test can see is now
  a comment at the declaration.
- `WbParserMain` no longer calls `exitProcess` in server mode, and says why in
  the KDoc.
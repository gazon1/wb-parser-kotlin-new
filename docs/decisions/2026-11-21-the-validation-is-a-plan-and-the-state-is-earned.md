---
title: "A validation is a plan of one-variation experiments, and the state is earned by the evidence"
date: 2026-11-21
tags: [research, tooling]
---

## Context

Issue #312 asks for the step after discovery: a candidate endpoint found in the
browser traffic is not a contract just because it appeared. To know what a
parameter does, what a sort order changes and where a page boundary is, the
request has to be replayed with one thing changed at a time, in the same
browser context, and the result has to be written down next to the evidence
that supports it.

The pieces before it exist. `capture.py` records one named step and refuses the
page actions that could change the site; `plan.py` runs an approved plan and
keeps the state of it; the redactor is the only thing that writes a fixture. The
seller-catalog investigation already has a record, a plan and evidence.

What was missing is the experiment between a candidate and a claim: a replay, a
variation, a comparison, and a rule that decides when the claim is allowed to be
called `validated`.

## Idea

1. An agent improvises the probes and writes the conclusion in prose. Nothing to
   approve, nothing to compare, and a single sample is indistinguishable from a
   repeated one.
2. A validation plan next to the experiment plan, run by a second small script:
   the plan names the candidate, the variations and the expectations, the script
   replays them and writes the observations, the summary and the report.
3. A general API-testing framework with its own DSL and runner. A runtime and a
   dependency for one scenario, in a repository that must keep its runtime clean.

## Decision

A validation is a file: `experiments.yaml` in the investigation directory, next
to `plan.yaml` and `investigation.yaml`, approved by the operator and run by
`scripts/wb-research/validate.py`.

The plan names one candidate from the record, the page whose context the probe
runs in, the base query and the **names** of the headers to send. The candidate
contributes its method, endpoint and query; the plan overrides what it needs.

One experiment changes exactly one thing: one query parameter replaced, one
removed, the credentials mode, or a page reload. Two differences at once prove
nothing about either, so the plan is refused. The first experiment is the
reference and varies nothing.

The value of a header is never in the plan. The plan names it, and the value
comes from a local file outside the repository (`~/.cache/wb-research/<id>/request.json`
by default). A path inside the repository is refused, and a named header with no
value stops the run. A request replayed without the header it was seen with is a
different request, and the tool refuses to pretend otherwise.

The query is the other half of that split: it is part of the plan and part of
the approval, so a value the record redacted has to be written in the plan. A
value the local file supplied would change what the run asks without changing
the fingerprint the operator approved.

Only a `GET` is replayed. The investigation format carries POST candidates —
the antibot service is one — and a POST is not a request a read-only tool may
repeat: the page's `fetch` would send it and the site may act on it.

The approval is a fingerprint of the scope, written into the plan as
`approved_scope` — the page, the endpoint, the query as the plan writes it,
the header names, the credentials mode, the experiments and the pause between
them, plus the approved origins and pages of the capture config. The values of
the headers are not in it: they are the operator's session. A plan whose scope changed
does not run until it is approved again.

The state is earned by the evidence, not chosen by the writer:

- `observed` — one answer, or an expectation that held once;
- `reproduced` — the same answer from independent runs;
- `validated` — a controlled variation repeated, its expectation held every
  time, and the reference itself reproduced;
- `rejected` — a stable answer contradicts the expectation;
- `inconclusive` — a refusal, a failure (a transport error or a 5xx), an
  unreadable answer, disagreeing runs, or a sample of one.

A refusal ends the run: after a `403`, `429` or WB's `498` the remaining probes
are marked "not run" instead of hammering the site, and the run exits `2`. A
failed request exits `1`. A blocked or failed experiment is never reported as a
successful validation.

The run writes three files next to the plan: `validation.yaml` (the
machine-readable summary), `validation-report.md` (the report a human reads) and
`evidence/validation.json` (the sanitized observations a claim points at). The
evidence keeps the request, the status, the media type, the size, the top-level
keys, the counts and the ordered product ids; the summary keeps the order as a
hash; no body, cookie or header value is in either.

The tool is a development tool. It is not part of the mandatory CI, and its own
tests replace the browser with a function that returns the answer the test asks
for.

## Rationale

The plan wins over prose for the same reason `plan.yaml` did: the experiment has
to be inspectable before it runs, and an approval that is a fingerprint of the
plan can bind to the plan instead of to a moment. The one-variation rule is the
smallest thing that makes an experiment mean something: a plan that changes two
parameters cannot say which one the answer belongs to.

The states are the ones the issue names, and the tool computes them from the
observations rather than letting the writer type them. The record format's older
table needed something outside the experiment for `validated`; a repeated
controlled experiment is now one way to earn it, and the table says so.

The states are computed from the observations rather than typed by the writer.
A person who can label a sample `validated` will label it so, and the record is
then worth less than the code that produced it. The rule that `validated` needs
a reproduced reference and a repeated variation is the mechanical form of "do
not infer undocumented semantics from a single sample".

The header values stay out of the plan because the plan is committed and the
values are the operator's session. A placeholder in the plan with a value in the
local file keeps the plan readable and the value out of the repository — the same
split the redactor already makes for the evidence.

Stopping at a refusal is the same rule as `plan.py`'s pause: a site that says no
is the operator's question, not the tool's problem.

## Consequences

- **Always** write the experiments before the run and approve the plan: the
  fingerprint of the scope is what makes the run a decision and not a guess.
- **Always** change one thing per experiment, and keep the first experiment as
  the reference. A plan that changes two things is refused, not interpreted.
- **Always** compute the state from the observations: one sample is `observed`,
  `validated` needs the reference reproduced and the variation repeated, and a
  refusal, a failure or an unreadable answer is `inconclusive`.
- **Never** report a blocked, failed or inconclusive experiment as a validation,
  and **never** turn a refusal into a claim about what the site requires.
- **Always** keep header values out of the plan, the summary, the report and the
  evidence: the plan names the headers, the local file carries the values, and a
  local file inside the repository is refused.
- **Always** stop probing after a refusal and mark the rest "not run": a burst of
  requests against a site that said no is not evidence.
- **Always** compare one variation with the reference on the status, the media
  type, the shape, the counts and the order — a byte count is not evidence of a
  change, and a body is never committed.
- **Always** keep the tool outside the mandatory CI: it needs a real browser and
  a live site, and its tests replace the browser with a function.
- **Always** replay `GET` only, and treat a `5xx` as a failed request: a POST may
  change the site, and a server error is not an answer that can validate a claim.
- **Never** let `validate.py` click, type, bypass a CAPTCHA, a rate limit or an
  access control, or reach an origin the capture config does not approve.

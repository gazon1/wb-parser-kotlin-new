---
title: "The probe asks three questions, and each is answered by its own class"
date: 2026-10-23
tags: [architecture, coverage]
---

## Context

`EgressProbe` was 438 lines — the largest file in the repository — and it held three
questions that have nothing to do with each other:

- **the static check**: do the groups leave where the configuration says they leave?
  The proxy of a browser lives in AdsPower, and the configuration only declares which
  profiles are *expected* to share an address;
- **the live check**: what address does the world see? One browser per group asks an
  echo service;
- **the metrics**: what does the operator see about it? The gauges of the isolation
  state and the counter of the probes that failed.

It also held the isolation check (are two groups at one address?) and the parsing of
the echo answer. The rule «крупные классы делятся по решению, которое они держат»
had been written down and applied to `BrowserPool` and `Pipeline`, and it had not been
applied here — not because the rule was wrong, but because nobody had measured the
class against it. 438 lines is the size that made it visible.

The rule has a second half that is easy to miss: a class that holds several decisions
is hard to change safely, because a change to one decision touches the others. The
static check reads the AdsPower profile list; the live check opens a browser; the
metrics register a gauge. A change to the gauge's name must not be able to break the
way the address is read.

## Idea

1. **Split the class by size** — cut it into three roughly equal files. Rejected: the
   parts would be equal in size and different in kind, and the next change would
   still have to know all three.
2. **Leave it as it is and cover it with tests.** Rejected: the coverage of a class
   that holds three decisions says that *something* was executed; it does not say
   which decision was checked.
3. **Split it by the decisions it holds.** Chosen.

## Decision

`EgressProbe` is now the orchestration: it asks the questions and assembles the answer.

- `EgressStaticCheck` — the static check. It reads the AdsPower profile list, compares
  the declared proxy with the configured one, and answers whether the groups are the
  isolation the configuration promised.
- `EgressAddressProbe` — the live check. It opens one browser per group, asks the echo
  services, and answers what address the world sees.
- `EgressIsolationCheck` — the isolation check. Two groups seen at one address are one
  source, and the operator has to be told which groups.
- `EgressProbeMetrics` — the gauges and the counters. The gauge is registered once per
  group and reads the current state; that bookkeeping is a decision of its own.

The probe's own vocabulary moved into files of their own: `EgressGroupProbe`,
`EgressReport`, `EgressProbeConfig`.

The behaviour did not change. The same questions are asked, in the same order, with the
same answers — the split moves the decisions apart, it does not decide anything new.

## Rationale

The three questions have three different costs and three different failure modes: one
HTTP call and no browser, one browser per group and a third-party service, and a
registry that outlives the probe. A class that holds all three can only be tested
through all three, and a change to one of them has to understand the other two.

The metrics became a collaborator for a second reason: the live check needs to count
the probes that failed, and the count belongs to the metrics. Letting the address
probe reach into the registry would have kept the coupling the split was meant to
remove.

The types moved to files of their own because the package already works that way —
every type of `infra/http` has its own file — and a file that holds the vocabulary of
a decision holds that decision's neighbours too.

## Consequences

- **Always** split a class by the decisions it holds, not by its size.
- **Never** keep a metric registry inside the class that produces the measurement:
  the measurement and its reporting are two decisions.
- **Always** move a type to a file of its own when the package works that way.
- **MUST** keep the behaviour of a split identical: a split that changes what is
  asked, or in what order, is a new decision and needs its own record.

---
title: "A network capture is one named step, and an unapproved API host is an error"
date: 2026-11-19
tags: [research, tooling]
---

## Context

Issue #310 asks for the network events of the Wildberries site to be captured
and correlated with the steps of an experiment. The site answers its catalog
with a handful of XHR calls; everything else it loads is a static asset or an
analytics beacon. A raw HAR of one page load holds hundreds of entries, and
almost none of them is what the investigation is about.

The browser tooling that is already here (chrome-use) records a HAR, but it
does not know what a step is, it does not separate the Wildberries API from the
traffic around it, and the capture it writes carries the cookies of the
session. The format ADR (2026-11-19) says what a committed evidence file may
contain, but nothing produced one.

A filter that is too quiet is the failure mode that matters here: the research
question is "which endpoint answers the catalog", and a filter that silently
drops an unrecognised host turns an answer into a gap that nobody notices.

## Idea

1. Record the whole page load and filter it afterwards, by hand, every time.
2. Teach the browser tooling itself to name steps and separate hosts.
3. A narrow wrapper: it names the step, keeps the approved hosts, reports what
   it dropped and hands the result to the existing redactor.

## Decision

`scripts/wb-research/capture.py` records one named step at a time:

```
capture.py step --investigation <id> --name <step> -- <chrome-use command>
```

It clears the request log, starts a chrome-use HAR recording, runs the page
action, waits `--settle` seconds for the requests to be answered, stops the
recording, filters the entries and exports the result through `redact.py`.

The approved hosts live in one file, `devtools/wb-research/capture-config.json`:
`allowedOrigins` (the Wildberries API hosts), `noiseHosts` (hosts whose traffic
carries no API), `keptResourceTypes` (`Fetch` and `XHR` — a static asset is not
an API call) and `maxBodyFetches`.

An API-shaped request to a host that is neither approved nor marked as noise
is **not** dropped: the capture writes everything it has, names the host and
exits with code 2. The developer then approves the host with `--allow-host` or
adds it to the config — a decision that is visible in the record.

A request that was still in flight when the recording stopped has no status.
It is written as `status: null` with a reason; a zero status is not an HTTP
value.

The response body is read only to learn its shape: the names of the top-level
keys, the size, whether it was JSON. The body itself is never written, and the
shape is carried to the redactor through a `_capture` annotation on the HAR
entry.

## Rationale

The wrapper wins over hand-filtering because the separation is a decision that
has to be reproducible: the same capture, filtered by the same config, gives
the same evidence file, and the diff shows what the config changed rather than
what the operator changed. It wins over changing the browser tooling because
the tooling records what the browser did and the investigation needs a
different question asked of it — a smaller, local wrapper is the smaller
change, and it stays outside the production modules.

Loudness wins over silence because the cost of a false negative is invisible
and the cost of a false positive is one extra line in the output. A dropped
host is counted with a reason, and a host nobody has classified stops the
capture, so the gap is something the developer has to close on purpose.

A shape wins over a body because the question is "what does this endpoint
answer", and the answer to that is the set of field names and the size. The
body of a logged-in session carries personal data; the shape does not.

Null wins over zero because `status: 0` reads as an answer, and a request that
was never answered is evidence of something different.

## Consequences

- **Always** record one named step per capture; a step is the unit an
  investigation is written against.
- **Never** drop an API-shaped request to an unclassified host in silence: fail
  with exit code 2 and name the host.
- **Always** judge what counts as an API call by one definition — the kinds of
  request the config keeps. Two definitions would let a kind of request that
  the config keeps slip past the unapproved-host check as ordinary traffic, and
  the capture would not stop for the host.
- **Always** classify a host as `allowedOrigins` or `noiseHosts` before it can
  be dropped; the config is the only source of that decision.
- **Never** write a response body into a capture; only its shape.
- **Never** write a status for a request that has none: null with a reason.
- **Always** wait for the in-flight requests before stopping a recording
  (`--settle`).
- **Never** keep a top-level key that carries a value: a key is a name the
  service chose, and it can be an address or an account id — it goes through the
  same rules as everything else.
- **Always** take only the known fields of the `_capture` annotation, each with
  the type it is supposed to have: the annotation is an input, not a trusted
  source.
- **Always** validate every field that is copied from a capture: the method, the
  status, the size, the moment, the credentials mode, the header names and the
  flags. A capture is an input, and a field nobody looked at must not be able
  to carry a value into the committed file.
- **Never** round a count that was measured: the status and the size are whole
  numbers, and a number with a fraction in it was never measured. Truncating it
  would put a value in the file that nobody saw.
- **Never** keep a timestamp of a moment that did not happen: the shape of a
  timestamp is not a date — a month of ninety-nine days is not a month — so the
  moment is checked against the calendar as well as against the format.
- **Never** keep the **name** of a header that is an identifier: a name is a
  token of characters, and a token can be a profile's UUID in either spelling,
  an account id or a hash of a session. The fact that the header was there
  stays, the name does not. A name the service chose is made of words, long
  ones included, and it stays as it is.
- **Never** keep the value of a free-text query parameter (`query`, `search`,
  `text`, and the short spellings of the same thing — `q`, `term`, `keyword`,
  `keywords`, `phrase`): what somebody typed is the search history of the
  session, and the query of an investigation belongs in the record of the
  investigation.
- **Never** keep a run of digits in a value whose name says nothing about it —
  the value of a query parameter, the value of an allowlisted header — when it
  is longer than the runs the site asks with: the account the request was made
  from is such a run (`user_id=2285324551794928359`). The site's own constants
  are ten digits at the longest (`dest=1259570991`, `hide_vflags=4294967296`)
  and they stay — they say what the request asked for, and a ten-digit run
  cannot be told apart from them.
- **Never** keep an account id in a path segment that follows a segment naming
  an account, whatever its length: the digit-run rule needs nine digits. The id
  is judged by its shape — digits, a mix of letters and digits, or a separator
  between them — while a plain word stays, because it is the name of a route
  the service chose (`user/check` is an endpoint, not an account).
- **Never** keep a JSON key that is made of digits only: such a key is an
  identifier, and an answer indexed by an account id carries the id in it.
- **Never** keep a JSON key that carries a space: such a key is a phrase, and a
  phrase can be a person's name. The name of a field the service chose is one
  word.
- **Never** keep the value of a query parameter whose name is a credential
  name, whatever separator the service wrote it with: `access_token`,
  `access-token` and `access.token` are the same name, and the value beside it
  can be too short for every length-based rule.
- **Never** keep a query parameter name that carries personal data: a name is
  part of the URL and part of the evidence, but a value that stands on the left
  of the `=` is not a name.
- **Never** keep a query parameter name that is made of digits only: such a name
  is an identifier, the way a key of digits is, and the digit-run rule needs
  nine digits.
- **Never** keep a query parameter name that carries a space: such a name is a
  phrase, and a phrase can be a person's name. The name of a parameter the
  service chose is one word.
- **Never** keep a value that hides behind percent-escapes: the name of a query
  parameter, the value of a query parameter, the value of an allowed header and
  the key of a JSON answer are judged as the server reads them — decoded layer
  by layer, at most three — because the server resolves every layer and an
  address behind two of them (`alice%2540example.com`) is the address all the
  same. A value that survives the rules unchanged keeps the spelling it came
  with.
- **Never** keep a UUID in an operator-chosen name: it is an identifier, and the
  opaque-value rule does not see it — the hyphens break the run of characters.
- **Never** keep a content type beyond its media type and the safe parameters:
  the server writes it, and the same value in a header goes through the rules.
  The value of a safe parameter is a value as well: a charset that carries a
  run of digits is an account id, and it is dropped. The media type is judged
  by its shape as well: a subtype that carries a UUID or an account id is a
  value and not a media type, and a content type without a media type is not
  kept.
- **Never** keep the value of an allowed header as it is: the name says what may
  be shown, not that the value is safe. A user agent that carries an address and
  a server header that carries an opaque value go through the same rules as the
  query values and the notes.
- **Always** take only the known timing phases, and only a number as a duration:
  the timing is an input like any other.
- **Never** write a phase that was not measured: the recording writes `-1` for a
  phase it did not measure, and a negative duration is not a measurement.
- **Never** attribute a response body to a request that cannot be told apart
  from a twin — the same method and the same URL, in the recording or in the
  request list: a mapping that cannot be verified is no mapping.
- **Always** delete the recording of the previous run of a step before the new
  one starts — the raw recording, the filtered file the export reads, the
  report of the previous run and the fixture the repository holds — and
  **never** read a capture the browser failed to save.
- **Never** write the browser's own error text into a capture: it quotes the
  URL and the request. The reason a body was not read is a code (`ambiguous`,
  `browser-error`, `no-body`, `not-read`), and the text of a reason belongs to
  the redactor, which turns the code into the sentence the evidence carries.
- **Never** continue a capture whose request log could not be cleared: the
  bodies are looked up in that log, and the join with the recording is
  positional.
- **Always** check that the fixture of the step that was just recorded is among
  the exported ones: another step of the same investigation is not this one.
- **Always** treat the investigation id as one name, not a path: it becomes a
  directory in the raw cache and a directory in the repository.
- **Never** drive a page action that could change the site: the page action of a
  step is a chrome-use command that reads the page, and an action that is not
  one of them — a click, a typed text, a filled form, a shell — is refused
  before the recording starts. The capture observes, so the cart, the orders
  and the account stay as they were.
- **Never** run a page action of a program that is only called chrome-use: the
  action is the chrome-use command by its own name, and a path to another
  program of that name is refused.
- **Never** open a host the config does not approve: a navigation is a request,
  and a request may change what it is asked to change.
- **Never** open a page the config does not name: an approved host answers a GET
  that adds to the cart as well, so the page is what the config approves. The
  origin of the page is compared — the scheme, the host and the port, because
  the same host on another port is another service — and the path of the page
  is resolved before it is compared: the dot segments are the dot segments
  however they are written, `%2e%2e` is `..`. A page cannot hide behind a
  relative segment, and a named page approves the path it names and the paths
  under it, by path segment.
- **Never** open a URL the browser reads differently from the way it is judged: a
  browser reads a backslash in an http(s) URL as a slash, so
  `https://a.example\@b.example/` names the host `a.example` to the browser and
  `b.example` to the parser here. The URL is refused rather than guessed, and
  the page that is executed is the page that was judged. A URL whose host
  cannot be read at all names no host, and no page of it is approved.
- The tool stays in `scripts/` and `devtools/`: no production module depends on
  it and it adds no runtime dependency.

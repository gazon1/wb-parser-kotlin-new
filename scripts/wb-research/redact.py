#!/usr/bin/env python3
"""Turn a raw browser capture into a sanitized evidence fixture.

A raw capture is local, temporary and sensitive: it holds cookies, profile
identifiers and the response text of a logged-in session. The evidence file
that is committed keeps the *shape* of what was observed — the method, the URL
with its query names, which headers were sent, the status, the content type,
the size — and replaces every value that could identify the session with a
stable placeholder.

Deterministic by construction: the same capture always yields the same
fixture, byte for byte. No timestamps are generated and no randomness is used,
so a fixture can be regenerated and diffed.

Usage:
    redact.py capture.har [capture2.json ...] --out evidence.json
    redact.py capture.har --filter '__internal/u-search' --out evidence.json

Accepted inputs: a Chrome DevTools HAR, the JSON that the chrome-use
`wildberries/catalog-request` site adapter prints, and a probe — the JSON a
`chrome-use eval` fetch reported, which carries the request and the shape of the
answer but no body.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import SplitResult, parse_qsl, quote, unquote, urlencode, urlsplit, urlunsplit

# Request headers whose value may be shown. Everything else keeps its name and
# loses its value: a name says what was sent, a value could identify the
# session or be a credential.
SAFE_REQUEST_HEADERS = {
    "accept",
    "accept-language",
    "content-type",
    "origin",
    "referer",
    "sec-ch-ua",
    "sec-ch-ua-mobile",
    "sec-ch-ua-platform",
    "user-agent",
    "x-requested-with",
    "x-spa-version",
}
# `x-userid` is *not* on the list: it identifies the account the request was made
# from. WB sends `0` for an anonymous session, but the capture cannot be assumed
# to have been anonymous — a digit string is short enough to pass every
# length-based rule, so the name-based rule is the only thing that stops it.

# Response headers worth keeping. The rest are dropped, not redacted: the
# interesting ones are a short list and a longer one is more likely to carry
# something personal.
SAFE_RESPONSE_HEADERS = {
    "content-encoding",
    "content-length",
    "content-type",
    "server",
    "statuscode",
    "x-cache-status",
}

# Query parameters whose value is never evidence.
SENSITIVE_QUERY_PARAMS = {
    "access_token",
    "api_key",
    "api_secret",
    "apikey",
    "app_secret",
    "auth",
    "authorization",
    "client_secret",
    "csrf_token",
    "email",
    "id_token",
    "otp",
    "passwd",
    "password",
    "phone",
    "private_key",
    "pwd",
    "refresh_token",
    "secret",
    "secret_key",
    "session",
    "session_id",
    "session_token",
    "sid",
    "token",
    "wbaas_token",
    "x-api-key",
    "x_wbaas_token",
    "_wbauid",
    # The account the request was made from, in the query as well as in the
    # header: a digit string is short enough to pass the opaque-value rule.
    "userid",
    "user_id",
    "x-userid",
}

# A name that ends the way a credential name ends. The list above cannot hold
# every name a service invents, and the fallback — the opaque-value rule —
# needs 24 characters, so a short token (`access_token=abc123`) would pass it.
# The suffix is what a credential name ends with, whatever the service calls
# the front of it.
CREDENTIAL_NAME_SUFFIX = ("_token", "_secret", "_password", "_key", "_passwd")

# The parts of a credential name. The name is what carries the rule; the value
# beside it can be short, so the value itself cannot be recognized — only the
# name can. A name is a prefix and a core word (`access` + `token`), or a core
# word alone (`token`), or an id of a session or of a user (`session_id`). The
# name has to be the whole name: `author` is not `auth` with a suffix, so the
# core word cannot be followed by more letters.
CREDENTIAL_PREFIX = r"(?:access|refresh|id|auth|api|app|client|private|user|secret|session)"
CREDENTIAL_CORE = r"(?:token|secret|key|password|passwd|cookie|credential|authorization|session)"
CREDENTIAL_NAME = (
    r"(?:(?:" + CREDENTIAL_PREFIX + r")[._-]?)?" + CREDENTIAL_CORE + r"|(?:session|user)[._-]?id|sessionid|userid"
)

# The same name, grouped, so that it can be used inside another pattern.
CREDENTIAL_NAME_PATTERN = "(?:" + CREDENTIAL_NAME + ")"

# A name may carry a prefix of its own: `capture-access_token` is a file name
# whose last part is a credential name.
CREDENTIAL_NAME_PREFIX = r"(?:[A-Za-z0-9]+[._-])*"

# A credential written as an assignment: `access_token=abc123`, `password: abc123`.
CREDENTIAL_ASSIGNMENT = re.compile(
    r"(?i)(?<![\w.-])(" + CREDENTIAL_NAME_PREFIX + CREDENTIAL_NAME_PATTERN + r")(?![\w])"
    r"(\s*[=:]\s*)"
    r"(\"[^\"]*\"|'[^']*'|[^\s,;\"']+)"
)

# A credential written as a name and a value joined by a dash: `access_token-abc123`.
# This is the shape a slug takes, and a slug is what an operator-chosen file name
# becomes. The value is judged separately (see `looks_like_credential`), as the
# value after a space is: a name and an ordinary word after it is a slug.
CREDENTIAL_DASHED = re.compile(
    r"(?i)(?<![\w.-])(" + CREDENTIAL_NAME_PREFIX + CREDENTIAL_NAME_PATTERN + r")(?![\w])"
    r"([_-])([A-Za-z0-9]{4,})(?![A-Za-z0-9])"
)

# A credential written as a name and a value separated by a space: `token abc123`.
# The value is judged separately (see `looks_like_credential`), because a name
# and an ordinary word after it is not a credential.
CREDENTIAL_SPACED = re.compile(
    r"(?i)(?<![\w.-])(" + CREDENTIAL_NAME_PREFIX + CREDENTIAL_NAME_PATTERN + r")(?![\w])"
    r"(\s+)([^\s]{4,})"
)

# An authorization scheme with its credential: `Bearer abc123`.
BEARER_VALUE = re.compile(r"(?i)\b(bearer|basic)(\s+)([A-Za-z0-9._+/=-]{4,})")

# A path segment that names what the next segment is: the segment after it is a
# credential, whatever it looks like. A segment that is itself a credential name
# (`access_token`) says the same thing, and `names_a_credential` asks both
# questions.
CREDENTIAL_PATH_COMPONENTS = {
    "activation",
    "activate",
    "apikey",
    "auth",
    "code",
    "invitation",
    "invite",
    "key",
    "otp",
    "passwd",
    "password",
    "reset",
    "reset-password",
    "secret",
    "session",
    "token",
    "verify",
}

# A segment that is a whole credential name. The name may carry a prefix of its
# own, as it may in an assignment: `oauth.access_token` says the same as
# `access_token`.
CREDENTIAL_SEGMENT = re.compile(r"(?i)" + CREDENTIAL_NAME_PREFIX + CREDENTIAL_NAME)

# A value that looks like an opaque credential: long, no spaces, no URL
# punctuation. This is what catches a profile identifier or a token that the
# name-based rules did not. The dot is inside the character class because a JWT
# is `header.payload.signature` — three base64url parts separated by dots, and
# without the dot it slips through as three short words.
OPAQUE_VALUE = re.compile(r"^[A-Za-z0-9._-]{24,}$")

# The same shape, but inside free text: a note can carry a token in the middle
# of a sentence, where the anchored rule above never sees it.
OPAQUE_TOKEN = re.compile(r"[A-Za-z0-9._-]{24,}")

# A value that is itself a URL. A URL carries its own secrets in its query, so
# it is redacted as a URL and not kept as an opaque string.
URL_VALUE = re.compile(r"^https?://", re.IGNORECASE)

# A URL inside free text. A note is prose: the URL follows ordinary words, so
# the URL is looked for anywhere in the text, not only at its start.
URL_IN_TEXT = re.compile(r"https?://\S+", re.IGNORECASE)

# The kind of a failure, as the exception class names it. The *text* of an
# error is not evidence: it can quote the response, the URL or the request.
ERROR_TYPE = re.compile(r"^[A-Za-z][A-Za-z0-9]*(?:Error|Exception)")

MAX_VALUE_LENGTH = 200

# The name of a capture file and the variant of a probe are operator-chosen
# strings that reach the committed file as they are. They are normalized to a
# slug — no `@`, no `:`, no `/`, no spaces — a name that carries a long run of
# digits (an account id) is dropped rather than kept, and an email address in
# a name is replaced: a slug of it would still be the address, spelled out.
UNSAFE_IDENTIFIER = re.compile(r"[^A-Za-z0-9._-]+")
LONG_DIGIT_RUN = re.compile(r"\d{9,}")
EMAIL_ADDRESS = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")

# A phone number, as people write it: ten or eleven digits in groups, with a
# country code in front. The groups are what make it a phone number — a run of
# ten digits without them is an id (`dest=1259570991`), and a date does not fit
# the groups either.
PHONE_NUMBER = re.compile(r"(?<!\d)(?:\+7[ .-]?|8[ .-])?\(?\d{3}\)?[ .-]\d{3}[ .-](?:\d{2}[ .-]\d{2}|\d{4})(?![\w-])")

# An opaque value inside a name: a token or a JWT. The hyphen is not part of it —
# a name made of hyphenated words is a name, not a token.
OPAQUE_IDENTIFIER = re.compile(r"[A-Za-z0-9._]{24,}")
MAX_IDENTIFIER_LENGTH = 64

# The characters a URL path is written with. A path that had a value replaced
# is put back with the same characters, so that the committed URL still looks
# like the URL that was fetched.
PATH_SAFE = "/:@&=+$,~!*'()-._"

# The numbers a probe measured. Numbers are evidence — a count says what was
# seen, and it cannot carry a name, a token or a sentence.
MEASUREMENT_COUNTS = ("total", "productCount", "productsWithPoints", "productsWithCashback")
MEASUREMENT_VALUES = ("feedbackPointsValues", "cashbackValues")

# The cashback of every product next to its price: the pairs are what show
# the unit of the field. A number is evidence; a name is not.
MEASUREMENT_PRICE_PAIRS = "cashbackPricePairs"
PRICE_PAIR_FIELDS = ("id", "priceKopecks", "salePriceKopecks", "cashback")
PRICE_PAIR_REQUIRED = ("id", "priceKopecks", "cashback")


def placeholder(kind: str) -> str:
    """A stable marker for a value that was removed."""
    return f"<redacted:{kind}>"


def looks_like_credential(value: str) -> bool:
    """A value that looks like a credential rather than like an ordinary word.

    A name and a value separated by a space is a weak shape: `token started` is
    a sentence, `token abc123` is a credential. The value is a credential when
    it carries a digit or a capital that is not only the first letter of an
    ordinary word, or when it is long enough to be one anyway — a long run of
    lower-case letters is a word (`generated`), not a credential.
    """
    if len(value) < 4:
        return False
    if any(c.isdigit() for c in value) or len(value) >= 16:
        return True
    # A capital in the middle, or a value written in capitals, is a credential;
    # a capital at the start alone is an ordinary word (`Wildberries`).
    return any(c.isupper() for c in value[1:]) or value.isupper()


def redact_credentials(text: str, label: str = "credential") -> str:
    """The credentials in a text, whatever the shape of the text is.

    The credential is recognized by the *name* beside it: `access_token=abc123`,
    `password: abc123`, `access_token abc123`, `access_token-abc123`,
    `Bearer abc123`. The value can be short, so no length-based rule can find
    it — only the name can. Every shape of value goes through here: a header
    value, a note, a path segment, a query value, a file name.
    """
    marker = "redacted" if label == "redacted" else placeholder(label)
    text = BEARER_VALUE.sub(lambda m: f"{m.group(1)}{m.group(2)}{marker}", text)
    text = CREDENTIAL_SPACED.sub(
        lambda m: f"{m.group(1)}{m.group(2)}{marker}" if looks_like_credential(m.group(3)) else m.group(0),
        text,
    )
    text = CREDENTIAL_ASSIGNMENT.sub(lambda m: f"{m.group(1)}{m.group(2)}{marker}", text)
    text = CREDENTIAL_DASHED.sub(
        lambda m: f"{m.group(1)}{m.group(2)}{marker}" if looks_like_credential(m.group(3)) else m.group(0),
        text,
    )
    return text


def names_a_credential(segment: str) -> bool:
    """Whether a path segment says that the next segment is a credential.

    A segment that is a credential name (`access_token`, `token`, `session_id`)
    says it, and so does a segment that names what follows it (`reset`, `otp`).
    """
    lowered = segment.lower()
    if lowered in CREDENTIAL_PATH_COMPONENTS:
        return True
    return CREDENTIAL_SEGMENT.fullmatch(segment) is not None


def safe_identifier(value: Any, fallback: str) -> str:
    """An operator-chosen name, reduced to a slug that cannot carry a secret.

    The name of a capture file and the variant of a probe are strings the
    operator typed, and they end up in a committed file. A slug keeps the name
    readable — the name is what tells one capture from another — while a name
    that carries a long run of digits is dropped (that is the shape of an
    account id), and an email address or a phone number in it is replaced: a
    slug of them would still be them, spelled out.
    """
    raw = str(value or "")
    text = redact_credentials(raw, label="redacted")
    text = EMAIL_ADDRESS.sub("email", text)
    text = PHONE_NUMBER.sub("phone", text)
    text = OPAQUE_IDENTIFIER.sub("opaque", text)
    text = UNSAFE_IDENTIFIER.sub("-", text).strip("-")
    if not text or LONG_DIGIT_RUN.search(text):
        return fallback
    return text[:MAX_IDENTIFIER_LENGTH]


def is_number(value: Any) -> bool:
    """A number that is not a boolean — `True` is not a count of one."""
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def redact_measurements(probe: dict[str, Any]) -> dict[str, Any]:
    """The numbers the probe measured, and nothing else.

    The counts are kept as they are; the lists of values are kept only when
    every element of them is a number, so a value that slipped a string into
    the list loses the list instead of the string.
    """
    out: dict[str, Any] = {}
    for name in MEASUREMENT_COUNTS:
        value = probe.get(name)
        if is_number(value):
            out[name] = value
    for name in MEASUREMENT_VALUES:
        values = probe.get(name)
        if isinstance(values, list) and values and all(is_number(v) for v in values):
            # The distinct values, sorted: what the field can be, without the
            # hundred repetitions of the same zero.
            out[name] = sorted(set(values))
    pairs = redact_price_pairs(probe.get(MEASUREMENT_PRICE_PAIRS))
    if pairs:
        out[MEASUREMENT_PRICE_PAIRS] = pairs
    return out


def redact_price_pairs(pairs: Any) -> list[dict[str, Any]]:
    """The cashback of every product next to its price — the unit of the field.

    The pairs are the measurement that shows what unit WB sends the cashback
    in: the number next to the price it belongs to. Only numbers may survive;
    a pair that carries anything else loses the whole list, because a pair is
    a measurement, not a place for a name.
    """
    if not isinstance(pairs, list) or not pairs:
        return []
    out: list[dict[str, Any]] = []
    for pair in pairs:
        if not isinstance(pair, dict):
            return []
        if set(pair) - set(PRICE_PAIR_FIELDS):
            # A field this sanitizer does not know: it may carry anything, so
            # the list of pairs goes rather than the field.
            return []
        if not all(is_number(pair.get(field)) for field in PRICE_PAIR_REQUIRED):
            return []
        kept: dict[str, Any] = {}
        for field in PRICE_PAIR_FIELDS:
            value = pair.get(field)
            if value is None:
                continue
            if not is_number(value):
                return []
            kept[field] = value
        out.append(kept)
    return out


def redact_note(note: str) -> str:
    """A note is free text the operator typed — it goes through the same rules.

    A note is the easiest way to smuggle a value into a committed file: it is
    not a header, not a URL and not a body, so the other rules never see it.
    So a note loses its email addresses and its phone numbers, its long digit
    runs (that is the shape of an account id), its URLs become redacted URLs,
    and an opaque value in it is dropped.
    """
    text = EMAIL_ADDRESS.sub("email", str(note))
    text = PHONE_NUMBER.sub("phone", text)
    text = URL_IN_TEXT.sub(lambda match: redact_url(match.group(0)), text)
    text = redact_credentials(text)
    text = OPAQUE_TOKEN.sub(placeholder("opaque"), text)
    text = LONG_DIGIT_RUN.sub(placeholder("account-id"), text)
    return text[:MAX_VALUE_LENGTH]


def redact_header_value(name: str, value: str, safe: set[str]) -> str:
    """Reduce one header value to what may be committed.

    A header on the safe list may still carry a credential: `Bearer abc123` in
    a user agent is a credential whoever sent it. The name of the header says
    what may be shown; the value still goes through the rules.
    """
    if name.lower() in safe:
        if URL_VALUE.match(value):
            return redact_url(value)
        return redact_credentials(value)[:MAX_VALUE_LENGTH]
    return placeholder(name.lower())


def redact_query_value(name: str, value: str) -> str:
    """Reduce one query parameter value to what may be committed.

    The name of the parameter is not the only thing that can carry a secret: a
    search for a phone number puts the number in an ordinary parameter, so the
    personal values are replaced here as well.
    """
    if name.lower() in SENSITIVE_QUERY_PARAMS:
        return placeholder("query-param")
    if name.lower().endswith(CREDENTIAL_NAME_SUFFIX):
        return placeholder("query-param")
    if URL_VALUE.match(value):
        return redact_url(value)
    if OPAQUE_VALUE.match(value):
        return placeholder("opaque")
    text = redact_credentials(value)
    text = EMAIL_ADDRESS.sub("email", text)
    text = PHONE_NUMBER.sub("phone", text)
    return text[:MAX_VALUE_LENGTH]


def redact_error(value: Any) -> str | None:
    """The kind of a failure, never its text.

    An error message can quote the response body, the URL or the request, so
    only the exception class survives — enough to tell a network failure from a
    parse failure.
    """
    if value is None:
        return None
    text = str(value).strip()
    match = ERROR_TYPE.match(text)
    return match.group(0) if match else placeholder("error")


def redact_url(url: str) -> str:
    """Keep the URL's structure; drop the values that could identify a session.

    The fragment is dropped entirely: it never reaches the server, it is never
    evidence, and it is the part of a URL most likely to carry a token. The
    user information of the authority (`user:password@host`) is dropped for the
    same reason — it is a credential, and it is not evidence either.
    """
    parts = urlsplit(url)
    query = []
    for name, value in parse_qsl(parts.query, keep_blank_values=True):
        query.append((name, redact_query_value(name, value)))
    return urlunsplit((parts.scheme, authority_without_userinfo(parts), redact_path(parts.path), urlencode(query), ""))


def redact_path(path: str) -> str:
    """A URL path, without the values in it that could identify a session.

    The path is part of the URL, and it carries values as well: a reset link
    (`/reset/<token>`), a profile (`/users/<address>`), a phone number. The
    query values are judged by their value already; the path was kept as it
    came, so anything in it reached the committed file as it was.

    The path is looked at decoded: a capture may spell an address as
    `alice%40example.com`, and the plain patterns would not see it. The path
    is put back encoded, and only when a value was actually replaced — an
    ordinary path is kept as it came.
    """
    if not path:
        return path
    decoded = decode_path(path)
    segments = decoded.split("/")
    kept = []
    previous = ""
    for segment in segments:
        text = EMAIL_ADDRESS.sub("email", segment)
        text = PHONE_NUMBER.sub("phone", text)
        text = OPAQUE_TOKEN.sub("opaque", text)
        text = redact_credentials(text, label="credential")
        if names_a_credential(previous) and text == segment and not text.startswith("<redacted:"):
            # The segment after a segment that names a credential is one, even
            # when it is short enough to pass every length-based rule. The value
            # is judged by whether it is still there: a segment that was already
            # replaced carries a marker, not a value. A value that begins with
            # the name of a marker (`opaque123`) is a value, and it is replaced
            # like any other.
            text = "credential"
        kept.append(text)
        previous = segment
    text = "/".join(kept)
    if text == decoded:
        return path
    return quote(text, safe=PATH_SAFE)


def decode_path(path: str) -> str:
    """The path as the server reads it, with the escapes resolved.

    A capture may encode a value once or twice; the server resolves every
    layer, so the sanitizer has to look at the same text.
    """
    text = path
    for _ in range(3):
        decoded = unquote(text)
        if decoded == text:
            break
        text = decoded
    return text


def authority_without_userinfo(parts: SplitResult) -> str:
    """The host and the port of a URL, without the user information.

    `urlsplit(...).netloc` carries `user:password@host` as well, and the
    user information is a credential: it must not reach the repository.
    """
    host = parts.hostname or ""
    if ":" in host and not host.startswith("["):
        # An IPv6 host: it needs its brackets back.
        host = f"[{host}]"
    try:
        port = parts.port
    except ValueError:
        port = None
    return f"{host}:{port}" if port else host


def redact_headers(headers: list[dict[str, Any]], safe: set[str]) -> dict[str, str]:
    """Map header names to their values, keeping only the allowed ones."""
    out: dict[str, str] = {}
    for header in headers:
        name = str(header.get("name", "")).lower()
        if not name:
            continue
        if name in safe:
            out[name] = redact_header_value(name, str(header.get("value", "")), safe)
        elif name not in out:
            out[name] = placeholder(name)
    return dict(sorted(out.items()))


def capture_from_har_entry(entry: dict[str, Any], capture_id: str, source_file: str) -> dict[str, Any]:
    """One HAR entry as one capture."""
    request = entry.get("request", {})
    response = entry.get("response", {})
    content = response.get("content", {}) or {}
    return {
        "id": capture_id,
        "source": "har",
        "sourceFile": safe_identifier(source_file, "capture"),
        "started": entry.get("startedDateTime"),
        "request": {
            "method": request.get("method"),
            "url": redact_url(str(request.get("url", ""))),
            "headers": redact_headers(request.get("headers", []) or [], SAFE_REQUEST_HEADERS),
        },
        "response": {
            "status": response.get("status"),
            "contentType": content.get("mimeType"),
            "bodyBytes": content.get("size"),
            "headers": redact_headers(response.get("headers", []) or [], SAFE_RESPONSE_HEADERS),
        },
        "body": {"included": False, "reason": "a response body may carry personal data"},
    }


def capture_from_probe(probe: dict[str, Any], capture_id: str, source_file: str) -> dict[str, Any]:
    """One page-JavaScript probe as one capture.

    A probe is what a `chrome-use eval` fetch reported: the request it made and
    the shape of the answer. It carries no body — only key names and counts.
    """
    return {
        "id": capture_id,
        "source": "probe",
        "sourceFile": safe_identifier(source_file, "capture"),
        "request": {
            "method": probe.get("method"),
            "url": redact_url(str(probe.get("url", ""))),
            "credentials": probe.get("credentials"),
            "headersSent": sorted(probe.get("headerNames", []) or []),
            "headerValuesCaptured": False,
        },
        "response": {
            "status": probe.get("status"),
            "contentType": probe.get("contentType"),
            "bodyBytes": probe.get("bodyBytes"),
            "isJson": probe.get("isJson"),
            "error": redact_error(probe.get("error")),
        },
        "schema": {
            "topLevelKeys": probe.get("topLevelKeys"),
            "productCount": probe.get("productCount"),
        },
        "measurements": redact_measurements(probe),
        "body": {"included": False, "reason": "a response body may carry personal data"},
    }


def capture_from_adapter(data: dict[str, Any], capture_id: str, source_file: str) -> dict[str, Any]:
    """One chrome-use site adapter result as one capture."""
    result = (data.get("data") or {}).get("result") or {}
    request = result.get("request", {}) or {}
    response = result.get("response", {}) or {}
    shape = response.get("shape", {}) or {}
    return {
        "id": capture_id,
        "source": "adapter",
        "sourceFile": safe_identifier(source_file, "capture"),
        "request": {
            "method": request.get("method"),
            "url": redact_url(str(request.get("url", ""))),
            "credentials": request.get("credentials"),
            "headersSent": sorted(request.get("sentByBrowser", []) or []),
            "headersDroppedByBrowser": sorted(request.get("droppedByBrowser", []) or []),
            "headerValuesCaptured": False,
        },
        "response": {
            "status": response.get("status"),
            "contentType": response.get("contentType"),
            "bodyBytes": shape.get("bodyBytes"),
            "isJson": shape.get("isJson"),
        },
        "schema": {
            "topLevelKeys": shape.get("topLevelKeys"),
            "productCount": shape.get("productCount"),
        },
        "body": {"included": False, "reason": "a response body may carry personal data"},
    }


def sanitize(document: Any, url_filter: str | None = None, stem: str = "capture") -> list[dict[str, Any]]:
    """Turn one parsed capture document into the list of sanitized captures."""
    # The name of the file is the operator's, and it reaches the committed file
    # twice — as the id of the capture and as its source. Both are reduced to
    # a slug here, once, so neither can carry an email or an account id.
    stem = safe_identifier(stem, "capture")
    if isinstance(document, dict) and "log" in document:
        entries = document["log"].get("entries", []) or []
        captures = []
        for index, entry in enumerate(entries, start=1):
            url = str(entry.get("request", {}).get("url", ""))
            if url_filter and url_filter not in url:
                continue
            captures.append(capture_from_har_entry(entry, f"{stem}-{index:03d}", stem))
        return captures
    if isinstance(document, dict) and "probes" in document:
        return [
            capture_from_probe(probe, f"{stem}-{safe_identifier(probe.get('variant'), str(index))}", stem)
            for index, probe in enumerate(document["probes"], start=1)
        ]
    if isinstance(document, dict) and "data" in document:
        return [capture_from_adapter(document, stem, stem)]
    raise ValueError("unrecognised capture: neither a HAR nor a chrome-use adapter result")


def load_document(path: Path) -> Any:
    """Read a capture file.

    `chrome-use eval` prints a line naming the page before the JSON it
    produced, so a capture is not always a JSON document from its first byte.
    """
    text = path.read_text(encoding="utf-8")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        start = text.find("{")
        if start < 0:
            raise
        return json.loads(text[start:])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("captures", nargs="+", help="raw capture files (HAR or chrome-use adapter JSON)")
    parser.add_argument("--out", required=True, help="where to write the sanitized fixture")
    parser.add_argument("--filter", dest="url_filter", help="keep only HAR entries whose URL contains this")
    parser.add_argument("--note", action="append", default=[], help="a note to record in the fixture")
    args = parser.parse_args(argv)

    captures: list[dict[str, Any]] = []
    for path in args.captures:
        found = sanitize(load_document(Path(path)), args.url_filter, Path(path).stem)
        if not found:
            print(f"no capture matched in {path}", file=sys.stderr)
            return 1
        captures.extend(found)

    fixture = {
        "schema": "wb-research/evidence/v1",
        "notes": [redact_note(note) for note in args.note],
        "captures": captures,
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(fixture, indent=2, ensure_ascii=False, sort_keys=False) + "\n", encoding="utf-8")
    print(f"{out}: {len(captures)} capture(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

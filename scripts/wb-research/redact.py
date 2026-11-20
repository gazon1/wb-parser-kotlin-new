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
    # The account the request was made from, under the other names the API
    # spells it with. The value is a digit string, so it is short enough to
    # pass the opaque-value rule.
    "uid",
    "account",
    "accountid",
    "account_id",
}

# A name that ends the way a credential name ends. The list above cannot hold
# every name a service invents, and the fallback — the opaque-value rule —
# needs 24 characters, so a short token (`access_token=abc123`) would pass it.
# The suffix is what a credential name ends with, whatever the service calls
# the front of it.
CREDENTIAL_NAME_SUFFIX = ("_token", "_secret", "_password", "_key", "_passwd")

# Query parameters that carry free text — what somebody typed. The value is
# the search history of the session, and it can be a person's name. It is not
# evidence: the query an investigation asked is written in the record of the
# investigation, on purpose, by the developer.
#
# The names are the ones the capture sources use for the same thing — the
# search box of the site (`query`), the search of the redirect check
# (`search`), and the short spellings another API would use (`q`, `term`).
# A name that is not on the list is judged by its value instead.
FREE_TEXT_QUERY_PARAMS = {
    "keyword",
    "keywords",
    "phrase",
    "q",
    "query",
    "search",
    "term",
    "text",
}

# The parts of a credential name. The name is what carries the rule; the value
# beside it can be short, so the value itself cannot be recognized — only the
# name can. A name is a prefix and a core word (`access` + `token`), or a core
# word alone (`token`), or an id of a session or of a user (`session_id`), or one
# of the short names below. The name has to be the whole name: `author` is not
# `auth` with a suffix, so the core word cannot be followed by more letters.
CREDENTIAL_PREFIX = r"(?:access|refresh|id|auth|api|app|client|private|user|secret|session)"
CREDENTIAL_CORE = r"(?:token|secret|key|password|passwd|cookie|credential|authorization|session)"

# The names that are credentials on their own. The sanitizer already treats them
# as sensitive parameters, and a name that is a credential is one everywhere: a
# note and an allowlisted header are not places where `auth=abc123` is safe.
STANDALONE_CREDENTIAL_NAMES = ("auth", "otp", "sid", "pwd", "_wbauid")

# A credential name that carries a qualifier: `auth_code`, `otpCode`, `auth_code_id`.
# The qualifier alone is not a credential — `code` is a word of the site (`promo_code`
# is a product's code) — so only a name that is already a credential takes one.
# `key_code` is a business term, and `token_type` is a name of a scheme: the
# base is a short name of a credential, or `authorization`, whose code is a secret
# by the name itself.
#
# The qualifier is not one fixed word either. A service calls it `code`, `nonce`,
# `value` or `hash`, and the value behind it is short — the opaque-value rule
# needs 24 characters — so a qualifier the sanitizer does not know is a secret
# that reaches the committed file. A qualifier that is separated from the name
# (`auth_code`, `auth_code_id`) or that has a hump of its own (`authCode`,
# `authCodeId`) is therefore any word.
#
# A qualifier that is glued to the name (`authcode`) is told apart from an
# ordinary word by the word itself: `authcode` is a credential, `sidebar` is a
# word of the site. So there the qualifier is one of the known words, the way the
# name of a credential is recognized by the suffix it ends with.
CREDENTIAL_QUALIFIER_WORD = r"[A-Za-z][A-Za-z0-9]*"
CREDENTIAL_QUALIFIER_GLUED = (
    "code",
    "id",
    "value",
    "hash",
    "nonce",
    "token",
    "secret",
    "key",
    "pin",
    "ref",
    "salt",
    "digits",
    "number",
    "checksum",
    "signature",
)
CREDENTIAL_QUALIFIER = (
    r"(?:[._-]" + CREDENTIAL_QUALIFIER_WORD + r"(?:[._-]" + CREDENTIAL_QUALIFIER_WORD + r")*"
    r"|(?-i:[A-Z][A-Za-z0-9]*)+"
    r"|(?:" + "|".join(CREDENTIAL_QUALIFIER_GLUED) + r"))"
)
CREDENTIAL_QUALIFIED_BASE = r"(?:" + "|".join(STANDALONE_CREDENTIAL_NAMES) + r"|authorization)"

CREDENTIAL_NAME = (
    r"(?:(?:" + CREDENTIAL_PREFIX + r")[._-]?)?" + CREDENTIAL_CORE + r"|(?:session|user)[._-]?id|sessionid|userid"
    r"|(?:" + "|".join(STANDALONE_CREDENTIAL_NAMES) + r")"
    r"|" + CREDENTIAL_QUALIFIED_BASE + CREDENTIAL_QUALIFIER
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

# A path segment that names an account. The segment after it is an account id
# when it is a number, however short: the digit-run rule needs nine digits,
# and an account id is not always that long.
ACCOUNT_PATH_COMPONENTS = {
    "account",
    "accounts",
    "customer",
    "customers",
    "profile",
    "profiles",
    "user",
    "users",
}

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

# A run of digits in a value whose name says nothing about what it is — the
# value of a header, the value of a query parameter. The site asks with digit
# runs of its own — `dest=1259570991` is the catalog the page is about,
# `hide_vflags=4294967296` is a mask of flags, `nm=161886426` is a card — and
# those runs are the evidence: they say what the request asked for. They are
# ten digits at the longest, so a longer run is not one of them: the account
# the request was made from is a run like that (`user_id=2285324551794928359`),
# and it must not reach a committed file. A run of ten digits is kept, because
# it cannot be told apart from the site's own constants.
VALUE_DIGIT_RUN = re.compile(r"\d{11,}")
EMAIL_ADDRESS = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")

# A phone number, as people write it: ten or eleven digits in groups, with a
# country code in front. The groups are what make it a phone number — a run of
# ten digits without them is an id (`dest=1259570991`), and a date does not fit
# the groups either.
PHONE_NUMBER = re.compile(r"(?<!\d)(?:\+7[ .-]?|8[ .-])?\(?\d{3}\)?[ .-]\d{3}[ .-](?:\d{2}[ .-]\d{2}|\d{4})(?![\w-])")

# An opaque value inside a name: a token or a JWT. The hyphen is not part of it —
# a name made of hyphenated words is a name, not a token.
#
# The run is only a candidate: a dotted run is judged part by part. A dotted name
# is a name — `investigation.capture.wb-research-notes` is evidence, and turning
# it into `opaque` throws the evidence away — so a part is replaced only when it
# has the shape of a token.
OPAQUE_RUN = re.compile(r"[A-Za-z0-9._]{24,}")

# A part of a dotted run that is a token: a blob of at least this many
# characters, or a part that is at least this long and mixes letters with digits
# (the header of a JWT is twenty characters of base64).
OPAQUE_BLOB_LENGTH = 24
OPAQUE_MIXED_LENGTH = 20

# A UUID inside a name: the identifier of a browser profile, written in groups.
# The groups are short, so the opaque-value rule does not see it, and the hyphen
# is what keeps it out of the opaque-identifier rule — so it is recognized by its
# own shape. The edges are guarded, so that a longer run of hex characters is
# not read as a UUID inside it.
UUID_VALUE = re.compile(r"(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])")

# The same identifier written without the hyphens. It has the same length as a
# hash, so the version and the variant are checked: a UUID names a version and
# a variant, and an ordinary hash names neither. The compact spelling is rare,
# but it is the same identifier — a value that only one spelling does not reach
# the committed file is a value that reaches it in the other spelling.
UUID_COMPACT = re.compile(
    r"(?i)(?<![0-9a-f])[0-9a-f]{8}[0-9a-f]{4}[1-5][0-9a-f]{3}[89ab][0-9a-f]{3}[0-9a-f]{12}(?![0-9a-f])"
)

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


def is_opaque_part(part: str) -> bool:
    """Whether a part of a dotted run is a token rather than a word.

    A blob of characters is a token; a word is not. The blob is long enough on
    its own, and a part that mixes letters with digits is a token even when it
    is shorter — that is what the header of a JWT looks like.
    """
    if len(part) >= OPAQUE_BLOB_LENGTH:
        return True
    if len(part) < OPAQUE_MIXED_LENGTH:
        return False
    return any(c.isdigit() for c in part) and any(c.isalpha() for c in part)


def redact_opaque_run(text: str) -> str:
    """The tokens inside a name, with the names around them kept.

    A name that is made of dotted words is evidence: it says which capture it
    is. So a dotted run is judged part by part, and only the parts that have
    the shape of a token are replaced.
    """

    def replace(match: re.Match[str]) -> str:
        run = match.group(0)
        if "." not in run:
            return "opaque"
        return ".".join("opaque" if is_opaque_part(part) else part for part in run.split("."))

    return OPAQUE_RUN.sub(replace, text)


def opaque_parts(run: str) -> str:
    """A run of opaque characters, with the names inside it kept.

    A dotted run is judged part by part — a name made of dotted words is
    evidence, and a token inside such a name is not. A run whose parts are all
    blobs (`header.payload.signature` of a token) is one token, so it becomes
    one marker.
    """
    parts = run.split(".") if "." in run else [run]
    if all(is_opaque_part(part) for part in parts):
        return placeholder("opaque")
    if not any(part.isalpha() and not part.isdigit() for part in parts):
        # No part of the run is a word: the run is a set of blobs, not a name.
        return placeholder("opaque")
    return ".".join(placeholder("opaque") if is_opaque_part(part) else part for part in parts)


def safe_identifier(value: Any, fallback: str) -> str:
    """An operator-chosen name, reduced to a slug that cannot carry a secret.

    The name of a capture file and the variant of a probe are strings the
    operator typed, and they end up in a committed file. A slug keeps the name
    readable — the name is what tells one capture from another — while a name
    that carries a long run of digits is dropped (that is the shape of an
    account id), an email address or a phone number in it is replaced: a slug
    of them would still be them, spelled out, and a UUID in it is replaced: it
    is the identifier of a profile, written in groups too short for the other
    rules to see.
    """
    raw = str(value or "")
    text = redact_credentials(raw, label="redacted")
    text = EMAIL_ADDRESS.sub("email", text)
    text = PHONE_NUMBER.sub("phone", text)
    text = UUID_VALUE.sub("opaque", text)
    text = UUID_COMPACT.sub("opaque", text)
    text = redact_opaque_run(text)
    text = UNSAFE_IDENTIFIER.sub("-", text).strip("-")
    if not text or LONG_DIGIT_RUN.search(text):
        return fallback
    return text[:MAX_IDENTIFIER_LENGTH]


def is_number(value: Any) -> bool:
    """A number that is not a boolean — `True` is not a count of one."""
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def safe_key(key: Any) -> str:
    """A key of a JSON answer that may be committed.

    A key is a name the service chose, and a name can be a person's address or
    an account id: `{"alice@example.com": ...}` is an answer whose key *is* the
    address. A key that survives the rules unchanged is kept as it is — the
    shape of the answer is the evidence — and a key that does not becomes one
    placeholder, so the fact that there was a key stays and the value does not.

    A key made of digits only is an identifier, not a name: an answer indexed
    by account id carries the id in the key. The digit-run rule needs nine
    digits and an account id is not always that long, so the key is judged by
    its shape: digits only means it is an id.

    A key that carries a space is a phrase, not the name of a field: it is
    free text — a person's name, a sentence — that the answer happens to be
    keyed by. A field name the service chose is one word.
    """
    text = str(key)
    if text.isdigit():
        return placeholder("key")
    if any(character.isspace() for character in text):
        return placeholder("key")
    if redact_note(text) == text:
        return text
    return placeholder("key")


def safe_keys(keys: Any) -> list[str] | None:
    """The top-level keys of a JSON answer, safe to commit."""
    if not isinstance(keys, list):
        return None
    return sorted(safe_key(key) for key in keys)


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


def redact_generic_value(value: str) -> str:
    """A value whose name says nothing about what it is — its shape decides.

    The name of a header or of a query parameter is not a promise: a profile
    identifier in an ordinary parameter and a token in a user agent are both
    values that only their shape gives away. So the value goes through the rules
    that need no name: a UUID is the identifier of a profile — in either
    spelling, and wherever it sits in the value — a long opaque run is a token,
    a long run of digits is an account id, and the personal values are replaced
    as they are everywhere else.

    A dotted name is a name: its parts are judged one by one, so a name of
    dotted words survives and a token inside it does not.
    """
    text = UUID_VALUE.sub(placeholder("opaque"), value)
    text = UUID_COMPACT.sub(placeholder("opaque"), text)
    # The digit rule comes after the UUID rule: the digits of a UUID are part
    # of it, and a rule that runs first would eat the identifier.
    text = VALUE_DIGIT_RUN.sub(placeholder("id"), text)
    # The whole value may be one run of opaque characters — hyphens and all —
    # or a run may sit inside a value that carries other words.
    text = opaque_parts(text) if OPAQUE_VALUE.match(text) else OPAQUE_RUN.sub(opaque_parts, text)
    text = redact_credentials(text)
    text = EMAIL_ADDRESS.sub("email", text)
    text = PHONE_NUMBER.sub("phone", text)
    return text[:MAX_VALUE_LENGTH]


def redact_header_value(name: str, value: str, safe: set[str]) -> str:
    """Reduce one header value to what may be committed.

    A header on the safe list may still carry a credential: `Bearer abc123` in
    a user agent is a credential whoever sent it. The name of the header says
    what may be shown; the value still goes through the rules — including the
    ones that need no name, because an allowlisted name is not a promise about
    the value it carries.
    """
    if name.lower() in safe:
        if URL_VALUE.match(value):
            return redact_url(value)
        return redact_generic_value(value)
    return placeholder(name.lower())


def redact_query_value(name: str, value: str) -> str:
    """Reduce one query parameter value to what may be committed.

    The name of the parameter is not the only thing that can carry a secret: a
    search for a phone number puts the number in an ordinary parameter, so the
    personal values are replaced here as well.

    A name that names a credential is redacted whatever the spelling of the
    name is: `access_token`, `accessToken` and `x-auth-token` are the same
    name. The name is recognized by the same rule the rest of the redactor
    uses, so that a new spelling of a name does not open a new hole.

    A name that carries free text is redacted as well: what somebody typed is
    the search history of the session, and it can be a person's name.

    A run of digits is replaced when it is longer than the runs the site asks
    with (`VALUE_DIGIT_RUN`): the account the request was made from is such a
    run, and it is not evidence. A run of ten digits stays — it cannot be told
    apart from the site's own constants (`dest=1259570991`), and those are
    the evidence.
    """
    if name.lower() in FREE_TEXT_QUERY_PARAMS:
        return placeholder("query-param")
    if name.lower() in SENSITIVE_QUERY_PARAMS:
        return placeholder("query-param")
    if name.lower().endswith(CREDENTIAL_NAME_SUFFIX):
        return placeholder("query-param")
    if CREDENTIAL_SEGMENT.fullmatch(name) is not None:
        return placeholder("query-param")
    if URL_VALUE.match(value):
        return redact_url(value)
    return redact_generic_value(value)


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


def safe_query_name(name: str) -> str:
    """The name of a query parameter, if it may be committed.

    The name is part of the URL and part of the evidence, and an ordinary name
    (`resultset`, `spp`, `dest`) stays as it is. A name that carries personal
    data — an address, a phone number, an account id, an opaque token — is not
    a name but a value that happens to stand on the left of the `=`, so it
    becomes one placeholder. A name made of digits only is an identifier, the
    way a key of digits is.

    A name that carries a space is a phrase, not the name of a field: it is
    free text — a person's name — that the request happens to be keyed by. A
    name the service chose is one word.
    """
    if name.isdigit() or any(character.isspace() for character in name) or redact_note(name) != name:
        return placeholder("query-name")
    return name


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
        query.append((safe_query_name(name), redact_query_value(name, value)))
    return urlunsplit((parts.scheme, authority_without_userinfo(parts), redact_path(parts.path), urlencode(query), ""))


def is_account_id(segment: str) -> bool:
    """Whether a path segment is an account id.

    The segment after a segment that names an account is an id, and an id is
    not always nine digits long. It is judged by its shape: digits, a mix of
    letters and digits, or a separator between them is an identifier. A plain
    word is the name of a route the service chose — `user/check` is an
    endpoint, not an account.
    """
    if not segment:
        return False
    return re.fullmatch(r"[A-Za-z]+", segment) is None


def redact_path(path: str) -> str:
    """A URL path, without the values in it that could identify a session.

    The path is part of the URL, and it carries values as well: a reset link
    (`/reset/<token>`), a profile (`/users/<address>`), a phone number, an
    account id. The query values are judged by their value already; the path
    was kept as it came, so anything in it reached the committed file as it
    was.

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
        if previous.lower() in ACCOUNT_PATH_COMPONENTS and is_account_id(segment):
            # The segment after a segment that names an account is an account id,
            # and an account id is not always nine digits long.
            kept.append(placeholder("account-id"))
            previous = segment
            continue
        text = EMAIL_ADDRESS.sub("email", segment)
        text = PHONE_NUMBER.sub("phone", text)
        text = OPAQUE_TOKEN.sub("opaque", text)
        text = LONG_DIGIT_RUN.sub(placeholder("account-id"), text)
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
        raw = str(header.get("name", "")).lower()
        if not raw:
            continue
        name = safe_header_name(raw)
        if name != raw:
            # The name is not a name: a header of a capture can be spelled
            # anything, and the name itself would reach the committed file.
            key = placeholder("header-name")
            if key not in out:
                out[key] = placeholder("header-name")
            continue
        if name in safe:
            out[name] = redact_header_value(name, str(header.get("value", "")), safe)
        elif name not in out:
            out[name] = placeholder(name)
    return dict(sorted(out.items()))


# The media type of a content type header: `type/subtype`, and nothing else.
MEDIA_TYPE = re.compile(r"(?i)^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$")

# The parameters of a content type header that may be kept. A charset is a
# name and a number of characters; anything else can carry a value of its own.
SAFE_CONTENT_TYPE_PARAMS = {"charset"}


def safe_content_type(value: Any) -> str | None:
    """The media type of a response, and the parameters that may be committed.

    The content type comes from the server, and a server may answer with
    anything: a media type and a list of parameters is what a content type
    is, so only that is kept. The parameters that survive are named ones —
    `charset` — and their value is a token of characters, not free text.
    """
    if not isinstance(value, str):
        return None
    parts = value.split(";")
    media = parts[0].strip()
    if not MEDIA_TYPE.match(media):
        return None
    kept = [media]
    for part in parts[1:]:
        name, separator, raw = part.partition("=")
        if not separator:
            continue
        name = name.strip().lower()
        raw = raw.strip()
        if name not in SAFE_CONTENT_TYPE_PARAMS:
            continue
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,40}", raw):
            continue
        kept.append(f"{name}={raw}")
    return "; ".join(kept)


# The method of a request: a token of letters and nothing else. The capture is
# an input, so a field nobody looked at must not be able to carry a value.
HTTP_METHOD = re.compile(r"[A-Za-z]{3,12}")


def safe_method(value: Any) -> str | None:
    """The method of a request, if it is one.

    A method is a word of letters: `GET`, `POST`, `PROPFIND`. Anything else in
    this field is not a method, and a value that rode in here would reach the
    committed file as if it were the method.
    """
    if not isinstance(value, str) or not HTTP_METHOD.fullmatch(value):
        return None
    return value.upper()


def safe_status(value: Any) -> int | None:
    """The status of a response, if it is one.

    An HTTP status is a number between 100 and 599. A status that was never
    answered is `null` with a reason, not a zero and not a string.
    """
    if not is_number(value):
        return None
    status = int(value)
    return status if 100 <= status <= 599 else None


def safe_size(value: Any) -> int | None:
    """The size of a body in bytes, if it is a size."""
    if not is_number(value):
        return None
    size = int(value)
    return size if size >= 0 else None


# The moment a HAR entry was recorded: an ISO-8601 timestamp.
ISO_TIMESTAMP = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})")


def safe_timestamp(value: Any) -> str | None:
    """When the request started, if the capture said when."""
    if not isinstance(value, str) or not ISO_TIMESTAMP.fullmatch(value):
        return None
    return value


# The credentials mode of a request. The browser knows three of them.
CREDENTIALS_MODES = {"include", "omit", "same-origin"}


def safe_credentials(value: Any) -> str | None:
    """The credentials mode of a request, if it is one."""
    if not isinstance(value, str):
        return None
    return value if value in CREDENTIALS_MODES else None


# The name of a header: a token of the characters a name may carry.
HEADER_NAME = re.compile(r"[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")


def is_identifier_name(name: str) -> bool:
    """Whether the name of a header is an identifier rather than a name.

    A name is a token of characters, and a token can be an identifier: a
    profile's UUID in either spelling, an account id, a hash of a session.
    Such a name is a value that happens to stand where a name stands, and it
    must not reach the committed file.

    A name the service chose is made of words: a run that mixes letters with
    digits is a token — the header of a JWT, a hash — and a name of digits
    only is an id.
    """
    if name.isdigit():
        return True
    if UUID_VALUE.fullmatch(name) or UUID_COMPACT.fullmatch(name):
        return True
    return len(name) >= OPAQUE_MIXED_LENGTH and any(c.isdigit() for c in name) and any(c.isalpha() for c in name)


def safe_header_name(name: Any) -> str:
    """The name of a header, if it may be committed.

    A name that is not a token is not a name: it can be an address or a
    credential. A name that is an identifier is not a name either. The fact
    that a header was there stays, the name does not.
    """
    text = str(name).lower()
    if not HEADER_NAME.fullmatch(text) or is_identifier_name(text):
        return placeholder("header-name")
    return text


def safe_header_names(names: Any) -> list[str]:
    """The names of the headers that were sent, if they are names.

    A name that is not a token is not a name: it can be an address or a
    credential. A name that is an identifier is not a name either. The fact
    that a header was there stays, the name does not.
    """
    if not isinstance(names, list):
        return []
    return sorted(safe_header_name(name) for name in names)


def safe_bool(value: Any) -> bool | None:
    """A flag of the capture, if it is a flag."""
    return value if isinstance(value, bool) else None


# The phases a HAR records. Only a number is evidence of a phase; anything else
# in a timing field is dropped, the way an unknown header value is dropped.
HAR_TIMING_PHASES = ("blocked", "dns", "connect", "send", "wait", "receive", "ssl")


def har_timing(entry: dict[str, Any]) -> dict[str, Any]:
    """How long the request took. The HAR knows it; the browser does not.

    The timing is an input like any other: the phases are taken by name and
    only a number survives, so a capture cannot carry a value into the file
    through a field nobody looked at.
    """
    timings = entry.get("timings")
    kept: dict[str, Any] = {}
    if isinstance(timings, dict):
        for phase in HAR_TIMING_PHASES:
            value = timings.get(phase)
            if is_number(value):
                kept[phase] = value
    duration = entry.get("time")
    return {"durationMs": duration if is_number(duration) else None, "timings": kept}


# The fields of the capture annotation that may reach a committed file. The
# annotation is written into the HAR by the capture, and the HAR is an input:
# a field this sanitizer does not know may carry anything, so it is dropped
# rather than copied.
CAPTURE_ANNOTATION_FIELDS = ("available", "reason", "bodyBytes", "isJson", "jsonType", "topLevelKeys")

# The reasons a body was not read. The annotation carries the code of the
# reason, not its text: the browser's own error text is not a reason, it is the
# text of a failure, and it may quote the URL or the request. The codes are
# the bridge between the capture and this module, and the text of a reason
# belongs to this module — the evidence format is written here.
CAPTURE_REASONS = {
    "ambiguous": "several requests looked the same, so no body was attributed to this one",
    "browser-error": "the browser did not return the response body",
    "no-body": "the request detail carried no body",
    "not-read": "the response body was not read",
}

# The kinds of JSON body there are. Anything else is not a kind, and a kind
# that is not one of these says nothing.
JSON_TYPES = ("object", "array", "scalar")


def safe_reason(value: Any) -> str:
    """The reason a body was not read, if it is one of the reasons.

    The annotation is an input, so a reason that is not one of the codes is
    not kept: it may be the browser's own error text, which quotes the
    request.
    """
    return CAPTURE_REASONS.get(str(value), placeholder("error"))


def har_schema(entry: dict[str, Any]) -> dict[str, Any]:
    """The shape of the response body, when the capture read it.

    The capture that produced the HAR can read the body of a request one by one
    and keep only its shape. It writes that shape into the entry as `_capture`.
    An entry without it means the body was never read — which is not the same as
    a body that was read and was empty.

    The annotation is an input, not a trusted source: only the fields this
    sanitizer knows are taken, and each of them only with the type it is
    supposed to have. A capture cannot smuggle a value into the fixture
    through a field nobody looked at.
    """
    annotation = entry.get("_capture")
    if not isinstance(annotation, dict):
        return {"available": False, "reason": "the capture did not read the response body"}
    shape: dict[str, Any] = {}
    for field in CAPTURE_ANNOTATION_FIELDS:
        if field not in annotation:
            continue
        value = annotation[field]
        if field in ("available", "isJson"):
            shape[field] = safe_bool(value)
        elif field == "reason":
            shape[field] = safe_reason(value)
        elif field == "bodyBytes":
            shape[field] = safe_size(value)
        elif field == "jsonType":
            shape[field] = value if value in JSON_TYPES else None
        else:
            shape[field] = safe_keys(value)
    return shape


def har_response(response: dict[str, Any], pending: bool) -> dict[str, Any]:
    """What came back.

    A request that was still in flight when the capture stopped has no answer:
    the browser reports a zero status and an empty content type, and those are
    not HTTP values. They become null with a reason, so that a request without
    an answer cannot be read as a request that was answered with nothing.
    """
    content = response.get("content", {}) or {}
    if pending:
        return {
            "status": None,
            "contentType": None,
            "bodyBytes": None,
            "headers": {},
            "reason": "the capture recorded no status for this request",
        }
    return {
        "status": safe_status(response.get("status")),
        "contentType": safe_content_type(content.get("mimeType")),
        "bodyBytes": safe_size(content.get("size")),
        "headers": redact_headers(response.get("headers", []) or [], SAFE_RESPONSE_HEADERS),
    }


def capture_from_har_entry(entry: dict[str, Any], capture_id: str, source_file: str) -> dict[str, Any]:
    """One HAR entry as one capture."""
    request = entry.get("request", {})
    response = entry.get("response", {})
    annotation = entry.get("_capture") if isinstance(entry.get("_capture"), dict) else {}
    pending = safe_bool(annotation.get("pending")) is True or not response.get("status")
    return {
        "id": capture_id,
        "source": "har",
        "sourceFile": safe_identifier(source_file, "capture"),
        "started": safe_timestamp(entry.get("startedDateTime")),
        "request": {
            "method": safe_method(request.get("method")),
            "url": redact_url(str(request.get("url", ""))),
            "headers": redact_headers(request.get("headers", []) or [], SAFE_REQUEST_HEADERS),
        },
        "response": har_response(response, pending),
        "timing": har_timing(entry),
        "schema": har_schema(entry),
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
            "method": safe_method(probe.get("method")),
            "url": redact_url(str(probe.get("url", ""))),
            "credentials": safe_credentials(probe.get("credentials")),
            "headersSent": safe_header_names(probe.get("headerNames")),
            "headerValuesCaptured": False,
        },
        "response": {
            "status": safe_status(probe.get("status")),
            "contentType": safe_content_type(probe.get("contentType")),
            "bodyBytes": safe_size(probe.get("bodyBytes")),
            "isJson": safe_bool(probe.get("isJson")),
            "error": redact_error(probe.get("error")),
        },
        "schema": {
            "topLevelKeys": safe_keys(probe.get("topLevelKeys")),
            "productCount": probe.get("productCount") if is_number(probe.get("productCount")) else None,
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
            "method": safe_method(request.get("method")),
            "url": redact_url(str(request.get("url", ""))),
            "credentials": safe_credentials(request.get("credentials")),
            "headersSent": safe_header_names(request.get("sentByBrowser")),
            "headersDroppedByBrowser": safe_header_names(request.get("droppedByBrowser")),
            "headerValuesCaptured": False,
        },
        "response": {
            "status": safe_status(response.get("status")),
            "contentType": safe_content_type(response.get("contentType")),
            "bodyBytes": safe_size(shape.get("bodyBytes")),
            "isJson": safe_bool(shape.get("isJson")),
        },
        "schema": {
            "topLevelKeys": safe_keys(shape.get("topLevelKeys")),
            "productCount": shape.get("productCount") if is_number(shape.get("productCount")) else None,
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

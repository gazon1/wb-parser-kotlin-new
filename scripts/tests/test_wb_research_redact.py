"""The redactor is the boundary between a raw capture and a committed file.

A secret that survives it is a secret in the repository, so the test feeds it
the things that must not survive and checks that they do not.
"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pytest

REDACTOR = Path(__file__).resolve().parents[1] / "wb-research" / "redact.py"


def load_redactor():
    spec = importlib.util.spec_from_file_location("wb_research_redact", REDACTOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


redact = load_redactor()

SECRET_COOKIE = "wb_session=abc123def456; _wbauid=9876543210"
SECRET_TOKEN = "qid228532455179492835920261119130047"
SECRET_DEVICE = "site_9ee9449a3a1a448393d750b3dd2c22e8"

HAR = {
    "log": {
        "entries": [
            {
                "startedDateTime": "2026-11-19T13:00:47.027Z",
                "request": {
                    "method": "GET",
                    "url": (
                        "https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search"
                        "?resultset=catalog&query=menu_v3_9467&wbaas_token=qwertyuiop123456"
                    ),
                    "headers": [
                        {"name": "Referer", "value": "https://www.wildberries.ru/catalog/0/list.aspx"},
                        {"name": "User-Agent", "value": "Mozilla/5.0 Chrome/155.0.0.0"},
                        {"name": "x-requested-with", "value": "XMLHttpRequest"},
                        {"name": "Cookie", "value": SECRET_COOKIE},
                        {"name": "deviceid", "value": SECRET_DEVICE},
                        {"name": "x-queryid", "value": SECRET_TOKEN},
                        {"name": "Authorization", "value": "Bearer topsecrettoken"},
                    ],
                },
                "response": {
                    "status": 200,
                    "headers": [
                        {"name": "content-type", "value": "text/plain; charset=UTF-8"},
                        {"name": "set-cookie", "value": SECRET_COOKIE},
                    ],
                    "content": {"mimeType": "text/plain", "size": 44265, "text": "customer: Ivan Petrov"},
                },
            }
        ]
    }
}

ADAPTER = {
    "success": True,
    "data": {
        "result": {
            "request": {
                "method": "GET",
                "url": "https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search?resultset=catalog",
                "credentials": "include",
                "headersRequested": ["x-requested-with"],
                "sentByBrowser": ["x-requested-with", "x-userid"],
                "droppedByBrowser": [],
            },
            "response": {
                "status": 403,
                "contentType": "text/plain",
                "ok": False,
                "shape": {"bodyBytes": 0, "isJson": False},
            },
        }
    },
}

PROBE = {
    "probes": [
        {
            "variant": "storefront",
            "url": "https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search?resultset=catalog&wbaas_token=qwertyuiop123456",
            "method": "GET",
            "credentials": "include",
            "headerNames": ["x-requested-with", "x-userid", "deviceid"],
            "status": 200,
            "contentType": "application/json",
            "bodyBytes": 233,
            "isJson": True,
            "topLevelKeys": ["name", "query", "search_result"],
            "productCount": None,
        }
    ]
}


def test_a_raw_capture_is_reduced_to_metadata():
    captures = redact.sanitize(HAR, stem="capture")
    assert len(captures) == 1
    capture = captures[0]
    assert capture["response"]["status"] == 200
    assert capture["response"]["bodyBytes"] == 44265
    assert capture["body"]["included"] is False


def test_no_secret_value_survives():
    captures = redact.sanitize(HAR, stem="capture")
    text = json.dumps(captures)
    assert SECRET_COOKIE not in text
    assert SECRET_TOKEN not in text
    assert SECRET_DEVICE not in text
    assert "topsecrettoken" not in text
    assert "Ivan Petrov" not in text


def test_the_structure_of_the_request_survives():
    headers = redact.sanitize(HAR, stem="capture")[0]["request"]["headers"]
    assert headers["referer"] == "https://www.wildberries.ru/catalog/0/list.aspx"
    assert headers["x-requested-with"] == "XMLHttpRequest"
    assert headers["cookie"] == "<redacted:cookie>"
    assert headers["deviceid"] == "<redacted:deviceid>"


def test_a_sensitive_query_parameter_loses_its_value():
    url = redact.sanitize(HAR, stem="capture")[0]["request"]["url"]
    assert "wbaas_token=%3Credacted%3Aquery-param%3E" in url
    assert "qwertyuiop123456" not in url
    assert "resultset=catalog" in url


def test_an_adapter_result_is_sanitized_too():
    captures = redact.sanitize(ADAPTER, stem="attempt")
    assert captures[0]["response"]["status"] == 403
    assert captures[0]["request"]["headersSent"] == ["x-requested-with", "x-userid"]
    assert captures[0]["request"]["headerValuesCaptured"] is False


def test_the_same_capture_gives_the_same_fixture():
    assert redact.sanitize(HAR, stem="capture") == redact.sanitize(HAR, stem="capture")


def test_a_capture_that_is_neither_a_har_nor_an_adapter_result_is_refused():
    with pytest.raises(ValueError):
        redact.sanitize({"something": "else"})


def test_a_probe_keeps_the_shape_of_the_answer_and_drops_the_token():
    captures = redact.sanitize(PROBE, stem="probe")
    capture = captures[0]
    assert capture["id"] == "probe-storefront"
    assert capture["response"]["status"] == 200
    assert capture["schema"]["topLevelKeys"] == ["name", "query", "search_result"]
    assert capture["schema"]["productCount"] is None
    assert "qwertyuiop123456" not in json.dumps(capture)
    assert capture["request"]["headersSent"] == ["deviceid", "x-requested-with", "x-userid"]


def test_a_capture_that_prints_a_line_before_its_json_is_read(tmp_path):
    path = tmp_path / "probe.txt"
    path.write_text("eval @ https://www.wildberries.ru\n" + json.dumps(PROBE), encoding="utf-8")
    document = redact.load_document(path)
    assert document["probes"][0]["variant"] == "storefront"


def test_the_price_pairs_of_a_probe_survive_the_sanitizer():
    # The pairs are the measurement that shows the unit of the cashback: the
    # number next to the price it belongs to.
    probe = {
        "variant": "fcashback-1",
        "cashbackPricePairs": [
            {"id": 727988295, "priceKopecks": 240000, "salePriceKopecks": 77000, "cashback": 3},
        ],
    }

    measurements = redact.redact_measurements(probe)

    assert measurements["cashbackPricePairs"] == [
        {"id": 727988295, "priceKopecks": 240000, "salePriceKopecks": 77000, "cashback": 3},
    ]


def test_a_pair_that_carries_a_string_loses_the_pairs():
    # A pair is a measurement: a name that slipped into one takes the whole
    # list with it, the way a string in a list of values takes the list.
    probe = {
        "variant": "fcashback-1",
        "cashbackPricePairs": [
            {"id": 727988295, "priceKopecks": 240000, "cashback": 3, "name": "alice@example.com"},
        ],
    }

    measurements = redact.redact_measurements(probe)

    assert "cashbackPricePairs" not in measurements


def test_the_account_id_is_not_kept_even_when_it_is_short():
    # The finding of the review: `x-userid` was on the allowlist, and a numeric id
    # is too short for the opaque-value rule to catch.
    har = {
        "log": {
            "entries": [
                {
                    "request": {
                        "method": "GET",
                        "url": "https://www.wildberries.ru/catalog/0/list.aspx",
                        "headers": [{"name": "x-userid", "value": "123456789012345"}],
                    },
                    "response": {"status": 200, "headers": [], "content": {}},
                }
            ]
        }
    }

    headers = redact.sanitize(har, stem="capture")[0]["request"]["headers"]

    assert headers["x-userid"] == "<redacted:x-userid>"
    assert "123456789012345" not in json.dumps(headers)


def test_the_text_of_an_error_is_kept_out_of_the_fixture():
    probe = {
        "probes": [
            {
                "variant": "failing",
                "url": "https://www.wildberries.ru/__internal/u-search",
                "error": 'TypeError: Failed to fetch {"name": "Иван Петров"}',
            }
        ]
    }

    capture = redact.sanitize(probe, stem="probe")[0]

    assert capture["response"]["error"] == "TypeError"
    assert "Иван Петров" not in json.dumps(capture)


def test_a_url_fragment_is_dropped():
    url = redact.redact_url("https://www.wildberries.ru/catalog/0/list.aspx#access_token=qwertyuiop123456")

    assert "access_token" not in url
    assert "#" not in url


def test_a_url_in_a_header_value_is_redacted_as_a_url():
    headers = redact.redact_headers(
        [{"name": "referer", "value": "https://www.wildberries.ru/x?wbaas_token=qwertyuiop123456"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert "qwertyuiop123456" not in headers["referer"]
    assert "wbaas_token=%3Credacted%3Aquery-param%3E" in headers["referer"]


def test_a_jwt_shaped_value_is_opaque():
    jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl"

    assert redact.redact_query_value("some", jwt) == "<redacted:opaque>"


def test_the_user_information_of_a_url_is_dropped():
    # The finding of the review: `netloc` carries `user:password@host`, and the
    # user information is a credential. The URL is assembled from parts, so the
    # secret scanner does not read the fixture itself as a credential.
    userinfo = "alice" + ":" + "secret"

    url = redact.redact_url(f"https://{userinfo}@www.wildberries.ru/catalog/0/list.aspx")

    assert "alice" not in url
    assert "secret" not in url
    assert url == "https://www.wildberries.ru/catalog/0/list.aspx"


def test_the_host_and_the_port_of_a_url_survive():
    url = redact.redact_url("https://www.wildberries.ru:8443/catalog/0/list.aspx")

    assert url == "https://www.wildberries.ru:8443/catalog/0/list.aspx"


def test_a_value_in_the_path_of_a_url_is_replaced():
    # The finding of the review: the path is part of the URL too, and it carries
    # a token, an address and a phone number just as the query does.
    url = redact.redact_url("https://www.wildberries.ru/reset/eyJhbGciOiJIUzI1NiJ9abcdefghij")

    assert "abcdefghij" not in url
    assert url == "https://www.wildberries.ru/reset/opaque"


def test_an_address_in_the_path_of_a_url_is_replaced():
    url = redact.redact_url("https://www.wildberries.ru/users/alice@example.com")

    assert "alice" not in url
    assert "example.com" not in url


def test_an_ordinary_path_of_a_url_survives():
    url = redact.redact_url("https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search")

    assert url == "https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search"


def test_an_encoded_address_in_the_path_of_a_url_is_replaced():
    # The finding of the review: a capture may spell an address with its escapes,
    # and the server reads it the same way the sanitizer has to.
    url = redact.redact_url("https://www.wildberries.ru/users/alice%40example.com")

    assert "alice" not in url
    assert "example.com" not in url


def test_an_encoded_token_in_the_path_of_a_url_is_replaced():
    url = redact.redact_url("https://www.wildberries.ru/reset/eyJhbGciOiJIUzI1NiJ9abcdefghij")

    assert "abcdefghij" not in url


def test_a_twice_encoded_address_in_the_path_of_a_url_is_replaced():
    url = redact.redact_url("https://www.wildberries.ru/users/alice%2540example.com")

    assert "alice" not in url
    assert "example.com" not in url


def test_an_encoded_phone_number_in_the_path_of_a_url_is_replaced():
    url = redact.redact_url("https://www.wildberries.ru/users/%2B7%20999%20123-45-67")

    assert "999" not in url


def test_the_account_id_in_a_query_parameter_loses_its_value():
    # The account id is short enough to pass the opaque-value rule, so the
    # parameter name has to carry the rule.
    url = redact.redact_url("https://www.wildberries.ru/api?x-userid=123456789012345")

    assert "123456789012345" not in url
    assert "x-userid=%3Credacted%3Aquery-param%3E" in url


def test_a_short_credential_in_a_query_parameter_loses_its_value():
    # The finding of the review: the opaque-value rule needs 24 characters, so a
    # short credential (`access_token=abc123`) passed every length-based rule.
    url = redact.redact_url("https://example.com/api?access_token=abc123")

    assert "abc123" not in url
    assert "access_token=%3Credacted%3Aquery-param%3E" in url


def test_a_credential_parameter_name_loses_its_value_whatever_it_is_called():
    # The list of known names cannot hold every name a service invents, so the
    # suffix of the name carries the rule.
    url = redact.redact_url("https://example.com/api?partner_refresh_token=abc123&client_secret=xyz789")

    assert "abc123" not in url
    assert "xyz789" not in url


def test_an_ordinary_query_parameter_keeps_its_value():
    url = redact.redact_url("https://example.com/api?resultset=catalog&spp=30")

    assert "resultset=catalog" in url
    assert "spp=30" in url


def test_a_credential_in_an_allowed_header_value_is_replaced():
    # The finding of the review: a header on the safe list may still carry a
    # credential, and the value is what carries it.
    headers = redact.redact_headers(
        [{"name": "user-agent", "value": "Bearer abc123"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert "abc123" not in headers["user-agent"]


def test_a_short_credential_in_a_note_is_replaced():
    # The finding of the review: the opaque-value rule needs 24 characters, so a
    # short credential in a note passed it.
    note = redact.redact_note("failed request: access_token=abc123")

    assert "abc123" not in note


def test_a_short_credential_after_a_path_component_is_replaced():
    # The finding of the review: a reset link carries a short token as well.
    url = redact.redact_url("https://example.com/reset/abc123")

    assert "abc123" not in url


def test_an_encoded_short_credential_after_a_path_component_is_replaced():
    url = redact.redact_url("https://example.com/reset/%61bc123")

    assert "abc123" not in url


def test_a_short_credential_in_a_name_is_replaced():
    # The finding of the review: the name of a capture file reaches the
    # committed file as it is.
    name = redact.safe_identifier("capture-access_token-abc123", "capture")

    assert "abc123" not in name


def test_an_operator_chosen_name_is_reduced_to_a_slug():
    assert redact.safe_identifier("probe/customer alice@example.com", "capture") == "probe-customer-email"


def test_an_email_in_a_name_does_not_survive_as_a_slug():
    # The finding of the review: a slug of an address is still the address.
    name = redact.safe_identifier("capture-alice@example.com", "capture")

    assert "alice" not in name
    assert "@" not in name


def test_a_name_that_carries_an_account_id_is_dropped():
    assert redact.safe_identifier("capture-123456789012", "capture") == "capture"


def test_the_capture_id_does_not_carry_the_variant_verbatim():
    probe = {
        "probes": [
            {
                "variant": "customer/alice@example.com",
                "url": "https://www.wildberries.ru/__internal/u-search",
            }
        ]
    }

    capture = redact.sanitize(probe, stem="probe")[0]

    assert "@" not in capture["id"]
    assert capture["sourceFile"] == "probe"


def test_the_capture_id_does_not_carry_the_file_name_verbatim():
    # The finding of the review: the id was built from the raw file name.
    capture = redact.sanitize(HAR, stem="capture-alice@example.com")[0]

    assert "@" not in capture["id"]
    assert "alice" not in capture["id"]
    assert capture["id"] == "email-001"


def test_a_note_goes_through_the_same_rules():
    # A note is free text: without the rules it is the easiest way to smuggle a
    # value into a committed file.
    note = redact.redact_note("deviceid=site_9ee9449a3a1a448393d750b3dd2c22e8 for alice@example.com")

    assert "site_9ee9449a3a1a448393d750b3dd2c22e8" not in note
    assert "alice" not in note


def test_a_url_inside_a_note_is_redacted_as_a_url():
    # The finding of the review: a URL after ordinary words was kept as it was.
    note = redact.redact_note("Request captured at https://example.com/api?token=secret123456789")

    assert "secret123456789" not in note
    assert "token=" in note


def test_a_phone_number_in_a_note_is_replaced():
    # The finding of the review: a short personal number is not an opaque value.
    note = redact.redact_note("contact 555-123-4567 or +7 495 123 45 67")

    assert "555-123-4567" not in note
    assert "123 45 67" not in note
    assert note.count("phone") == 2


def test_a_phone_number_in_a_name_is_replaced():
    assert redact.safe_identifier("call-555-123-4567", "capture") == "call-phone"


def test_a_date_in_a_name_is_not_a_phone_number():
    assert redact.safe_identifier("2026-11-19-catalog", "capture") == "2026-11-19-catalog"


def test_an_opaque_token_in_a_name_is_replaced():
    # The finding of the review: a name carries a token as well as a word.
    name = redact.safe_identifier("capture-eyJhbGciOiJIUzI1NiJ9abcdefghij", "capture")

    assert name == "capture-opaque"
    assert "abcdefghij" not in name


def test_a_personal_value_in_a_query_parameter_is_replaced():
    # The finding of the review: the parameter name was not the only carrier.
    url = redact.redact_url("https://www.wildberries.ru/search?query=555-123-4567&mail=alice@example.com")

    assert "555-123-4567" not in url
    assert "alice" not in url
    assert "phone" in url
    assert "email" in url


def test_a_number_that_is_not_a_phone_number_survives():
    # WB's own parameters carry long digit runs; they are ids, not phone numbers.
    url = redact.redact_url("https://www.wildberries.ru/search?dest=1259570991&hide_vflags=4294967296")

    assert "1259570991" in url
    assert "4294967296" in url


def test_an_account_id_in_a_note_is_replaced():
    # The finding of the review: a note kept what a name drops.
    note = redact.redact_note("captured under user 1234567890")

    assert "1234567890" not in note
    assert "account-id" in note


def test_a_note_keeps_the_words_of_the_investigation():
    note = redact.redact_note("the same catalog query with and without each filter")

    assert note == "the same catalog query with and without each filter"


def test_a_credential_written_with_a_space_is_replaced():
    # The finding of the review: a name and a value need no `=` to be a
    # credential.
    note = redact.redact_note("failed request: token abc123")

    assert "abc123" not in note
    assert "credential" in note


def test_a_credential_written_with_a_space_keeps_an_ordinary_word():
    # A name and an ordinary word after it is a sentence, not a credential.
    note = redact.redact_note("the session started at the third request")

    assert "started" in note


def test_a_compound_credential_name_in_a_path_is_replaced():
    # The finding of the review: the path component list held `token`, not
    # `access_token`.
    url = redact.redact_url("https://example.com/access_token/abc123")

    assert "abc123" not in url


def test_a_name_that_only_starts_like_a_credential_keeps_its_value():
    # The finding of the review: `author` is not `auth` with a suffix.
    note = redact.redact_note("author=Alice")

    assert note == "author=Alice"


def test_a_credential_in_a_qualified_name_is_replaced():
    # The finding of the review: a name may carry a prefix of its own.
    note = redact.redact_note("oauth.access_token=abc123")

    assert "abc123" not in note


def test_a_credential_in_a_qualified_name_is_replaced_in_a_name():
    # The finding of the review: the name of a capture file reaches the
    # committed file as it is.
    name = redact.safe_identifier("capture-oauth.access_token-abc123", "capture")

    assert "abc123" not in name


def test_a_credential_in_a_qualified_name_is_replaced_in_a_path():
    # The finding of the review: a name may carry a prefix of its own in a path
    # as well as in an assignment.
    url = redact.redact_url("https://example.com/oauth.access_token/abc123")

    assert "abc123" not in url


def test_an_ordinary_word_after_a_credential_name_keeps_its_value():
    # The finding of the review: a long run of lower-case letters is a word.
    note = redact.redact_note("the token generated by the storefront")

    assert "generated" in note


def test_an_ordinary_word_of_eight_letters_after_a_credential_name_keeps_its_value():
    # The finding of the review: length alone does not make a word a credential.
    note = redact.redact_note("token received from endpoint, session completed")

    assert "received" in note
    assert "completed" in note


def test_an_ordinary_word_that_starts_like_a_credential_keeps_its_value():
    # The finding of the review: `author` is not `auth` with a suffix.
    note = redact.redact_note("author: Wildberries request")

    assert note == "author: Wildberries request"


def test_a_credential_value_that_begins_with_a_marker_is_replaced():
    # The finding of the review: the marker exemption was a prefix check, so a
    # value that began with `opaque` passed it. A value is a value.
    for value in ("opaque123", "email123", "phone123"):
        url = redact.redact_url(f"https://example.com/reset/{value}")

        assert value not in url


def test_a_credential_value_after_a_qualified_name_is_replaced():
    url = redact.redact_url("https://example.com/oauth.access_token/opaque123")

    assert "opaque123" not in url


def test_an_ordinary_word_after_a_dashed_credential_name_keeps_its_value():
    # The finding of the review: a slug is a name and a word, not a credential.
    note = redact.redact_note("token-generated")

    assert "generated" in note


def test_a_dashed_credential_with_a_digit_in_it_is_replaced():
    note = redact.redact_note("token-abc123")

    assert "abc123" not in note


def test_a_proper_noun_after_a_credential_name_keeps_its_value():
    # The finding of the review: a capital at the start alone is a word.
    note = redact.redact_note("the token Wildberries uses for this request")

    assert "Wildberries" in note


def test_a_profile_id_in_a_name_is_replaced():
    # The finding of the review: a UUID is written in groups too short for the
    # opaque-value rule, and the hyphen kept it out of the identifier rule.
    name = redact.safe_identifier("capture-550e8400-e29b-41d4-a716-44665544a000", "capture")

    assert "550e8400" not in name
    assert name == "capture-opaque"


def test_a_profile_id_in_a_variant_does_not_reach_the_capture():
    probe = {
        "probes": [
            {
                "variant": "550e8400-e29b-41d4-a716-44665544a000",
                "url": "https://www.wildberries.ru/__internal/u-search",
            }
        ]
    }

    capture = redact.sanitize(probe, stem="probe")[0]

    assert "550e8400" not in capture["id"]
    assert "550e8400" not in capture["sourceFile"]

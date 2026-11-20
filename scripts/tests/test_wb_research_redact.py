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


def test_a_port_of_zero_survives():
    # The finding of the review: the port is part of the origin, and zero is a
    # port the parser reads — dropping it names another origin.
    url = redact.redact_url("https://www.wildberries.ru:0/catalog/0/list.aspx")

    assert url == "https://www.wildberries.ru:0/catalog/0/list.aspx"


def test_a_url_with_a_port_the_parser_refuses_is_replaced():
    # The finding of the review: a port out of range is a URL the parser
    # cannot read — dropping the port would name another endpoint.
    url = redact.redact_url("https://www.wildberries.ru:99999/api/search")

    assert url == "<redacted:url>"


def test_a_url_with_a_port_that_is_not_a_number_is_replaced():
    url = redact.redact_url("https://www.wildberries.ru:port/api/search")

    assert url == "<redacted:url>"


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


def test_an_encoded_slash_in_a_changed_segment_stays_encoded():
    # The finding of the review: a segment is judged decoded, and the value that
    # replaced it was written back with the separator among the safe characters.
    # A `%2F` is not a separator to a browser, so writing it back as `/` turns
    # one segment into two and names another endpoint.
    url = redact.redact_url("https://www.wildberries.ru/catalog/a%2Falice%40example.com")

    assert url == "https://www.wildberries.ru/catalog/a%2Femail"


def test_an_unchanged_segment_keeps_the_slash_it_came_with():
    url = redact.redact_url("https://www.wildberries.ru/catalog/a%2Fb")

    assert url == "https://www.wildberries.ru/catalog/a%2Fb"


def test_the_segments_of_a_path_keep_their_number():
    path = redact.redact_path("/catalog/a%2Falice%40example.com/reset")

    assert path.count("/") == 3


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


def test_an_encoded_address_in_a_name_is_replaced():
    # The finding of the review: the name was judged as it was written, and the
    # slug of an encoded address still spelled the address out.
    name = redact.safe_identifier("alice%40example.com", "capture")

    assert "alice" not in name
    assert "example.com" not in name


def test_a_twice_encoded_address_in_a_name_is_replaced():
    name = redact.safe_identifier("alice%2540example.com", "capture")

    assert "alice" not in name
    assert "example.com" not in name


def test_an_ordinary_name_keeps_its_spelling():
    name = redact.safe_identifier("initial-load", "capture")

    assert name == "initial-load"


def test_a_note_goes_through_the_same_rules():
    # A note is free text: without the rules it is the easiest way to smuggle a
    # value into a committed file.
    note = redact.redact_note("deviceid=site_9ee9449a3a1a448393d750b3dd2c22e8 for alice@example.com")

    assert "site_9ee9449a3a1a448393d750b3dd2c22e8" not in note
    assert "alice" not in note


def test_an_encoded_address_in_a_note_is_replaced():
    # The finding of the review: a note was judged as it was written, and the
    # address behind an escape reached the committed file.
    note = redact.redact_note("contact: alice%40example.com")

    assert "alice" not in note
    assert "example.com" not in note


def test_a_twice_encoded_address_in_a_note_is_replaced():
    note = redact.redact_note("contact: alice%2540example.com")

    assert "alice" not in note
    assert "example.com" not in note


def test_a_note_that_survives_the_rules_keeps_its_spelling():
    note = redact.redact_note("kept 8 of 312 recorded requests")

    assert note == "kept 8 of 312 recorded requests"


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
    url = redact.redact_url("https://www.wildberries.ru/search?find=555-123-4567&mail=alice@example.com")

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


def test_a_profile_id_in_an_ordinary_query_parameter_is_replaced():
    # The finding of the review: the UUID rule was applied to the names the
    # operator chose, but not to the values of the query — and braces keep the
    # opaque-value rule from seeing the identifier either.
    url = redact.redact_url("https://example.com/api?profile={550e8400-e29b-41d4-a716-44665544a000}")

    assert "550e8400" not in url


def test_a_profile_id_in_an_allowed_header_is_replaced():
    # The finding of the review: the name of a header says what may be shown,
    # not what the value carries.
    headers = redact.redact_headers(
        [{"name": "user-agent", "value": "550e8400-e29b-41d4-a716-44665544a000"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert "550e8400" not in headers["user-agent"]


def test_an_opaque_credential_in_an_allowed_header_is_replaced():
    # The finding of the review: a long opaque value in an allowed header was
    # kept, because nothing named it a credential.
    headers = redact.redact_headers(
        [{"name": "x-spa-version", "value": "9f8e7d6c5b4a39281706f5e4d3c2b1a0"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert "9f8e7d6c" not in headers["x-spa-version"]


def test_an_ordinary_user_agent_survives_an_allowed_header():
    # The counterpart of the finding: the rules must not throw the evidence away.
    headers = redact.redact_headers(
        [{"name": "user-agent", "value": "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert headers["user-agent"] == "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0"


def test_a_dotted_investigation_name_keeps_its_parts():
    # The finding of the review: a dotted name is a name, and replacing it with
    # `opaque` throws the evidence away.
    name = redact.safe_identifier("catalog.investigation.2026.11.19", "capture")

    assert name == "catalog.investigation.2026.11.19"


def test_a_token_inside_a_dotted_name_is_replaced():
    # The counterpart of the finding: the parts of the name that are a token do
    # not survive it.
    name = redact.safe_identifier("capture.eyJhbGciOiJIUzI1NiJ9abcdefghij", "capture")

    assert "abcdefghij" not in name
    assert name == "capture.opaque"


def test_a_profile_id_without_hyphens_is_replaced():
    # The finding of the review: the UUID rule knew one spelling of the
    # identifier, and the other spelling reached the committed file.
    url = redact.redact_url("https://example.com/api?profile=550e8400e29b41d4a71644665544a000")

    assert "550e8400" not in url


def test_a_profile_id_inside_a_larger_value_is_replaced():
    # The finding of the review: the identifier was recognized only when it was
    # the whole value, so a value that carried it among other words kept it.
    headers = redact.redact_headers(
        [{"name": "user-agent", "value": "Mozilla/5.0 profile=550e8400e29b41d4a71644665544a000"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert "550e8400" not in headers["user-agent"]


def test_a_credential_name_in_camel_case_is_replaced():
    # The finding of the review: the name of a credential is the same name in
    # every spelling, and the rule that knew the underscore spelling did not
    # know this one.
    url = redact.redact_url("https://example.com/api?accessToken=abc123")

    assert "abc123" not in url


def test_a_credential_name_with_a_hyphen_is_replaced():
    url = redact.redact_url("https://example.com/api?x-auth-token=abc123")

    assert "abc123" not in url


def test_a_credential_name_in_camel_case_keeps_the_value_out_of_a_note():
    note = redact.redact_note("failed request: clientSecret=xyz789")

    assert "xyz789" not in note


def test_a_dotted_name_in_an_ordinary_query_parameter_survives():
    # The finding of the review: the rule that keeps a dotted name was applied
    # to the names the operator chose, and not to the values.
    url = redact.redact_url("https://example.com/api?name=catalog.investigation.2026.11.19")

    assert "catalog.investigation.2026.11.19" in url


def test_a_dotted_name_in_an_allowed_header_survives():
    headers = redact.redact_headers(
        [{"name": "x-spa-version", "value": "catalog.investigation.2026.11.19"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert headers["x-spa-version"] == "catalog.investigation.2026.11.19"


def test_a_token_inside_a_dotted_value_is_replaced():
    # The counterpart of the finding: a name is kept, a token inside it is not.
    headers = redact.redact_headers(
        [{"name": "x-spa-version", "value": "capture.eyJhbGciOiJIUzI1NiJ9abcdefghij"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert "abcdefghij" not in headers["x-spa-version"]
    assert headers["x-spa-version"] == "capture.<redacted:opaque>"


def test_a_credential_named_auth_in_a_note_is_replaced():
    # The finding of the review: `auth` is a sensitive parameter, but the shared
    # grammar of the credential names did not know it — so `auth=abc123` in a note
    # reached the committed file.
    note = redact.redact_note("request failed: auth=abc123")

    assert "abc123" not in note


def test_a_credential_named_auth_in_an_allowed_header_is_replaced():
    headers = redact.redact_headers(
        [{"name": "user-agent", "value": "auth=abc123"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert "abc123" not in headers["user-agent"]


def test_the_short_credential_names_are_credentials():
    # The names the sanitizer treats as sensitive parameters are credential names
    # everywhere: a note is not a place where `otp=999111` is safe.
    for name in ("auth", "otp", "sid", "pwd", "_wbauid"):
        note = redact.redact_note(f"request failed: {name}=abc123")

        assert "abc123" not in note


def test_a_word_that_starts_like_a_credential_name_is_not_one():
    # The counterpart: the name has to be the whole name.
    note = redact.redact_note("author=Alice")

    assert "Alice" in note


def test_a_qualified_credential_name_is_a_credential():
    # The finding of the review: `auth_code` is a credential name with a
    # qualifier, and the grammar knew only the names without one.
    assert redact.redact_query_value("auth_code", "abc123") == "<redacted:query-param>"
    assert redact.redact_query_value("authCode", "abc123") == "<redacted:query-param>"
    assert redact.redact_query_value("otp_code", "123456") == "<redacted:query-param>"
    assert redact.redact_query_value("authorization_code", "xyz") == "<redacted:query-param>"


def test_a_qualified_credential_name_is_replaced_everywhere():
    note = redact.redact_note("callback failed: auth_code=abc123")
    headers = redact.redact_headers(
        [{"name": "user-agent", "value": "auth_code=abc123"}],
        redact.SAFE_REQUEST_HEADERS,
    )
    url = redact.redact_url("https://example.com/callback/auth_code/abc123")

    assert "abc123" not in note
    assert "abc123" not in headers["user-agent"]
    assert "abc123" not in url


def test_a_qualifier_alone_is_not_a_credential():
    # The counterpart: only a name that is already a credential takes one.
    # `code` is a word of the site, and `key_code` is a business term.
    assert redact.redact_query_value("code", "ABC123") == "ABC123"
    assert redact.redact_query_value("promo_code", "WB-42") == "WB-42"
    assert redact.redact_query_value("key_code", "ABC") == "ABC"
    assert redact.redact_query_value("token_type", "Bearer") == "Bearer"


def test_a_credential_name_with_any_qualifier_is_a_credential():
    # The finding of the review: the qualifier was one word, `code`, so a name
    # that carries another one was not a credential name at all.
    assert redact.redact_query_value("auth_code_id", "abc123") == "<redacted:query-param>"
    assert redact.redact_query_value("authCodeId", "abc123") == "<redacted:query-param>"
    assert redact.redact_query_value("auth_nonce", "abc123") == "<redacted:query-param>"
    assert redact.redact_query_value("otp_value", "123456") == "<redacted:query-param>"
    assert redact.redact_query_value("sid_hash", "abc123") == "<redacted:query-param>"


def test_a_credential_name_with_any_qualifier_is_replaced_everywhere():
    note = redact.redact_note("callback failed: auth_code_id=abc123")
    headers = redact.redact_headers(
        [{"name": "user-agent", "value": "auth_nonce=abc123"}],
        redact.SAFE_REQUEST_HEADERS,
    )
    url = redact.redact_url("https://example.com/callback/authCodeId/abc123")

    assert "abc123" not in note
    assert "abc123" not in headers["user-agent"]
    assert "abc123" not in url


def test_a_glued_qualifier_is_only_a_known_word():
    # The counterpart: a qualifier that is glued to the name cannot be told
    # from an ordinary word, so only a known one is a qualifier there.
    assert redact.redact_query_value("authcode", "abc123") == "<redacted:query-param>"
    assert redact.redact_query_value("sidebar", "Alice") == "Alice"
    assert redact.redact_query_value("authorship", "Alice") == "Alice"
    assert redact.redact_note("sidebar=abc123") == "sidebar=abc123"
    assert redact.redact_note("authors=Alice") == "authors=Alice"


def test_a_content_type_that_carries_a_url_keeps_only_the_media_type():
    # The finding of the review: the content type came from the server and was
    # copied as it was, while the same value in the headers went through the
    # rules. A server can answer with anything.
    har = {
        "log": {
            "entries": [
                {
                    "request": {"method": "GET", "url": "https://www.wildberries.ru/a", "headers": []},
                    "response": {
                        "status": 200,
                        "headers": [],
                        "content": {
                            "mimeType": "application/json; profile=https://example.com/?token=secret123",
                            "size": 10,
                        },
                    },
                }
            ]
        }
    }

    response = redact.sanitize(har, stem="capture")[0]["response"]

    assert response["contentType"] == "application/json"
    assert "secret123" not in json.dumps(response)


def test_a_content_type_that_is_not_a_media_type_is_dropped():
    assert redact.safe_content_type("not a type") is None
    assert redact.safe_content_type(None) is None


def test_a_credential_parameter_name_loses_its_value_whatever_the_separator():
    # The finding of the review: the suffix rule knew only the underscore, so
    # the same name written with a hyphen or a dot kept its value.
    url = redact.redact_url("https://example.com/api?access-token=abc123&refresh.token=xyz789")

    assert "abc123" not in url
    assert "xyz789" not in url


def test_a_credentials_mode_that_is_not_a_string_is_dropped():
    # The finding of the review: the modes are a set, and a value that is not
    # hashable raised instead of being dropped.
    assert redact.safe_credentials([]) is None
    assert redact.safe_credentials({}) is None


def test_a_credentials_mode_that_is_not_one_is_dropped():
    assert redact.safe_credentials("include") == "include"
    assert redact.safe_credentials("alice@example.com") is None


def test_a_duration_that_is_not_a_number_is_dropped():
    timing = redact.har_timing({"time": "session_token=secret123", "timings": {}})

    assert timing["durationMs"] is None
    assert "secret123" not in json.dumps(timing)


def test_a_field_of_the_capture_annotation_that_is_not_known_is_dropped():
    # The finding of the review: the annotation was copied field by field, and a
    # field nobody looked at could carry anything into the committed file.
    shape = redact.har_schema({"_capture": {"available": True, "note": "session_token=secret123"}})

    assert "note" not in shape
    assert "secret123" not in json.dumps(shape)


def test_a_flag_of_the_annotation_that_is_not_a_flag_is_dropped():
    # The finding of the review: the flags were read with `bool()`, so a string
    # "false" read as a flag that is set.
    shape = redact.har_schema({"_capture": {"available": "false", "isJson": "no"}})

    assert shape["available"] is None
    assert shape["isJson"] is None


def test_a_free_text_query_parameter_loses_its_value():
    # The finding of the review: what somebody typed is free text, and it can
    # be a person's name. The query of an investigation is written in the record
    # of the investigation, on purpose; the machine fixture carries the names of
    # the parameters only.
    url = redact.redact_url("https://www.wildberries.ru/search?query=Ivan%20Petrov&resultset=catalog")

    assert "Ivan" not in url
    assert "Petrov" not in url
    assert "query=%3Credacted%3Aquery-param%3E" in url
    assert "resultset=catalog" in url


def test_a_free_text_query_parameter_under_a_short_name_loses_its_value():
    # The finding of the review: the free-text names were three words, and a
    # service that spells the same thing `q` or `term` had its search text
    # committed as it was.
    url = redact.redact_url("https://www.wildberries.ru/search?q=Ivan%20Petrov&term=колонки")

    assert "Ivan" not in url
    assert "Petrov" not in url
    assert "колонки" not in url
    assert "q=%3Credacted%3Aquery-param%3E" in url
    assert "term=%3Credacted%3Aquery-param%3E" in url


def test_a_json_key_that_is_an_account_id_is_replaced():
    shape = redact.har_schema({"_capture": {"available": True, "topLevelKeys": ["1234567890"]}})

    assert shape["topLevelKeys"] == ["<redacted:key>"]
    assert "1234567890" not in json.dumps(shape)


def test_a_json_key_that_is_an_address_is_replaced():
    # The finding of the review: the top-level keys were copied from the answer
    # as they were, and a key can be the address itself.
    shape = redact.har_schema({"_capture": {"available": True, "topLevelKeys": ["alice@example.com", "products"]}})

    assert shape["topLevelKeys"] == ["<redacted:key>", "products"]
    assert "alice" not in json.dumps(shape)


def test_a_json_key_that_is_a_name_in_latin_is_replaced():
    assert redact.safe_key("Ivan Petrov") == "<redacted:key>"


def test_a_json_key_that_is_free_text_is_replaced():
    # The finding of the review: a key that survives the pattern-based rules is
    # not necessarily safe — a key that carries a space is a phrase, and a
    # phrase can be a person's name.
    shape = redact.har_schema({"_capture": {"available": True, "topLevelKeys": ["Иван Петров", "products"]}})

    assert shape["topLevelKeys"] == ["<redacted:key>", "products"]
    assert "Петров" not in json.dumps(shape)


def test_a_json_kind_that_is_not_a_kind_is_not_kept():
    shape = redact.har_schema({"_capture": {"available": True, "jsonType": "session_token=secret123"}})

    assert shape["jsonType"] is None
    assert "secret123" not in json.dumps(shape)


def test_a_known_reason_of_the_annotation_keeps_its_code():
    # The codes are the bridge between the capture and the redactor: a reason
    # that is one of them is the reason the capture recorded.
    shape = redact.har_schema({"_capture": {"available": False, "reason": "browser-error"}})

    assert shape["reason"] == redact.CAPTURE_REASONS["browser-error"]


def test_a_long_digit_run_in_a_query_value_is_replaced():
    # The finding of the review: a digit run in an ordinary parameter was kept
    # as it was, and the account the request was made from is a digit run
    # (`user_id=2285324551794928359`).
    url = redact.redact_url("https://www.wildberries.ru/api?dest=123456789012345")

    assert "123456789012345" not in url
    assert "dest=%3Credacted%3Aid%3E" in url


def test_a_mixed_account_id_after_an_account_path_component_is_replaced():
    url = redact.redact_url("https://www.wildberries.ru/accounts/ab-12345")

    assert "ab-12345" not in url
    assert "account-id" in url


def test_an_account_id_after_an_account_path_component_is_replaced():
    # The finding of the review: the digit-run rule needs nine digits, so a
    # short account id in the path reached the committed file as it was.
    url = redact.redact_url("https://www.wildberries.ru/users/12345")

    assert "12345" not in url
    assert "account-id" in url


def test_an_account_id_after_an_account_path_component_is_replaced_when_it_is_long():
    url = redact.redact_url("https://www.wildberries.ru/customer/987654321")

    assert "987654321" not in url
    assert "account-id" in url


def test_an_account_id_in_a_query_parameter_stays_an_id():
    # The rule is about a path segment: WB's own parameters carry long digit
    # runs, and they are ids, not phone numbers.
    url = redact.redact_url("https://www.wildberries.ru/search?dest=1259570991")

    assert "dest=1259570991" in url


def test_an_account_id_in_the_path_of_a_url_is_replaced():
    # The finding of the review: the path went through the address, the phone
    # number and the token rules, but not through the account-id rule — so a
    # digit run in a path segment reached the committed file as it was.
    url = redact.redact_url("https://www.wildberries.ru/users/1234567890")

    assert "1234567890" not in url
    assert "account-id" in url


def test_an_account_id_under_a_short_name_is_replaced():
    url = redact.redact_url("https://www.wildberries.ru/api?uid=2285324551794928359")

    assert "2285324551794928359" not in url
    assert "uid=%3Credacted%3Aquery-param%3E" in url


def test_an_address_in_an_allowed_header_value_is_replaced():
    # The finding of the review: the value of a header on the safe list went
    # through the credential rules only, so an address in a user agent reached
    # the committed file as it was.
    headers = redact.redact_headers(
        [{"name": "user-agent", "value": "Mozilla/5.0 alice@example.com"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert "alice@example.com" not in headers["user-agent"]
    assert "email" in headers["user-agent"]


def test_an_alphanumeric_account_id_after_an_account_path_component_is_replaced():
    # The finding of the review: the rule asked for digits, so an account id
    # that carries letters as well went into the committed file as it was.
    url = redact.redact_url("https://www.wildberries.ru/users/user12345")

    assert "user12345" not in url
    assert "account-id" in url


def test_a_negative_size_in_the_annotation_is_dropped():
    # The finding of the review: the size was copied without the validator the
    # other fields go through.
    shape = redact.har_schema({"_capture": {"available": True, "bodyBytes": -10}})

    assert shape["bodyBytes"] is None


def test_an_encoded_account_id_in_the_path_of_a_url_is_replaced():
    url = redact.redact_url("https://www.wildberries.ru/users/%31%32%33%34%35%36%37%38%39%30")

    assert "1234567890" not in url


def test_an_opaque_value_in_an_allowed_response_header_is_replaced():
    headers = redact.redact_headers(
        [{"name": "server", "value": "nginx-abcdef0123456789abcdef"}],
        redact.SAFE_RESPONSE_HEADERS,
    )

    assert "abcdef0123456789abcdef" not in headers["server"]


def test_a_long_digit_run_in_an_allowed_header_is_replaced():
    # The finding of the review: the value of an allowed header went through
    # the shape rules, but not through the digit rule — so an account id in a
    # user agent reached the committed file as it was.
    headers = redact.redact_headers(
        [{"name": "user-agent", "value": "Wildberries/1.0 2285324551794928359"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert "2285324551794928359" not in headers["user-agent"]
    assert "id" in headers["user-agent"]


def test_the_digit_run_of_the_site_stays_in_an_allowed_header():
    # The rule is about a run that is longer than the runs the site asks with:
    # a ten-digit run cannot be told apart from the site's own constants.
    headers = redact.redact_headers(
        [{"name": "user-agent", "value": "Wildberries/1.0 1259570991"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert "1259570991" in headers["user-agent"]


def test_an_ordinary_content_type_survives():
    assert redact.safe_content_type("text/plain; charset=UTF-8") == "text/plain; charset=UTF-8"


def test_an_ordinary_json_key_survives():
    shape = redact.har_schema({"_capture": {"available": True, "topLevelKeys": ["metadata", "products"]}})

    assert shape["topLevelKeys"] == ["metadata", "products"]


def test_an_ordinary_number_after_an_ordinary_path_component_survives():
    url = redact.redact_url("https://www.wildberries.ru/catalog/0/list.aspx")

    assert url == "https://www.wildberries.ru/catalog/0/list.aspx"


def test_an_ordinary_query_parameter_keeps_its_value_beside_a_free_text_one():
    url = redact.redact_url("https://www.wildberries.ru/search?query=колонки&spp=30")

    assert "spp=30" in url


def test_an_ordinary_query_parameter_name_survives():
    assert redact.safe_query_name("resultset") == "resultset"
    assert redact.safe_query_name("x-queryid") == "x-queryid"


def test_an_ordinary_query_parameter_name_survives_a_space_rule():
    # The rule is about a name that carries a space, not about every name.
    assert redact.safe_query_name("resultset") == "resultset"


def test_an_ordinary_timing_survives():
    timing = redact.har_timing({"time": 1139.99, "timings": {"wait": 445.8, "receive": 693.7}})

    assert timing == {"durationMs": 1139.99, "timings": {"wait": 445.8, "receive": 693.7}}


def test_an_ordinary_word_that_only_starts_like_a_credential_keeps_its_value():
    url = redact.redact_url("https://example.com/api?keyboard=qwerty&monkey=abc")

    assert "keyboard=qwerty" in url
    assert "monkey=abc" in url


def test_a_numeric_json_key_is_an_identifier():
    # A key made of digits only is an identifier: an answer indexed by an
    # account id carries the id in the key.
    assert redact.safe_key("42") == "<redacted:key>"


def test_a_pending_flag_that_is_not_a_flag_does_not_make_the_request_pending():
    # The finding of the review: the flag was read with `bool()`, so the
    # string "false" read as a request that was still in flight.
    entry = {
        "request": {"method": "GET", "url": "https://example.com/api"},
        "response": {"status": 200, "headers": [], "content": {"mimeType": "text/plain", "size": 1}},
        "_capture": {"pending": "false"},
    }

    capture = redact.capture_from_har_entry(entry, "c1", "step.har")

    assert capture["response"]["status"] == 200


def test_a_phone_number_in_an_allowed_header_value_is_replaced():
    headers = redact.redact_headers(
        [{"name": "x-cache-status", "value": "MISS +7 495 123 45 67"}],
        redact.SAFE_RESPONSE_HEADERS,
    )

    assert "495 123 45 67" not in headers["x-cache-status"]


def test_a_query_parameter_name_of_digits_only_is_replaced():
    # The finding of the review: a name of digits only is an identifier, the
    # way a key of digits is, and the digit rule needs nine digits to fire.
    url = redact.redact_url("https://example.com/api?12345=1")

    assert "12345" not in url
    assert "%3Credacted%3Aquery-name%3E" in url


def test_a_query_parameter_name_that_carries_a_space_is_replaced():
    # The finding of the review: a name that carries a space is a phrase, not
    # the name of a field — it is free text that the request is keyed by, and
    # a person's name is such a phrase.
    url = redact.redact_url("https://example.com/api?Alice Smith=1")

    assert "Alice" not in url
    assert "%3Credacted%3Aquery-name%3E" in url


def test_a_query_parameter_name_that_is_an_account_id_is_replaced():
    url = redact.redact_url("https://example.com/api?1234567890=1")

    assert "1234567890" not in url


def test_a_query_parameter_name_that_is_an_address_is_replaced():
    # The finding of the review: the name of a parameter was copied as it was,
    # and a name can be an address on the left of the `=`.
    url = redact.redact_url("https://www.wildberries.ru/api?alice%40example.com=1")

    assert "alice" not in url
    assert "example.com" not in url


def test_a_reason_of_the_annotation_that_is_not_a_code_is_replaced():
    # The finding of the review: the reason was copied as free text, and the
    # browser's own error text quotes the URL and the request.
    shape = redact.har_schema({"_capture": {"available": False, "reason": "CDP error: https://example.com/api"}})

    assert shape["reason"] == "<redacted:error>"
    assert "example.com" not in json.dumps(shape)


def test_a_route_after_an_account_path_component_survives():
    # A plain word after a segment that names an account is the name of a route
    # the service chose: `user/check` is an endpoint, not an account.
    url = redact.redact_url("https://data-checker.wildberries.ru/data-checker/api/v1/user/check")

    assert url == "https://data-checker.wildberries.ru/data-checker/api/v1/user/check"


def test_a_search_parameter_loses_its_value():
    url = redact.redact_url("https://www.wildberries.ru/search?search=колонки")

    assert "колонки" not in url
    assert "search=%3Credacted%3Aquery-param%3E" in url


def test_a_short_numeric_json_key_is_replaced():
    # The finding of the review: the digit-run rule needs nine digits, so a
    # short account id used as a key of the answer reached the committed file
    # as it was.
    shape = redact.har_schema({"_capture": {"available": True, "topLevelKeys": ["123456"]}})

    assert shape["topLevelKeys"] == ["<redacted:key>"]
    assert "123456" not in json.dumps(shape)


def test_a_status_that_is_not_an_http_status_is_dropped():
    assert redact.safe_status(200) == 200
    assert redact.safe_status(0) is None
    assert redact.safe_status("200") is None


def test_a_timestamp_that_is_not_a_timestamp_is_dropped():
    assert redact.safe_timestamp("2026-11-19T20:15:56.800173056Z") == "2026-11-19T20:15:56.800173056Z"
    assert redact.safe_timestamp("alice@example.com") is None


def test_a_timing_phase_that_is_not_a_number_is_dropped():
    # The finding of the review: the timings were copied as they were, and a
    # field nobody looked at could carry anything into the committed file.
    timing = redact.har_timing({"time": 1139.99, "timings": {"wait": "alice@example.com"}})

    assert timing["timings"] == {}
    assert "alice" not in json.dumps(timing)


def test_a_timing_phase_that_is_not_known_is_dropped():
    timing = redact.har_timing({"time": 1139.99, "timings": {"custom": 12, "wait": 445.8}})

    assert timing["timings"] == {"wait": 445.8}


def test_a_uuid_in_a_name_is_replaced():
    # The finding of the review: a UUID is an identifier, and the opaque-value
    # rule does not see it — the hyphens break the run of characters.
    name = redact.safe_identifier("probe-550e8400-e29b-41d4-a716-44665544abcd", "probe")

    assert name == "probe-opaque"
    assert "550e8400" not in name


def test_a_value_of_the_annotation_that_is_not_of_the_type_it_claims_is_dropped():
    shape = redact.har_schema({"_capture": {"available": True, "bodyBytes": "session_token=secret123"}})

    assert shape["bodyBytes"] is None
    assert "secret123" not in json.dumps(shape)


def test_every_code_of_the_reasons_is_a_reason_of_the_annotation():
    # The codes and the text of a reason belong together: a code that the
    # capture writes but the redactor does not know would read as an error.
    for code in redact.CAPTURE_REASONS:
        shape = redact.har_schema({"_capture": {"available": False, "reason": code}})

        assert shape["reason"] == redact.CAPTURE_REASONS[code]


def test_the_digit_run_of_the_catalog_the_page_is_about_stays():
    # The site's own constants are ten digits at the longest, and they are the
    # evidence: they say what the request asked for.
    url = redact.redact_url("https://www.wildberries.ru/api?dest=1259570991&hide_vflags=4294967296")

    assert "dest=1259570991" in url
    assert "hide_vflags=4294967296" in url


def test_the_fields_of_a_capture_are_validated_before_they_are_copied():
    # The finding of the review: the metadata of a capture was copied as it
    # came, and the capture is an input — a value could ride in a field nobody
    # looked at.
    har = {
        "log": {
            "entries": [
                {
                    "startedDateTime": "alice@example.com",
                    "request": {
                        "method": "session_token=secret123",
                        "url": "https://example.com/api",
                        "headers": [{"name": "alice@example.com", "value": "bob@example.com"}],
                    },
                    "response": {
                        "status": "carol@example.com",
                        "headers": [],
                        "content": {"mimeType": "application/json", "size": "dan@example.com"},
                    },
                }
            ]
        }
    }

    capture = redact.sanitize(har, stem="capture")[0]

    assert capture["started"] is None
    assert capture["request"]["method"] is None
    assert capture["response"]["status"] is None
    assert capture["response"]["bodyBytes"] is None
    assert "@" not in json.dumps(capture)


def test_the_fields_of_an_adapter_result_are_validated_before_they_are_copied():
    data = {
        "data": {
            "result": {
                "request": {
                    "method": "alice@example.com",
                    "url": "https://example.com/api",
                    "credentials": "bob@example.com",
                    "sentByBrowser": ["alice@example.com"],
                    "droppedByBrowser": ["carol@example.com"],
                },
                "response": {"status": "dan@example.com", "contentType": "application/json"},
            }
        }
    }

    capture = redact.capture_from_adapter(data, "adapter-001", "adapter")

    assert capture["request"]["method"] is None
    assert capture["request"]["credentials"] is None
    assert capture["request"]["headersSent"] == ["<redacted:header-name>"]
    assert capture["request"]["headersDroppedByBrowser"] == ["<redacted:header-name>"]
    assert capture["response"]["status"] is None
    assert "@" not in json.dumps(capture)


def test_the_fields_of_a_probe_are_validated_before_they_are_copied():
    probe = {
        "method": "alice@example.com",
        "url": "https://example.com/api",
        "credentials": "bob@example.com",
        "headerNames": ["alice@example.com", "content-type"],
        "status": "carol@example.com",
        "bodyBytes": "dan@example.com",
        "productCount": "eve@example.com",
        "contentType": "application/json",
        "isJson": "yes",
    }

    capture = redact.capture_from_probe(probe, "probe-001", "probe")

    assert capture["request"]["method"] is None
    assert capture["request"]["credentials"] is None
    assert capture["request"]["headersSent"] == ["<redacted:header-name>", "content-type"]
    assert capture["response"]["status"] is None
    assert capture["response"]["bodyBytes"] is None
    assert capture["response"]["isJson"] is None
    assert capture["schema"]["productCount"] is None
    assert "@" not in json.dumps(capture)


def test_the_product_count_of_an_adapter_result_is_a_count():
    data = {
        "data": {
            "result": {
                "response": {"shape": {"productCount": -1}},
            }
        }
    }

    capture = redact.capture_from_adapter(data, "adapter-001", "adapter")

    assert capture["schema"]["productCount"] is None


def test_the_product_count_of_an_adapter_result_is_not_a_fraction():
    data = {
        "data": {
            "result": {
                "response": {"shape": {"productCount": 1.5}},
            }
        }
    }

    capture = redact.capture_from_adapter(data, "adapter-001", "adapter")

    assert capture["schema"]["productCount"] is None


def test_the_method_of_a_request_survives():
    assert redact.safe_method("get") == "GET"
    assert redact.safe_method("PROPFIND") == "PROPFIND"


def test_the_shape_of_a_capture_is_only_the_fields_that_are_known():
    shape = redact.har_schema({"_capture": {"available": True, "isJson": True, "bodyBytes": 51715}})

    assert shape == {"available": True, "isJson": True, "bodyBytes": 51715}


def test_an_identifier_in_a_header_name_is_replaced():
    # The finding of the review: a name is a token, and a token can be an
    # identifier — a profile's UUID, an account id, a hash of a session.
    headers = redact.redact_headers(
        [
            {"name": "550e8400-e29b-41d4-a716-446655440000", "value": "x"},
            {"name": "2285324551794928359", "value": "y"},
            {"name": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", "value": "z"},
        ],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert list(headers) == ["<redacted:header-name>"]
    assert "550e8400" not in json.dumps(headers)
    assert "2285324551794928359" not in json.dumps(headers)
    assert "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" not in json.dumps(headers)


def test_an_identifier_among_the_headers_that_were_sent_is_replaced():
    # The same name in the list of the headers the browser sent.
    names = redact.safe_header_names(["550e8400-e29b-41d4-a716-446655440000", "content-type", "2285324551794928359"])

    assert names == ["<redacted:header-name>", "<redacted:header-name>", "content-type"]


def test_a_number_with_a_separator_in_a_header_name_is_replaced():
    # The finding of the review: a number written with a separator between its
    # parts is an account id in the shape the site writes ids in.
    assert redact.safe_header_name("123456789-01") == "<redacted:header-name>"
    assert redact.safe_header_name("123456789_01") == "<redacted:header-name>"


def test_the_name_of_a_header_that_the_service_chose_survives():
    # A name the service chose is made of words — long ones included.
    headers = redact.redact_headers(
        [{"name": "Sec-CH-UA-Platform-Version", "value": "x"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert headers["sec-ch-ua-platform-version"] == "<redacted:sec-ch-ua-platform-version>"


def test_an_ordinary_header_name_survives():
    # A name the service chose carries a word, and a word is not an identifier.
    assert redact.safe_header_name("x-spa-version") == "x-spa-version"
    assert redact.safe_header_name("x-userid") == "x-userid"


def test_a_number_that_is_not_finite_is_not_a_number():
    # The finding of the review: a malformed capture carries `NaN` and
    # `Infinity`, and a count of them is not evidence.
    assert redact.safe_size(json.loads('{"size": Infinity}')["size"]) is None
    assert redact.safe_status(json.loads('{"status": NaN}')["status"]) is None
    assert redact.safe_size(51715) == 51715
    assert redact.safe_status(200) == 200


def test_a_charset_that_carries_an_identifier_is_dropped():
    # The finding of the review: the value of the parameter was copied as it
    # was, and it can carry an account id.
    content_type = redact.safe_content_type("application/json; charset=1234567890123456789")

    assert content_type == "application/json"
    assert "1234567890123456789" not in content_type
    assert redact.safe_content_type("application/json; charset=utf-8") == "application/json; charset=utf-8"


def test_a_twice_encoded_address_in_a_query_value_is_replaced():
    # The finding of the review: the value was decoded once, so an address
    # behind two layers of escapes reached the fixture as it was.
    url = redact.redact_url("https://example.com/api?x=alice%2540example.com")

    assert "alice" not in url
    assert "@" not in url


def test_a_thrice_encoded_address_in_a_query_value_is_replaced():
    url = redact.redact_url("https://example.com/api?x=alice%252540example.com")

    assert "alice" not in url
    assert "@" not in url


def test_a_twice_encoded_credential_in_a_query_value_is_replaced():
    url = redact.redact_url("https://example.com/api?token=abc%252Fdef%252Bghi")

    assert "abc" not in url
    assert "def" not in url


def test_a_twice_encoded_name_of_a_sensitive_parameter_is_replaced():
    # The name is judged as the server reads it too: a name that hides a
    # credential behind two layers of escapes is the credential.
    url = redact.redact_url("https://example.com/api?%2561ccess%255Ftoken=1")

    assert "=1" not in url
    assert "%3Credacted%3Aquery-param%3E" in url


def test_a_twice_encoded_name_of_an_address_is_replaced():
    url = redact.redact_url("https://example.com/api?alice%2540example.com=1")

    assert "alice" not in url
    assert "%3Credacted%3Aquery-name%3E" in url


def test_a_twice_encoded_address_in_an_allowed_header_value_is_replaced():
    headers = redact.redact_headers(
        [{"name": "x-cache-status", "value": "MISS alice%2540example.com"}],
        redact.SAFE_RESPONSE_HEADERS,
    )

    assert "alice" not in headers["x-cache-status"]


def test_a_uuid_in_a_note_is_replaced():
    # The finding of the review: the note was judged by the rules, and the
    # opaque-value rule is what sees a UUID — it is a run of characters long
    # enough, hyphens and all.
    note = redact.redact_note("profile 550e8400-e29b-41d4-a716-446655440000")

    assert "550e8400" not in note


def test_a_uuid_in_a_path_segment_is_replaced():
    url = redact.redact_url("https://example.com/device/550e8400-e29b-41d4-a716-446655440000")

    assert "550e8400" not in url


def test_a_uuid_in_a_json_key_is_replaced():
    key = redact.safe_key("550e8400-e29b-41d4-a716-446655440000")

    assert key == "<redacted:key>"


def test_a_uuid_in_a_query_parameter_name_is_replaced():
    url = redact.redact_url("https://example.com/api?550e8400-e29b-41d4-a716-446655440000=1")

    assert "550e8400" not in url
    assert "%3Credacted%3Aquery-name%3E" in url


def test_a_status_with_a_fraction_in_it_is_not_a_status():
    # The finding of the review: the status was truncated, so a number that
    # nobody measured reached the committed file.
    assert redact.safe_status(200.9) is None
    assert redact.safe_status(200.0) == 200
    assert redact.safe_size(51715.8) is None
    assert redact.safe_size(51715.0) == 51715


def test_a_timing_phase_that_was_not_measured_is_dropped():
    # The finding of the review: the HAR writes `-1` for a phase it did not
    # measure, and a negative duration is not a measurement.
    timing = redact.har_timing({"time": -1, "timings": {"dns": -1, "connect": 12, "wait": 445.8}})

    assert timing["durationMs"] is None
    assert timing["timings"] == {"connect": 12, "wait": 445.8}


def test_a_json_key_that_hides_behind_escapes_is_still_a_key_to_judge():
    # The finding of the review: the key was judged as it was written, so an
    # address behind percent-escapes reached the committed file.
    assert redact.safe_key("alice%40example.com") == "<redacted:key>"
    assert redact.safe_key("alice%2540example.com") == "<redacted:key>"
    assert redact.safe_key("Ivan%20Petrov") == "<redacted:key>"
    assert redact.safe_key("42") == "<redacted:key>"


def test_a_json_key_that_survives_the_rules_keeps_its_spelling():
    assert redact.safe_key("userId") == "userId"
    assert redact.safe_key("a%2Db") == "a%2Db"


def test_a_timestamp_that_is_not_a_moment_is_not_a_timestamp():
    # The finding of the review: the shape of a timestamp is not a date — a
    # month of ninety-nine days is not a month.
    assert redact.safe_timestamp("2026-99-99T99:99:99Z") is None
    assert redact.safe_timestamp("2026-11-19T13:00:00+99:99") is None
    assert redact.safe_timestamp("2026-02-30T10:00:00Z") is None
    assert redact.safe_timestamp("2025-02-29T00:00:00Z") is None
    assert redact.safe_timestamp("2026-11-19T13:00:00Z") == "2026-11-19T13:00:00Z"
    assert redact.safe_timestamp("2024-02-29T13:00:00.123+03:00") == "2024-02-29T13:00:00.123+03:00"


def test_a_media_type_that_carries_an_identifier_is_not_a_media_type():
    # The finding of the review: the media type was judged by its shape
    # alone, so a UUID in the subtype reached the committed file.
    assert redact.safe_content_type("application/550e8400-e29b-41d4-a716-446655440000") is None
    assert redact.safe_content_type("application/123456789012345") is None
    assert redact.safe_content_type("application/json") == "application/json"
    assert redact.safe_content_type("text/html; charset=utf-8") == "text/html; charset=utf-8"


def test_a_path_keeps_the_spelling_of_a_segment_it_did_not_change():
    # The finding of the review: the path was decoded and put back encoded as
    # a whole, so a change in one segment rewrote the spelling of another.
    path = redact.redact_path("/catalog/a%2Fb/users/123456789012")

    assert path == "/catalog/a%2Fb/users/%3Credacted:account-id%3E"
    assert redact.redact_path("/catalog/a%2Fb") == "/catalog/a%2Fb"
    assert redact.redact_path("/data-checker/api/v1/user/check") == "/data-checker/api/v1/user/check"


def test_a_media_type_with_a_numeric_subtype_is_not_a_media_type():
    # The finding of the review: the digit-run rule needs eleven digits, so a
    # shorter numeric subtype survived — and no service names a media type
    # that way.
    assert redact.safe_content_type("application/1234567890") is None
    assert redact.safe_content_type("application/123456") is None
    assert redact.safe_content_type("application/1") is None
    assert redact.safe_content_type("application/json") == "application/json"


def test_a_url_the_parser_cannot_read_is_replaced_whole():
    # The finding of the review: `urlsplit` refuses a URL with an unclosed
    # IPv6 bracket, and the refusal reached the caller as an exception — the
    # export of the whole step died on a value the redactor could not judge.
    # Where the user information ends and the path begins is unknown in such a
    # URL, and a URL that cannot be judged must not be guessed at.
    assert redact.redact_url("https://[::1/api") == "<redacted:url>"


def test_a_malformed_url_does_not_end_the_export():
    # The same refusal, reached from every place a URL can come from: a note,
    # a header value, a query value and a query name.
    assert redact.redact_note("see https://[::1/api for details") == "see <redacted:url> for details"
    headers = redact.redact_headers(
        [{"name": "referer", "value": "https://[::1/api"}],
        redact.SAFE_REQUEST_HEADERS,
    )
    assert headers["referer"] == "<redacted:url>"
    assert redact.redact_query_value("u", "https://[::1/api") == "<redacted:url>"
    assert redact.safe_query_name("https://[::1/api") == "<redacted:query-name>"


def test_a_capture_with_a_malformed_url_is_still_sanitized():
    document = json.loads(json.dumps(HAR))
    document["log"]["entries"][0]["request"]["url"] = "https://[::1/api"

    capture = redact.sanitize(document, stem="capture")[0]

    assert capture["request"]["url"] == "<redacted:url>"


def test_a_character_no_file_can_hold_is_replaced():
    # A capture can carry a lone surrogate: it is not evidence, and a file
    # that cannot be written is a failed export.
    assert redact.writable("a\ud800b") == "a?b"
    assert "\ud800" not in redact.redact_url("https://www.wildberries.ru/a\ud800b")


def test_a_header_name_that_hides_an_address_behind_escapes_is_not_a_name():
    # The finding of the review: the name of a header was judged as it was
    # written, so an address behind one layer of escapes (`%40` is `@`) was a
    # name the service chose. The name is judged as the server reads it.
    assert redact.safe_header_name("alice%40example.com") == "<redacted:header-name>"
    assert redact.safe_header_name("alice%2540example.com") == "<redacted:header-name>"
    headers = redact.redact_headers(
        [{"name": "alice%40example.com", "value": "x"}],
        redact.SAFE_REQUEST_HEADERS,
    )
    assert "alice%40example.com" not in json.dumps(headers)
    assert redact.safe_header_names(["alice%40example.com", "x-requested-with"]) == [
        "<redacted:header-name>",
        "x-requested-with",
    ]


def test_a_header_name_that_survives_the_rules_keeps_its_spelling():
    # The name is judged decoded, but a name the rules did not change keeps
    # the spelling it came with: a change in one place must not rewrite
    # another.
    assert redact.safe_header_name("x%2duser") == "x%2duser"
    assert redact.safe_header_name("referer") == "referer"
    assert redact.safe_header_name("sec-ch-ua-platform-version") == "sec-ch-ua-platform-version"


def test_a_value_with_a_long_run_inside_it_is_redacted_instead_of_raising():
    # The finding of the review: the rule that judges a run of opaque
    # characters inside a value was handed the match of the rule instead of
    # the text of the run, so a value that carried one ended the redaction.
    value = "Bearer abcdefghijklmnopqrstuvwxyz0123"

    redacted = redact.redact_generic_value(value)

    assert "abcdefghijklmnopqrstuvwxyz0123" not in redacted
    assert "<redacted:opaque>" in redacted


def test_a_header_value_with_a_long_run_inside_it_is_redacted():
    headers = redact.redact_headers(
        [{"name": "user-agent", "value": "agent abcdefghijklmnopqrstuvwxyz0123"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert "abcdefghijklmnopqrstuvwxyz0123" not in json.dumps(headers)


def test_a_content_type_header_keeps_the_media_type_and_the_safe_parameters_only():
    # The finding of the review: the content type of a header was judged by
    # the generic rules, so a parameter of the server's own choosing survived
    # in the committed file.
    headers = redact.redact_headers(
        [{"name": "content-type", "value": "multipart/form-data; boundary=abc123"}],
        redact.SAFE_RESPONSE_HEADERS,
    )

    assert headers["content-type"] == "multipart/form-data"


def test_a_content_type_header_of_a_request_is_judged_by_the_same_rule():
    headers = redact.redact_headers(
        [{"name": "content-type", "value": "application/json; charset=utf-8"}],
        redact.SAFE_REQUEST_HEADERS,
    )

    assert headers["content-type"] == "application/json; charset=utf-8"


def test_a_content_type_header_that_is_not_a_content_type_loses_its_value():
    headers = redact.redact_headers(
        [{"name": "content-type", "value": "not a type"}],
        redact.SAFE_RESPONSE_HEADERS,
    )

    assert headers["content-type"] == "<redacted:content-type>"


def test_a_count_that_cannot_be_a_count_is_not_kept():
    # The finding of the review: a count was judged as a number, so a
    # negative count and a count with a fraction in it were kept as evidence
    # of what was seen.
    measurements = redact.redact_measurements({"total": -1, "productCount": 2.7, "productsWithPoints": 0})

    assert measurements == {"productsWithPoints": 0}

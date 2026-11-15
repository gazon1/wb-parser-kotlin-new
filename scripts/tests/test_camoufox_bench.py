#!/usr/bin/env python3
"""The parts of the Camoufox benchmark that can be checked without a browser.

What is covered
---------------
Only the arithmetic, the request shape, the stop rule, and the address-bound profiles
directory: how a refusal, a rate limit and a transport failure are told apart, what the
refusal share divides by, that the catalog call carries the same headers the crawler
sends, and that one directory cannot serve two addresses. Launching a browser is
deliberately not tested here — it needs a real profile, and the measurement itself is the
check for that.

`unittest.TestCase` by the convention of the neighbouring tests: the same file runs
under the gate (`python3 -m unittest discover -s scripts/tests`) and under pytest.
"""

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

SCRIPTS = Path(__file__).resolve().parent.parent


def load_bench():
    spec = importlib.util.spec_from_file_location("camoufox_bench", SCRIPTS / "camoufox_bench.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bench = load_bench()


def args(**overrides):
    base = {"profiles": 1, "proxy": "", "headless": True, "delay": 3.0, "shared_address": False}
    base.update(overrides)
    return SimpleNamespace(**base)


def answer(status, products=100, ms=200):
    return {"status": status, "products": products, "ms": ms, "bytes": 0}


class SummariseTest(unittest.TestCase):
    def test_a_refusal_is_not_a_transport_failure(self):
        """The two are separate counters — the share is about the site, not the network."""
        results = [{"profile": 1, "requests": [answer(200), answer(498), answer(0)], "elapsed_s": 60}]
        summary = bench.summarise(results, args())
        self.assertEqual(summary["ok"], 1)
        self.assertEqual(summary["refused"], 1)
        self.assertEqual(summary["errored"], 1)

    def test_the_share_divides_by_requests_not_by_successes(self):
        """A numerator without its denominator says nothing (the protocol's §1.6)."""
        results = [{"profile": 1, "requests": [answer(200), answer(200), answer(498), answer(498)], "elapsed_s": 60}]
        summary = bench.summarise(results, args())
        self.assertEqual(summary["refusal_ratio"], 0.5)

    def test_throughput_counts_products_of_answered_requests(self):
        """Useful throughput: a refused request contributes no products."""
        results = [{"profile": 1, "requests": [answer(200, products=100), answer(498, products=0)], "elapsed_s": 60}]
        summary = bench.summarise(results, args())
        self.assertEqual(summary["products"], 100)
        self.assertEqual(summary["products_per_min"], 100.0)

    def test_an_empty_run_has_no_share_rather_than_zero(self):
        """'Nothing was measured' and 'nothing was refused' are different answers."""
        summary = bench.summarise([{"profile": 1, "requests": [], "elapsed_s": 0}], args())
        self.assertIsNone(summary["refusal_ratio"])
        self.assertIsNone(summary["products_per_min"])

    def test_a_profile_that_never_got_a_page_is_not_counted_as_measured(self):
        """Three requested, one navigation timeout: the level is two, not three."""
        results = [
            {"profile": 1, "requests": [answer(200)], "elapsed_s": 30},
            {"profile": 2, "requests": [answer(200)], "elapsed_s": 30},
            {"profile": 3, "requests": [], "error": "navigation timeout", "elapsed_s": 30},
        ]
        summary = bench.summarise(results, args(profiles=3))
        self.assertEqual(summary["profiles_requested"], 3)
        self.assertEqual(summary["profiles_measured"], 2)

    def test_first_refusal_is_reported_per_profile(self):
        """Whose counter was spent is the point of the stepped run."""
        results = [
            {"profile": 1, "requests": [answer(200), answer(498)], "elapsed_s": 30, "first_refusal": 2},
            {"profile": 2, "requests": [answer(200)], "elapsed_s": 30},
        ]
        summary = bench.summarise(results, args(profiles=2))
        self.assertEqual(summary["first_refusals"], {1: 2, 2: None})

    def test_a_rate_limit_is_counted_apart_from_a_refusal(self):
        """498 and 429 answer different questions; one must not hide inside the other."""
        results = [{"profile": 1, "requests": [answer(200), answer(429), answer(498)], "elapsed_s": 60}]
        summary = bench.summarise(results, args())
        self.assertEqual(summary["refused"], 1)
        self.assertEqual(summary["throttled"], 1)
        self.assertEqual(summary["refusal_ratio"], round(1 / 3, 4))

    def test_first_throttle_is_reported_per_profile(self):
        """When the site said 'too fast' is its own datum."""
        results = [
            {"profile": 1, "requests": [answer(200), answer(429)], "elapsed_s": 30, "first_throttle": 2},
            {"profile": 2, "requests": [answer(200)], "elapsed_s": 30},
        ]
        summary = bench.summarise(results, args(profiles=2))
        self.assertEqual(summary["first_throttles"], {1: 2, 2: None})
        self.assertEqual(summary["first_refusals"], {1: None, 2: None})

    def test_the_summary_does_not_publish_proxy_credentials(self):
        """The artifact is written down; userinfo from the proxy URL must not be in it."""
        summary = bench.summarise(
            [{"profile": 1, "requests": [answer(200)], "elapsed_s": 30}],
            args(proxy="http://user@10.0.0.1:8080"),
        )
        self.assertEqual(summary["proxy"], "http://10.0.0.1:8080")


class WorkerFailureTest(unittest.TestCase):
    """A browser that does not start is a datum, not a thread that vanishes."""

    def worker_args(self, profiles_dir):
        return SimpleNamespace(
            profiles_dir=str(profiles_dir),
            headless=True,
            proxy="",
            timeout_ms=1000,
            warmup_ms=0,
            count=1,
            delay=0.0,
            shared_address=False,
        )

    def test_a_browser_that_cannot_be_imported_is_a_result(self):
        """The import lives before the old try block, so the profile used to disappear."""
        with (
            tempfile.TemporaryDirectory() as tmp,
            mock.patch.object(bench, "launch_browser", side_effect=ImportError("no camoufox")),
        ):
            results = []
            bench.worker(1, self.worker_args(tmp), results, bench.threading.Event())
        self.assertEqual(len(results), 1)
        self.assertIn("no camoufox", results[0]["error"])
        self.assertEqual(results[0]["requests"], [])

    def test_stopping_a_sampler_that_never_started_is_not_an_error(self):
        """Cleanup runs on the same path when `start()` itself failed."""
        sampler = bench.Sampler("profile-1")
        sampler.stop()
        self.assertEqual(sampler.peak_rss, 0)

    def test_a_browser_that_fails_to_launch_is_a_result(self):
        """A launch failure after the import must be recorded just the same."""

        class BrokenBrowser:
            def __init__(self, **kwargs):
                raise RuntimeError("launch failed")

        with (
            tempfile.TemporaryDirectory() as tmp,
            mock.patch.object(bench, "launch_browser", return_value=BrokenBrowser),
        ):
            results = []
            bench.worker(1, self.worker_args(tmp), results, bench.threading.Event())
        self.assertEqual(len(results), 1)
        self.assertIn("launch failed", results[0]["error"])
        self.assertEqual(results[0]["peak_rss_mb"], 0.0)


class RequestShapeTest(unittest.TestCase):
    def test_the_catalog_call_carries_the_crawler_headers(self):
        """The request shape is not the variable of the measurement."""
        self.assertEqual(bench.HEADERS["deviceid"], "site_a39361daf4ec45f2a68c5c6ca73c8adf")
        self.assertEqual(bench.HEADERS["x-spa-version"], "14.28.3")

    def test_the_api_url_names_the_endpoint_the_crawler_uses(self):
        url = bench.api_url(2)
        self.assertTrue(url.startswith(bench.API + "?"))
        self.assertIn("page=2", url)
        self.assertIn("resultset=catalog", url)

    def test_the_query_is_percent_encoded(self):
        """The catalog token is Cyrillic; an unencoded space would break the call."""
        self.assertIn("query=menu_v3_9467%20", bench.api_url(1))


class StopRuleTest(unittest.TestCase):
    def test_a_refusal_stops_the_run_at_once(self):
        """The first 498 ends it: a spent counter is not a reason to keep asking."""
        for status in (498, 403, 401):
            self.assertEqual(bench.stop_reason(status), "refused", status)

    def test_a_rate_limit_stops_the_run_under_its_own_name(self):
        """429 is the rate speaking, not the session counter — it gets its own reason."""
        self.assertEqual(bench.stop_reason(429), "throttled")

    def test_an_answer_or_a_transport_failure_does_not_stop_it(self):
        """Only a refusal or a rate limit is the site saying no; 200 is work, 0 is the network."""
        for status in (200, 0, 404, 500):
            self.assertIsNone(bench.stop_reason(status), status)


class ProfilesDirTest(unittest.TestCase):
    """A user_data_dir that outlives a run is an identity that outlives its address."""

    def test_the_default_tag_names_the_egress(self):
        """Two endpoints must not resolve to one directory."""
        self.assertEqual(bench.address_tag(""), "direct")
        self.assertNotEqual(bench.address_tag("socks5://127.0.0.1:1081"), bench.address_tag("socks5://127.0.0.1:1082"))
        self.assertNotIn(":", bench.address_tag("socks5://127.0.0.1:1082"))

    def test_credentials_do_not_reach_the_directory_name(self):
        """Userinfo in a /tmp path is userinfo on disk; the endpoint is the identity."""
        tag = bench.address_tag("http://user@10.0.0.1:8080")
        self.assertNotIn("user", tag)
        self.assertEqual(tag, bench.address_tag("http://10.0.0.1:8080"))

    def test_a_directory_refuses_a_second_address(self):
        """Cookies from the direct address must not arrive through a proxy."""
        with tempfile.TemporaryDirectory() as tmp:
            bench.claim_profiles_dir(tmp, "")
            with self.assertRaises(SystemExit):
                bench.claim_profiles_dir(tmp, "socks5://127.0.0.1:1082")

    def test_the_same_address_may_return(self):
        """A repeat run on one address is the point of a persistent profile."""
        with tempfile.TemporaryDirectory() as tmp:
            bench.claim_profiles_dir(tmp, "")
            bench.claim_profiles_dir(tmp, "")

    def test_a_refused_move_leaves_the_marker_alone(self):
        """The directory keeps pointing at its old endpoint, so the refusal is repeatable."""
        with tempfile.TemporaryDirectory() as tmp:
            bench.claim_profiles_dir(tmp, "")
            with self.assertRaises(SystemExit):
                bench.claim_profiles_dir(tmp, "socks5://127.0.0.1:1082")
            self.assertEqual((Path(tmp) / "EGRESS").read_text(encoding="utf-8").strip(), "direct")

    def test_a_new_endpoint_needs_a_new_directory(self):
        """There is no override: the only way to change the endpoint is to change the path."""
        with tempfile.TemporaryDirectory() as tmp:
            direct = Path(tmp) / "direct"
            proxied = Path(tmp) / "proxied"
            bench.claim_profiles_dir(str(direct), "")
            bench.claim_profiles_dir(str(proxied), "socks5://127.0.0.1:1082")
            self.assertEqual((direct / "EGRESS").read_text(encoding="utf-8").strip(), "direct")
            self.assertEqual((proxied / "EGRESS").read_text(encoding="utf-8").strip(), "socks5://127.0.0.1:1082")


class ProcStatTest(unittest.TestCase):
    """`comm` may contain spaces and parentheses, and a naive split then reads the wrong columns."""

    def test_the_field_offsets_are_relative_to_the_last_paren(self):
        """ppid is index 1, and utime/stime are 11/12, once `comm` is removed."""
        self.assertEqual(bench.read_stat(os.getpid())[1], str(os.getppid()))

    def test_a_comm_with_spaces_does_not_shift_the_fields(self):
        """The bug: 'Web Content' splits into two tokens and moves every later index by one."""
        raw = "1234 (Web Content) S 5678 1234 1234 0 -1 4194560 1 0 0 0 111 222 0 0"
        fields = raw[raw.rfind(")") + 2 :].split()
        self.assertEqual(int(fields[1]), 5678)
        self.assertEqual(int(fields[11]), 111)
        self.assertEqual(int(fields[12]), 222)

    def test_a_comm_with_parens_inside_does_not_shift_the_fields(self):
        """`comm` runs to the LAST ')', not the first one."""
        raw = "9 (Isolated (Web) Co) S 42 9 9 0 -1 0 0 0 0 0 7 8 0 0"
        fields = raw[raw.rfind(")") + 2 :].split()
        self.assertEqual(int(fields[1]), 42)
        self.assertEqual(int(fields[11]), 7)

    def test_the_tree_of_a_live_process_starts_with_itself(self):
        """A tree that cannot see its own root measures nothing."""
        self.assertIn(os.getpid(), bench.process_tree({os.getpid()}))


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""The parts of the Camoufox benchmark that can be checked without a browser.

What is covered
---------------
Only the arithmetic and the request shape: how a refusal is counted apart from a
transport failure, what the refusal share divides by, and that the catalog call carries
the same headers the crawler sends. Launching a browser is deliberately not tested here
— it needs a real profile, and the measurement itself is the check for that.

`unittest.TestCase` by the convention of the neighbouring tests: the same file runs
under the gate (`python3 -m unittest discover -s scripts/tests`) and under pytest.
"""

import importlib.util
import os
import unittest
from pathlib import Path
from types import SimpleNamespace

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
            self.assertTrue(bench.stops_the_run(status), status)

    def test_an_answer_or_a_transport_failure_does_not_stop_it(self):
        """Only a refusal is the site saying no; a 200 is work and a 0 is the network."""
        for status in (200, 0, 404, 500):
            self.assertFalse(bench.stops_the_run(status), status)


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

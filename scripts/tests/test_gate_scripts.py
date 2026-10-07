#!/usr/bin/env python3
"""Unit tests for the gate scripts themselves.

Why the gate scripts need their own tests
-----------------------------------------
A gate is a program that is supposed to fail. Almost nothing else in the
repository tests whether the failure paths work, so a gate with a broken
predicate is indistinguishable from a gate that has nothing to find — and it
reports success the entire time. That is the same defect this whole change
exists to prevent, one level up.

What is covered here is the machinery, not the policy: the parsers, the XML
handling, the counting. The policy itself is checked by each script's
`--self-test`, which the static registry runs on every invocation.
"""

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent


def load(name: str, filename: str):
    """Import a gate script by path.

    These scripts are not a package and are named with dashes, so they cannot be
    imported normally. `spec_from_file_location` keeps the import honest without
    renaming files or adding an `__init__.py` that would imply one.
    """
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    assert spec and spec.loader, f"cannot load {filename}"
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


BASELINE = load("baseline_ratchet", "check-baseline-ratchet.py")
TEST_RUNS = load("test_runs", "check-test-runs.py")


def write(tmp: str, name: str, content: str) -> Path:
    path = Path(tmp) / name
    path.write_text(content, encoding="utf-8")
    return path


class TestDiscoveryIsNotVacuous(unittest.TestCase):
    """`python3 -m unittest discover` exits 0 even when it finds no tests.

    A file without `unittest.main()` is imported, defines nothing, and the run
    succeeds with an empty suite. The gate that runs this suite would then report
    success having verified nothing — the exact failure mode this change is
    about, reproduced inside the fix for it.

    So this asserts that discovery finds tests, rather than trusting the exit
    code that `static-gates.sh` uses.
    """

    def test_discovery_finds_this_suite(self):
        loader = unittest.TestLoader()
        suite = loader.discover(str(SCRIPTS / "tests"), pattern="test_*.py")
        self.assertGreater(
            suite.countTestCases(),
            0,
            "unittest discovery found no tests; the gate that runs them would pass vacuously",
        )

    def test_this_file_declares_a_main(self):
        # Without this, the file above contributes zero tests and discovery
        # succeeds anyway — which is the trap.
        self.assertIn("unittest.main()", Path(__file__).read_text(encoding="utf-8"))


class TestBaselineParsing(unittest.TestCase):
    def test_valid_baseline_parses(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(
                tmp,
                "detekt-baseline.xml",
                '<?xml version="1.0" ?>\n'
                "<SmellBaseline>\n"
                "  <CurrentIssues>\n"
                "    <ID>LongParameterList:A.kt$A$( x: Int)</ID>\n"
                "  </CurrentIssues>\n"
                "</SmellBaseline>\n",
            )
            self.assertEqual(BASELINE.parse_baseline(path).count, 1)

    def test_space_in_element_name_is_rejected(self):
        """The exact defect this repository shipped: `<Current issues />`.

        A regex would not notice — it looks like a plausible tag. detekt does not
        accept it, so the file suppresses nothing while looking like it does.
        """
        with tempfile.TemporaryDirectory() as tmp:
            path = write(
                tmp,
                "detekt-baseline.xml",
                '<?xml version="1.0" ?>\n'
                "<SmellBaseline>\n"
                "  <Current issues />\n"
                "</SmellBaseline>\n",
            )
            with self.assertRaises(ValueError):
                BASELINE.parse_baseline(path)

    def test_empty_current_issues_is_zero_not_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(
                tmp,
                "detekt-baseline.xml",
                '<?xml version="1.0" ?>\n'
                "<SmellBaseline>\n"
                "  <CurrentIssues></CurrentIssues>\n"
                "</SmellBaseline>\n",
            )
            self.assertEqual(BASELINE.parse_baseline(path).count, 0)

    def test_wrong_root_element_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(
                tmp,
                "detekt-baseline.xml",
                '<?xml version="1.0" ?>\n<Baselines><CurrentIssues/></Baselines>\n',
            )
            with self.assertRaises(ValueError) as ctx:
                BASELINE.parse_baseline(path)
            self.assertIn("SmellBaseline", str(ctx.exception))

    def test_id_regex_counts_entries_not_lines(self):
        """A wrapped signature must count as one entry.

        Counting lines would break the moment detekt reformats a long `<ID>`,
        which would turn the ratchet into noise.
        """
        source = "<CurrentIssues>\n    <ID>A:B.kt$C$( a: Int,\n b: Int)</ID>\n  </CurrentIssues>"
        self.assertEqual(len(BASELINE._ID_RE.findall(source)), 1)


class TestFloorFileParsing(unittest.TestCase):
    def test_parses_and_skips_comments(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(
                tmp,
                "floor.txt",
                "# comment\n\ndomain 196  # inline comment\ninfrastructure 0\n",
            )
            self.assertEqual(TEST_RUNS.parse_floor_file(path), {"domain": 196, "infrastructure": 0})

    def test_rejects_row_without_a_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "floor.txt", "domain\n")
            with self.assertRaises(ValueError):
                TEST_RUNS.parse_floor_file(path)

    def test_rejects_non_numeric_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "floor.txt", "domain many\n")
            with self.assertRaises(ValueError):
                TEST_RUNS.parse_floor_file(path)

    def test_missing_file_raises_rather_than_returning_empty(self):
        # An empty floor map would make every module report "no floor recorded"
        # and fail loudly — but a *silently* empty map that reads as "nothing to
        # check" is the failure mode this avoids.
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(FileNotFoundError):
                TEST_RUNS.parse_floor_file(Path(tmp) / "absent.txt")


class TestJUnitCounting(unittest.TestCase):
    def test_counts_testcases_in_nested_suites(self):
        with tempfile.TemporaryDirectory() as tmp:
            results = Path(tmp) / "test"
            results.mkdir()
            (results / "TEST-one.xml").write_text(
                '<?xml version="1.0" ?>\n'
                '<testsuite name="one" tests="2">\n'
                '  <testcase name="a"/>\n'
                '  <testcase name="b"/>\n'
                "</testsuite>\n",
                encoding="utf-8",
            )
            self.assertEqual(TEST_RUNS.count_in_dir(results), (2, 1))

    def test_sums_across_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            results = Path(tmp) / "test"
            results.mkdir()
            for index in range(3):
                (results / f"TEST-{index}.xml").write_text(
                    '<?xml version="1.0" ?>\n'
                    '<testsuite name="s">\n  <testcase name="a"/>\n</testsuite>\n',
                    encoding="utf-8",
                )
            self.assertEqual(TEST_RUNS.count_in_dir(results), (3, 3))

    def test_missing_directory_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(FileNotFoundError):
                TEST_RUNS.count_in_dir(Path(tmp) / "absent")

    def test_directory_without_xml_raises(self):
        """An empty results directory is not "zero tests" — it is no run."""
        with tempfile.TemporaryDirectory() as tmp:
            results = Path(tmp) / "test"
            results.mkdir()
            with self.assertRaises(ValueError):
                TEST_RUNS.count_in_dir(results)

    def test_malformed_xml_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            results = Path(tmp) / "test"
            results.mkdir()
            (results / "TEST-broken.xml").write_text("<testsuite", encoding="utf-8")
            with self.assertRaises(ValueError):
                TEST_RUNS.count_in_dir(results)


if __name__ == "__main__":
    unittest.main()
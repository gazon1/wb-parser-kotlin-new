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
import shutil
import subprocess
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
COVERAGE = load("coverage", "check-coverage.py")


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


class TestCoverageFloorParsing(unittest.TestCase):
    def test_parses_and_skips_comments(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "floor.txt", "# comment\n\napp 25  # inline comment\nwbparser 50\n")
            self.assertEqual(COVERAGE.parse_floor_file(path), {"app": 25, "wbparser": 50})

    def test_rejects_row_without_a_percentage(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "floor.txt", "wbparser\n")
            with self.assertRaises(ValueError):
                COVERAGE.parse_floor_file(path)

    def test_rejects_non_numeric_percentage(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "floor.txt", "wbparser most\n")
            with self.assertRaises(ValueError):
                COVERAGE.parse_floor_file(path)

    def test_rejects_percentage_out_of_range(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "floor.txt", "wbparser 101\n")
            with self.assertRaises(ValueError):
                COVERAGE.parse_floor_file(path)

    def test_missing_file_raises_rather_than_returning_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(FileNotFoundError):
                COVERAGE.parse_floor_file(Path(tmp) / "absent.txt")


class TestCoverageReportParsing(unittest.TestCase):
    def test_reads_the_line_counter(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "report.xml", _kover_report(covered=50, missed=50))
            self.assertAlmostEqual(COVERAGE.line_coverage(path), 50.0)

    def test_rejects_a_report_without_a_line_counter(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "report.xml", '<?xml version="1.0" ?>\n<report/>\n')
            with self.assertRaises(ValueError):
                COVERAGE.line_coverage(path)

    def test_rejects_malformed_xml(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "report.xml", "<report")
            with self.assertRaises(ValueError):
                COVERAGE.line_coverage(path)

    def test_rejects_a_report_without_any_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "report.xml", _kover_report(covered=0, missed=0))
            with self.assertRaises(ValueError):
                COVERAGE.line_coverage(path)


class TestPackageFilteredCoverage(unittest.TestCase):
    """A module's row is measured over that module's packages in the merged report.

    The whole point of the change is that the app's row must not be measured on the
    app module's own report — that report cannot see the integration module's suites,
    and it reported thirty-five covered classes as untested. So the filter has to
    work, and it has to exclude the other module's packages.
    """

    def test_measures_only_the_asked_for_package(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "report.xml", _two_package_report())
            coverage = COVERAGE.line_coverage(path, packages=("ru/wbparser/app",))
            self.assertAlmostEqual(coverage, 80.0)

    def test_the_other_package_is_not_counted(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "report.xml", _two_package_report())
            coverage = COVERAGE.line_coverage(path, packages=("ru/wbparser",))
            # Both packages are under `ru/wbparser`; the crawler's row excludes the app's.
            self.assertAlmostEqual(coverage, 60.0)
            coverage = COVERAGE.line_coverage(
                path,
                packages=("ru/wbparser",),
                excluded=("ru/wbparser/app",),
            )
            self.assertAlmostEqual(coverage, 20.0)

    def test_a_package_with_no_lines_is_refused_rather_than_reported_as_full(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, "report.xml", _two_package_report())
            with self.assertRaises(ValueError):
                COVERAGE.line_coverage(path, packages=("ru/wbparser/nowhere",))

    def test_every_known_module_is_measured_on_the_merged_report(self):
        for module in COVERAGE.MODULE_PACKAGES:
            self.assertEqual(COVERAGE.module_report(module), COVERAGE.MERGED_REPORT)

    def test_an_unknown_module_falls_back_to_its_own_report(self):
        self.assertEqual(
            COVERAGE.module_report("fixture", {"fixture": "somewhere.xml"}),
            "somewhere.xml",
        )


class TestCoverageGateCanFail(unittest.TestCase):
    """The end-to-end control: the gate must fail on a floor it does not meet.

    It runs against a fixture report and a fixture floor file, so it works in CI
    where no build output exists — a control that needs the build is a control
    that never runs where the gate runs.
    """

    def _run(self, floor_text: str, report_text: str | None, report_name: str = "report.xml") -> subprocess.CompletedProcess:
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        floor_path = Path(tmp) / "floor.txt"
        floor_path.write_text(floor_text, encoding="utf-8")
        command = [
            sys.executable,
            str(SCRIPTS / "check-coverage.py"),
            "--floor-file",
            str(floor_path),
        ]
        if report_text is not None:
            report_path = Path(tmp) / report_name
            report_path.write_text(report_text, encoding="utf-8")
        else:
            report_path = Path(tmp) / report_name
        command += ["--report", f"fixture={report_path}"]
        return subprocess.run(command, capture_output=True, text=True, check=False)

    def test_a_floor_below_the_measurement_passes(self):
        result = self._run("fixture 40\n", _kover_report(covered=50, missed=50))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_floor_above_the_measurement_fails(self):
        result = self._run("fixture 60\n", _kover_report(covered=50, missed=50))
        self.assertEqual(result.returncode, 1)
        self.assertIn("below", result.stderr)

    def test_a_missing_report_is_not_the_same_as_no_coverage(self):
        result = self._run("fixture 40\n", None, report_name="never-written.xml")
        self.assertEqual(result.returncode, 2)
        self.assertIn("did not run", result.stderr)

    def test_a_malformed_report_is_refused(self):
        result = self._run("fixture 40\n", "<report")
        self.assertEqual(result.returncode, 2)
        self.assertIn("not valid XML", result.stderr)


CI_WORKFLOWS = load("ci_workflows", "ci/check-ci-workflows.py")


class TestCiWorkflowsGateCanFail(unittest.TestCase):
    """The CI-workflows gate must fail when a surface is unwired.

    The gate's subject is the workflows themselves, so its control is a
    fixture repository with a broken workflow — not a broken repository, which
    is what the gate is pointed at in production and would be a rude thing to
    break on purpose.
    """

    GOOD = {
        "codeql.yml": (
            "on:\n  schedule:\n    - cron: \"17 3 * * 1\"\n"
            "jobs:\n  analyze:\n    permissions:\n      security-events: write\n"
            "    steps:\n      - uses: github/codeql-action/init@v4\n"
            "        with:\n          languages: java-kotlin\n"
            "      - uses: github/codeql-action/analyze@v4\n"
        ),
        "scorecard.yml": (
            "jobs:\n  analysis:\n    permissions:\n      id-token: write\n"
            "    steps:\n      - uses: ossf/scorecard-action@v2\n"
            "        with:\n          results_file: results.sarif\n"
        ),
        "lint-autofix.yml": (
            "jobs:\n  autofix:\n    steps:\n      - run: bash scripts/ci/lint-autofix.sh\n"
        ),
        "ci.yml": (
            "on:\n  schedule:\n    - cron: \"0 3 * * *\"\n"
            "jobs:\n"
            "  static:\n    steps:\n      - run: echo static\n"
            "  build:\n    steps:\n      - run: echo build\n"
            "  nightly-alert:\n    needs: [static, build]\n"
            "    steps:\n      - run: gh issue create --title x\n"
        ),
    }

    def _fixture(self, workflows: dict[str, str] | None = None, with_script: bool = True) -> Path:
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, True)
        workflow_dir = tmp / ".github" / "workflows"
        workflow_dir.mkdir(parents=True)
        for name, content in (workflows if workflows is not None else self.GOOD).items():
            (workflow_dir / name).write_text(content, encoding="utf-8")
        script = tmp / "scripts" / "ci" / "lint-autofix.sh"
        script.parent.mkdir(parents=True)
        if with_script:
            script.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        return tmp

    def _check(self, root: Path) -> list[str]:
        return CI_WORKFLOWS.check(root)

    def test_a_wired_repository_has_no_findings(self):
        self.assertEqual(self._check(self._fixture()), [])

    def test_a_missing_workflow_is_a_finding(self):
        workflows = {k: v for k, v in self.GOOD.items() if k != "scorecard.yml"}
        findings = self._check(self._fixture(workflows))
        self.assertTrue(any("scorecard.yml does not exist" in f for f in findings), findings)

    def test_codeql_without_the_upload_permission_is_a_finding(self):
        workflows = dict(self.GOOD)
        workflows["codeql.yml"] = workflows["codeql.yml"].replace(
            "security-events: write", "contents: read"
        )
        findings = self._check(self._fixture(workflows))
        self.assertTrue(any("security-events: write" in f for f in findings), findings)

    def test_scorecard_without_the_oidc_permission_is_a_finding(self):
        workflows = dict(self.GOOD)
        workflows["scorecard.yml"] = workflows["scorecard.yml"].replace(
            "id-token: write", "contents: read"
        )
        findings = self._check(self._fixture(workflows))
        self.assertTrue(any("id-token: write" in f for f in findings), findings)

    def test_a_missing_corrector_is_a_finding(self):
        findings = self._check(self._fixture(with_script=False))
        self.assertTrue(any("lint-autofix.sh does not exist" in f for f in findings), findings)

    def test_a_nightly_run_without_a_schedule_is_a_finding(self):
        """No workflow has a schedule — the tests are supposed to run on their own.

        The check is across the whole surface: a scheduled run in one workflow
        satisfies it, whichever workflow that is. This case removes the
        schedule from every workflow, which is the actual defect.
        """
        workflows = {
            name: text.replace("schedule:", "pull_request:")
            for name, text in self.GOOD.items()
        }
        findings = self._check(self._fixture(workflows))
        self.assertTrue(any("schedule" in f for f in findings), findings)

    def test_a_nightly_run_without_an_issue_is_a_finding(self):
        workflows = dict(self.GOOD)
        workflows["ci.yml"] = workflows["ci.yml"].replace("gh issue create", "echo")
        findings = self._check(self._fixture(workflows))
        self.assertTrue(any("issue" in f for f in findings), findings)

    def test_a_permission_that_is_only_in_a_comment_is_a_finding(self):
        """A commented-out line is not a permission.

        The gate looks for the line that grants the permission. If the line is
        commented out, the check would still see the text and be satisfied by
        its own documentation — the quietest way a gate can stop gating.
        """
        workflows = dict(self.GOOD)
        workflows["codeql.yml"] = workflows["codeql.yml"].replace(
            "      security-events: write", "      # security-events: write"
        )
        findings = self._check(self._fixture(workflows))
        self.assertTrue(any("security-events: write" in f for f in findings), findings)

    def test_a_hash_inside_a_quoted_string_is_not_a_comment(self):
        text = CI_WORKFLOWS.strip_yaml_comments('    comment: "a # b"\n    real: value\n')
        self.assertIn('"a # b"', text)
        self.assertIn("real: value", text)

    def test_a_stale_wait_list_is_a_finding(self):
        """The alert job must wait for every other job of the pipeline.

        Its report comes from the run's API, so it cannot be stale. The wait
        list says *when* the report is made — and a job that is not in the
        list may still be running when the report is made.
        """
        workflows = dict(self.GOOD)
        workflows["ci.yml"] = workflows["ci.yml"].replace(
            "  nightly-alert:\n    needs: [static, build]",
            "  nightly-alert:\n    needs: [static]",
        )
        findings = self._check(self._fixture(workflows))
        self.assertTrue(any("does not wait for" in f for f in findings), findings)

    def test_a_wait_list_that_covers_every_job_is_quiet(self):
        findings = self._check(self._fixture())
        self.assertFalse(any("does not wait for" in f for f in findings), findings)


def _kover_report(covered: int, missed: int) -> str:
    """A kover XML report with one LINE counter, as the plugin writes it."""
    return (
        '<?xml version="1.0" ?>\n'
        "<report name=\"fixture\">\n"
        f'  <counter type="LINE" missed="{missed}" covered="{covered}"/>\n'
        "</report>\n"
    )


def _two_package_report() -> str:
    """A kover XML report with two packages, as the merged report has.

    `ru/wbparser/app` is 80% covered (8 of 10 lines), `ru/wbparser/domain` is 20%
    (1 of 5). Both together are 60% (9 of 15).
    """
    return (
        '<?xml version="1.0" ?>\n'
        "<report name=\"fixture\">\n"
        '  <package name="ru/wbparser/app">\n'
        '    <class name="ru/wbparser/app/A">\n'
        '      <counter type="LINE" missed="2" covered="8"/>\n'
        "    </class>\n"
        "  </package>\n"
        '  <package name="ru/wbparser/domain">\n'
        '    <class name="ru/wbparser/domain/B">\n'
        '      <counter type="LINE" missed="4" covered="1"/>\n'
        "    </class>\n"
        "  </package>\n"
        "</report>\n"
    )


if __name__ == "__main__":
    unittest.main()
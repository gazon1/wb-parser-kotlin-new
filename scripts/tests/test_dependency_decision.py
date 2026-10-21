#!/usr/bin/env python3
"""Тесты чтения записанных решений по major-зависимостям.

Что покрыто
-----------
Только то, что не ходит в сеть: как из таблицы решений достаётся решение по
зависимости, что то же решение находится для следующего pull request'а по той
же зависимости, что таблица с неизвестным словом в графе «решение» — это
поломка, а не решение, и что решение, привязанное к конкретному бампу, не
находится для другого бампа.

Файл написан на `unittest.TestCase` по конвенции соседних тестов: тот же файл
запускается и гейтом (`python3 -m unittest discover -s scripts/tests`), и pytest.
"""

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent


def load(name: str, path: Path):
    """Импортировать скрипт по пути — они не пакет, а имя файла."""
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader, f"cannot load {path}"
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


decision = load("dependency_decision", SCRIPTS / "dependency_decision.py")
jdk_gate = load("check_docker_jdk", SCRIPTS / "ci" / "check-docker-jdk.py")

#: Тело pull request'а dependabot'а об одной зависимости.
BODY = (
    "Bumps eclipse-temurin from 22-jre to 25-jre.\n"
    "\n"
    "[![Dependabot compatibility score](https://dependabot-badges.githubapp.com/)]\n"
)

#: Тело группового pull request'а: таблица и разделы по каждой зависимости.
GROUP_BODY = (
    "Bumps the spring group with 3 updates in the / directory:\n"
    "\n"
    "| Package | From | To |\n"
    "| --- | --- | --- |\n"
    "| [org.springframework.boot](https://github.com/spring-projects/spring-boot) | `3.4.1` | `4.1.1` |\n"
    "| [org.springframework:spring-web](https://github.com/spring-projects/spring-framework) | `6.2.1` | `7.0.9` |\n"
    "\n"
    "Updates `org.springframework:spring-web` from 6.2.1 to 7.0.9\n"
)


class DependencyDecisionTest(unittest.TestCase):
    def setUp(self) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.table = Path(self._dir.name) / "decisions.tsv"
        self.addCleanup(self._dir.cleanup)

    def write(self, text: str) -> None:
        self.table.write_text(text, encoding="utf-8")

    def test_a_decision_is_found_by_its_dependency(self) -> None:
        self.write("ktor\ttaken\t2.3.13 → 3.6.0\tPR #59: гейты зелёные.\n")
        found = decision.decision_for("ktor", path=self.table)
        self.assertIn("taken", found)
        self.assertIn("PR #59", found)

    def test_the_same_decision_is_found_for_the_next_pull_request(self) -> None:
        """Решение живёт под зависимостью, а не под номером pull request'а.

        Номер PR — часть причины, но не ключ: следующий pull request по той же
        зависимости находит то же решение.
        """
        self.write(
            "eclipse-temurin\tdeferred\t22 → 25\tPR #97 и PR #169: взяли и сломали.\n"
        )
        for number in ("97", "169", "200"):
            found = decision.decision_for("eclipse-temurin", ("22", "25"), self.table)
            self.assertIn("deferred", found)
            self.assertIn("#169", found)
            self.assertNotIn(number, found.split("\t")[0])

    def test_a_dependency_without_a_decision_says_so(self) -> None:
        self.write("ktor\ttaken\t2.3.13 → 3.6.0\tPR #59: гейты зелёные.\n")
        self.assertEqual(decision.NONE, decision.decision_for("junit", path=self.table))

    def test_an_empty_table_is_not_a_broken_one(self) -> None:
        self.write("# только комментарий\n\n")
        self.assertEqual(0, decision.check(self.table))
        self.assertEqual(decision.NONE, decision.decision_for("ktor", path=self.table))

    def test_a_missing_table_is_not_a_broken_one(self) -> None:
        self.assertEqual(0, decision.check(self.table))

    def test_an_unknown_decision_is_a_broken_table(self) -> None:
        self.write("ktor\tmerged\t2.3.13 → 3.6.0\tне то слово\n")
        self.assertEqual(2, decision.check(self.table))

    def test_a_row_without_a_reason_is_a_broken_table(self) -> None:
        self.write("ktor\ttaken\t2.3.13 → 3.6.0\n")
        self.assertEqual(2, decision.check(self.table))

    def test_a_row_without_a_dependency_is_a_broken_table(self) -> None:
        self.write("\ttaken\t2.3.13 → 3.6.0\tпочему\n")
        self.assertEqual(2, decision.check(self.table))

    def test_a_row_without_a_version_range_is_a_broken_table(self) -> None:
        self.write("ktor\ttaken\t\tпочему\n")
        self.assertEqual(2, decision.check(self.table))

    def test_comments_and_blank_lines_are_not_rows(self) -> None:
        self.write("# комментарий\n\nktor\ttaken\t2.3.13 → 3.6.0\tпочему\n")
        self.assertEqual(0, decision.check(self.table))
        self.assertIn("taken", decision.decision_for("ktor", path=self.table))

    def test_a_version_range_narrows_the_answer(self) -> None:
        """Решение, привязанное к бампу, не отвечает за другой бамп."""
        self.write("eclipse-temurin\tdeferred\t22 → 25\tPR #97: взяли и сломали.\n")
        self.assertIn("deferred", decision.decision_for("eclipse-temurin", ("22", "25"), self.table))
        self.assertEqual(
            decision.NONE, decision.decision_for("eclipse-temurin", ("25", "26"), self.table)
        )

    def test_a_decision_without_a_version_answers_for_any_bump(self) -> None:
        self.write("tailwindcss\tdeferred\t*\tPR #33: сборка падает.\n")
        self.assertIn("deferred", decision.decision_for("tailwindcss", ("3", "4"), self.table))
        self.assertIn("deferred", decision.decision_for("tailwindcss", ("3", "5"), self.table))

    def test_a_major_pattern_covers_the_minor_below_it(self) -> None:
        self.write("typescript\tdeferred\t5.6 → 7\tPR #35: `npm ci` не разрешается.\n")
        self.assertIn("deferred", decision.decision_for("typescript", ("5.6.3", "7.0.0"), self.table))

    def test_a_prefix_matches_a_whole_family(self) -> None:
        self.write("io.ktor:*\ttaken\t2.3.13 → 3.6.0\tPR #59: гейты зелёные.\n")
        self.assertIn("taken", decision.decision_for("io.ktor:ktor-client-core", path=self.table))
        self.assertEqual(decision.NONE, decision.decision_for("io.other:thing", path=self.table))

    def test_the_script_prints_none_when_nothing_is_recorded(self) -> None:
        self.write("ktor\ttaken\t2.3.13 → 3.6.0\tпочему\n")
        result = subprocess.run(
            [sys.executable, str(SCRIPTS / "dependency_decision.py"), "junit"],
            capture_output=True,
            text=True,
            check=True,
            env={**os.environ, "DEPENDENCY_DECISIONS": str(self.table)},
        )
        self.assertEqual(decision.NONE, result.stdout.strip())

    def test_the_script_prints_the_decision_by_dependency(self) -> None:
        self.write("ktor\ttaken\t2.3.13 → 3.6.0\tPR #59: гейты зелёные.\n")
        result = subprocess.run(
            [sys.executable, str(SCRIPTS / "dependency_decision.py"), "ktor", "2.3.13", "3.6.0"],
            capture_output=True,
            text=True,
            check=True,
            env={**os.environ, "DEPENDENCY_DECISIONS": str(self.table)},
        )
        self.assertIn("taken", result.stdout)
        self.assertIn("PR #59", result.stdout)

    def test_the_table_in_the_repository_is_readable(self) -> None:
        # Таблица в репозитории — часть контракта: сломанная таблица должна
        # падать громко, и это должен видеть гейт, а не человек.
        self.assertEqual(0, decision.check(decision.TABLE))


class DependencyOfPullRequestTest(unittest.TestCase):
    """Зависимость читается из тела pull request'а."""

    def test_one_dependency_is_read_from_the_body(self) -> None:
        found = decision.dependencies_of(BODY)
        self.assertEqual([("eclipse-temurin", "22-jre", "25-jre")], found)

    def test_a_group_is_read_from_the_table_of_its_body(self) -> None:
        found = decision.dependencies_of(GROUP_BODY)
        names = [name for name, _, _ in found]
        self.assertIn("org.springframework.boot", names)
        self.assertIn("org.springframework:spring-web", names)

    def test_a_body_without_an_update_has_no_dependency(self) -> None:
        self.assertEqual([], decision.dependencies_of("ничего здесь нет\n"))

    def test_a_pull_request_without_a_decision_is_unknown_or_none(self) -> None:
        self.assertEqual(decision.UNKNOWN, decision.decision_for_pull_request("ничего\n"))


class PullRequestDecisionTest(unittest.TestCase):
    """Решение по pull request'у находится по зависимости, которую он несёт."""

    def setUp(self) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.table = Path(self._dir.name) / "decisions.tsv"
        self.addCleanup(self._dir.cleanup)

    def write(self, text: str) -> None:
        self.table.write_text(text, encoding="utf-8")

    def test_the_next_pull_request_of_the_same_dependency_finds_the_decision(self) -> None:
        """Тот самый случай: решение принято под PR #97, а пришло PR #169."""
        self.write(
            "eclipse-temurin\tdeferred\t22 → 25\tPR #97: взяли и сломали.\n"
        )
        found = decision.decision_for_pull_request(BODY, self.table)
        self.assertIn("deferred", found)
        self.assertIn("взяли и сломали", found)

    def test_a_group_finds_the_decision_of_one_of_its_dependencies(self) -> None:
        self.write(
            "org.springframework:*\tdeferred\t6.2.1 → 7.0.9\tPR #99: миграция.\n"
        )
        found = decision.decision_for_pull_request(GROUP_BODY, self.table)
        self.assertIn("deferred", found)

    def test_a_pull_request_without_a_decision_says_none(self) -> None:
        self.write("ktor\ttaken\t2.3.13 → 3.6.0\tPR #59: гейты зелёные.\n")
        self.assertEqual(decision.NONE, decision.decision_for_pull_request(BODY, self.table))


class DockerJdkGateExplainsADecisionTest(unittest.TestCase):
    """Гейт про образ и JDK объясняет записанное решение, а не просто падает."""

    def setUp(self) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.root = Path(self._dir.name) / "tree"
        self.root.mkdir()
        self.table = Path(self._dir.name) / "decisions.tsv"
        self.addCleanup(self._dir.cleanup)

        (self.root / "build.gradle.kts").write_text("    jvmToolchain(22)\n", encoding="utf-8")
        (self.root / "Dockerfile.app").write_text(
            "FROM eclipse-temurin:25-jdk-alpine AS builder\n", encoding="utf-8"
        )
        (self.root / "Dockerfile.debug").write_text(
            "FROM eclipse-temurin:25-jre\n", encoding="utf-8"
        )

    def test_a_divergence_without_a_decision_is_a_finding(self) -> None:
        self.table.write_text("# решений нет\n", encoding="utf-8")
        findings, explanations = jdk_gate.check(self.root, self.table)
        self.assertTrue(findings)
        self.assertEqual([], explanations)

    def test_a_divergence_with_a_deferred_decision_is_explained(self) -> None:
        self.table.write_text(
            "eclipse-temurin\tdeferred\t22 → 25\tвзяли и сломали.\n", encoding="utf-8"
        )
        findings, explanations = jdk_gate.check(self.root, self.table)
        self.assertEqual([], findings)
        self.assertTrue(any("решение отложено" in note for note in explanations))
        self.assertTrue(any("взяли и сломали" in note for note in explanations))

    def test_a_divergence_with_a_taken_decision_is_still_a_finding(self) -> None:
        """`taken` — решение о бампе принято, и образ всё равно не тот."""
        self.table.write_text(
            "eclipse-temurin\ttaken\t22 → 25\tвзяли.\n", encoding="utf-8"
        )
        findings, explanations = jdk_gate.check(self.root, self.table)
        self.assertTrue(findings)
        self.assertEqual([], explanations)


if __name__ == "__main__":
    unittest.main()

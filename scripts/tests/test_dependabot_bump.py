#!/usr/bin/env python3
"""Тесты определения версионного бампа в pull request Dependabot.

Что покрыто
-----------
Только то, что не ходит в сеть: как из тела pull request достаются пары
версий, как по ним считается бамп и что скрипт решает сливать ли.

Файл написан на `unittest.TestCase` по конвенции соседних тестов: тот же файл
запускается и гейтом (`python3 -m unittest discover -s scripts/tests`), и pytest.
"""

import importlib.util
import subprocess
import sys
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


BUMP = load("dependabot_bump", SCRIPTS / "dependabot_bump.py")


class BumpTest(unittest.TestCase):
    """Как читается бамп из тела pull request."""

    def test_patch_between_two_versions(self) -> None:
        self.assertEqual(BUMP.classify("Bumps foo from 1.2.3 to 1.2.4."), BUMP.PATCH)

    def test_minor_between_two_versions(self) -> None:
        self.assertEqual(BUMP.classify("Bumps foo from 1.2.3 to 1.3.0."), BUMP.MINOR)

    def test_major_between_two_versions(self) -> None:
        self.assertEqual(BUMP.classify("Bumps foo from 1.2.3 to 2.0.0."), BUMP.MAJOR)

    def test_two_component_versions(self) -> None:
        # Не у каждой зависимости три числа: `from 5.7 to 5.8` — это minor.
        self.assertEqual(BUMP.classify("Bumps foo from 5.7 to 5.8."), BUMP.MINOR)

    def test_leading_v_is_ignored(self) -> None:
        self.assertEqual(BUMP.classify("Bumps foo from v1.2.3 to v1.2.4."), BUMP.PATCH)

    def test_grouped_pull_request_takes_the_strongest_bump(self) -> None:
        body = (
            "Bumps the group across 1 directory with 3 updates.\n"
            "- [foo] updates from 1.0.0 to 1.0.1\n"
            "- [bar] updates from 2.0.0 to 2.1.0\n"
            "- [baz] updates from 3.0.0 to 3.0.1\n"
        )
        self.assertEqual(BUMP.classify(body), BUMP.MINOR)

    def test_a_major_in_a_group_wins(self) -> None:
        body = (
            "Bumps the group across 1 directory with 2 updates.\n"
            "- [foo] updates from 1.0.0 to 1.0.1\n"
            "- [bar] updates from 2.0.0 to 3.0.0\n"
        )
        self.assertEqual(BUMP.classify(body), BUMP.MAJOR)

    def test_a_version_that_is_not_a_version_is_unknown(self) -> None:
        # «from main to v2» — не пара версий, и такая строка не должна
        # молча считаться patch'ем.
        self.assertEqual(BUMP.classify("Bumps foo from main to v2."), BUMP.MIXED)

    def test_body_without_versions_is_unknown(self) -> None:
        self.assertEqual(BUMP.classify("There is nothing to bump here."), BUMP.UNKNOWN)

    def test_prose_with_from_and_to_is_not_a_bump(self) -> None:
        # Тело pull request'а — это не только список обновлений: там есть
        # таблицы, и в их заголовке «from» и «to» стоят без всяких версий.
        # Такая пара не является бампом и не должна решать его.
        body = (
            "| Dependency | from | to |\n"
            "|---|---|---|\n"
            "- [foo] updates from 1.0.0 to 1.0.1\n"
        )
        self.assertEqual(BUMP.classify(body), BUMP.PATCH)

    def test_a_version_the_script_reads_only_partly_stops_the_merge(self) -> None:
        # `22-jre → 24-jre` — версия, у которой скрипт возьмёт только число.
        # 22 и 24 — это разные major, и такой бамп автослияние не запускает.
        body = (
            "Bumps the group with 2 updates.\n"
            "- [foo] updates from 1.0.0 to 1.0.1\n"
            "- [eclipse-temurin] updates from 22-jre to 24-jre\n"
        )
        self.assertEqual(BUMP.classify(body), BUMP.MAJOR)

    def test_a_version_that_became_a_line_is_not_mergeable(self) -> None:
        # `2.19.1 → 2.22` — предложение точнее не стало, а стало грубее:
        # `2.22` — это линия версий, а не релиз, и какой релиз за ней стоит,
        # скрипт не знает. Такого релиза может и не быть вовсе: `2.22`
        # публикует jackson-annotations, а у jackson-databind есть только
        # `2.22.0`…`2.22.4`.
        self.assertEqual(BUMP.classify("Bumps foo from 2.19.1 to 2.22."), BUMP.MIXED)

    def test_a_line_in_a_group_stops_the_merge(self) -> None:
        # Один такой бамп в группе останавливает всю группу: сливать можно
        # только то, что понятно.
        body = (
            "Bumps the group with 2 updates.\n"
            "- [foo] updates from 1.0.0 to 1.0.1\n"
            "- [jackson-databind] updates from 2.19.1 to 2.22\n"
        )
        self.assertEqual(BUMP.classify(body), BUMP.MIXED)


class ExitCodeTest(unittest.TestCase):
    """Что скрипт решает машине сливать."""

    def _run(self, body: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPTS / "dependabot_bump.py")],
            input=body,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_patch_is_mergeable(self) -> None:
        result = self._run("Bumps foo from 1.2.3 to 1.2.4.")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), "patch")

    def test_minor_is_mergeable(self) -> None:
        result = self._run("Bumps foo from 1.2.3 to 1.3.0.")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), "minor")

    def test_major_is_not_mergeable(self) -> None:
        result = self._run("Bumps foo from 1.2.3 to 2.0.0.")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout.strip(), "major")

    def test_unknown_is_not_mergeable(self) -> None:
        result = self._run("Nothing here.")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout.strip(), "unknown")

    def test_a_version_that_became_a_line_is_not_mergeable(self) -> None:
        # `2.19.1 → 2.22` — версия, которой может не быть на Maven Central
        # вовсе. Скрипт не ходит в сеть и не знает, опубликован ли релиз, но
        # он знает, что точность имени упала, — и «не знаю» не значит
        # «не опасно».
        result = self._run("Bumps foo from 2.19.1 to 2.22.")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout.strip(), "mixed")

    def test_a_file_argument_is_read(self) -> None:
        import tempfile

        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as handle:
            handle.write("Bumps foo from 1.2.3 to 1.2.4.")
            name = handle.name

        result = subprocess.run(
            [sys.executable, str(SCRIPTS / "dependabot_bump.py"), name],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), "patch")


if __name__ == "__main__":
    unittest.main()

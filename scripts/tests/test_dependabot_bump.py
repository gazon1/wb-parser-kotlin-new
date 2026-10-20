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

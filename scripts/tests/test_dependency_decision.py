#!/usr/bin/env python3
"""Тесты чтения записанных решений по major-зависимостям.

Что покрыто
-----------
Только то, что не ходит в сеть: как из таблицы решений достаётся решение по
номеру pull request'а, что таблица с неизвестным словом в графе «решение» —
это поломка, а не решение, и что решение, которого нет, ищется как «решение
не записано».

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
    spec.loader.exec_module(module)
    return module


decision = load("dependency_decision", SCRIPTS / "dependency_decision.py")


class DependencyDecisionTest(unittest.TestCase):
    def setUp(self) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.table = Path(self._dir.name) / "decisions.tsv"
        self.addCleanup(self._dir.cleanup)

    def write(self, text: str) -> None:
        self.table.write_text(text, encoding="utf-8")

    def test_a_decision_is_found_by_its_pull_request(self) -> None:
        self.write("59\ttaken\tktor 2.3 → 3.6: гейты зелёные.\n")
        found = decision.decision_for("59", self.table)
        self.assertIn("taken", found)
        self.assertIn("ktor 2.3 → 3.6", found)

    def test_a_pull_request_without_a_decision_says_so(self) -> None:
        self.write("59\ttaken\tktor 2.3 → 3.6: гейты зелёные.\n")
        self.assertEqual(decision.NONE, decision.decision_for("58", self.table))

    def test_an_empty_table_is_not_a_broken_one(self) -> None:
        self.write("# только комментарий\n\n")
        self.assertEqual(0, decision.check(self.table))
        self.assertEqual(decision.NONE, decision.decision_for("59", self.table))

    def test_a_missing_table_is_not_a_broken_one(self) -> None:
        self.assertEqual(0, decision.check(self.table))

    def test_an_unknown_decision_is_a_broken_table(self) -> None:
        self.write("59\tmerged\tне то слово\n")
        self.assertEqual(2, decision.check(self.table))

    def test_a_row_without_a_reason_is_a_broken_table(self) -> None:
        self.write("59\ttaken\n")
        self.assertEqual(2, decision.check(self.table))

    def test_comments_and_blank_lines_are_not_rows(self) -> None:
        self.write("# комментарий\n\n59\ttaken\tпочему\n")
        self.assertEqual(0, decision.check(self.table))
        self.assertIn("taken", decision.decision_for("59", self.table))

    def test_the_script_prints_none_when_nothing_is_recorded(self) -> None:
        self.write("59\ttaken\tпочему\n")
        result = subprocess.run(
            [sys.executable, str(SCRIPTS / "dependency_decision.py"), "58"],
            capture_output=True,
            text=True,
            check=True,
            env={**os.environ, "DEPENDENCY_DECISIONS": str(self.table)},
        )
        self.assertEqual(decision.NONE, result.stdout.strip())

    def test_the_table_in_the_repository_is_readable(self) -> None:
        # Таблица в репозитории — часть контракта: сломанная таблица должна
        # падать громко, и это должен видеть гейт, а не человек.
        self.assertEqual(0, decision.check(decision.TABLE))


if __name__ == "__main__":
    unittest.main()

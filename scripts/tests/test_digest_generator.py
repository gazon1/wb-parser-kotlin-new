#!/usr/bin/env python3
"""Тесты генератора `docs/decisions/DIGEST.md`.

Что покрыто
-----------
Формат выжимки, на который рассчитан Critical-раздел: правило приходит в digest
целиком, даже если в ADR оно перенесено на несколько строк, и однострочное
правило приходит без изменений.

Почему это отдельный тест
--------------------------
Генератор — это программа, которая читает чужие тексты и переписывает их. Ошибка
в ней не выглядит поломкой: digest остаётся валидным markdown, в нём остаются
все правила, и гейт `scripts/check-decisions-digest.sh` остаётся зелёным, потому
что он сверяет digest с тем же генератором. Агент при этом читает правило,
оборванное на полуслове, и принимает его за целое.

Поэтому тест работает на фикстуре: свой каталог `docs/decisions/` с двумя ADR —
перенесённым правилом и однострочным, — и смотрит, что попало в digest. Фикстура,
а не настоящие ADR: тест проверяет правило, а не то, как сегодня уложились
чужие тексты.

Файл написан на `unittest.TestCase` по конвенции соседних тестов: тот же файл
запускается и гейтом (`python3 -m unittest discover -s scripts/tests`), и pytest.
"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
GENERATOR = REPO_ROOT / "scripts" / "refresh-decisions-digest.sh"

WRAPPED_ADR = """---
title: "Правило перенесено по ширине"
date: 2026-10-21
tags: [documentation]
---

## Context

Правило в ADR переносится по ширине — так его пишут.

## Consequences

- **Always** отделять антибот (498) от транспортного сброса (статус 0) в любом
  счёте отказов: доля отказов — это доля антибота, а не «всего плохого».
- **Never** писать правило в две строки.
"""

PLAIN_ADR = """---
title: "Правило без маркера"
date: 2026-10-21
tags: [documentation]
---

## Context

Правило без маркера попадает в Per-tag, а не в Critical.

## Consequences

- Переносить это правило
  на следующую строку тоже можно.
"""


class DigestGeneratorTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self._tmp, True)
        # Генератор ищет решения рядом с собой: `<root>/scripts` и
        # `<root>/docs/decisions`. Поэтому он копируется в фикстуру целиком —
        # иначе он взял бы настоящие ADR репозитория.
        scripts = self._tmp / "scripts"
        scripts.mkdir()
        shutil.copy(GENERATOR, scripts / GENERATOR.name)
        decisions = self._tmp / "docs" / "decisions"
        decisions.mkdir(parents=True)
        (decisions / "2026-10-21-a-wrapped-rule.md").write_text(
            WRAPPED_ADR, encoding="utf-8"
        )
        (decisions / "2026-10-21-a-plain-rule.md").write_text(PLAIN_ADR, encoding="utf-8")
        self.digest = decisions / "DIGEST.md"

    def generate(self) -> str:
        result = subprocess.run(
            ["bash", str(self._tmp / "scripts" / GENERATOR.name)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return self.digest.read_text(encoding="utf-8")

    def critical_lines(self, digest: str) -> list[str]:
        """Строки Critical-раздела — те, что начинаются с маркера буллета."""
        section = digest.split("## Critical", 1)[1].split("## Per-tag", 1)[0]
        return [line for line in section.splitlines() if line.startswith("- ")]

    def test_a_wrapped_rule_arrives_whole(self) -> None:
        digest = self.generate()
        self.assertIn(
            "- **Always** отделять антибот (498) от транспортного сброса (статус 0) "
            "в любом счёте отказов: доля отказов — это доля антибота, а не "
            "«всего плохого». _(from `2026-10-21-a-wrapped-rule`)",
            digest,
        )

    def test_a_wrapped_rule_is_not_cut_at_the_first_line(self) -> None:
        # Ровно тот вид, который issue #161 описывает: правило, оборванное на
        # полуслове, с маркером источника в конце обрывка.
        digest = self.generate()
        self.assertNotIn(
            "- **Always** отделять антибот (498) от транспортного сброса (статус 0) "
            "в любом _(from `2026-10-21-a-wrapped-rule`)",
            digest,
        )

    def test_a_wrapped_rule_stays_one_line(self) -> None:
        # Свойство, на котором держится Critical: одна строка — одно правило.
        digest = self.generate()
        wrapped = [
            line
            for line in self.critical_lines(digest)
            if line.startswith("- **Always** отделять антибот")
        ]
        self.assertEqual(1, len(wrapped), wrapped)

    def test_a_one_line_rule_is_unchanged(self) -> None:
        digest = self.generate()
        self.assertIn(
            "- **Never** писать правило в две строки. "
            "_(from `2026-10-21-a-wrapped-rule`)",
            digest,
        )

    def test_a_wrapped_rule_without_a_marker_arrives_whole_too(self) -> None:
        # Per-tag собирается тем же корпусом, поэтому и там правило должно быть
        # целым — иначе Critical починен, а Per-tag остался с обрывками.
        digest = self.generate()
        self.assertIn(
            "- Переносить это правило на следующую строку тоже можно. "
            "_(from `2026-10-21-a-plain-rule`)_",
            digest,
        )

    def test_the_source_file_keeps_its_wrapping(self) -> None:
        # Перенос — это ширина исходника, а не свойство digest: правило собирает
        # генератор, и менять ADR ради него не нужно.
        self.generate()
        source = (self._tmp / "docs" / "decisions" / "2026-10-21-a-wrapped-rule.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("в любом\n  счёте отказов", source)

    def test_the_generator_is_idempotent(self) -> None:
        first = self.generate()
        second = self.generate()
        self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()

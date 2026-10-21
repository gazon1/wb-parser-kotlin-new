#!/usr/bin/env python3
"""Какое решение записано для major-зависимости.

Зачем
----
Major-обновление — это решение, а не обновление: меняется поведение, ломаются
предположения, иногда нужна правка кода. Пока решение не записано, оно каждый
цикл висит как «человек решает» — и список major'ов растёт молча, никто не
видит, сколько их накопилось, и решение о каждом откладывается снова.

Решение записывается в `config/gates/dependency-decisions.tsv`: номер pull
request'а, решение и почему. Таблица лежит рядом с остальными решениями
репозитория, потому что это и есть решение — такое же, как любое другое.

Что скрипт делает
-----------------
    python3 scripts/dependency_decision.py <номер PR>

печатает `none`, если решения нет, иначе `<решение>\t<почему>`.

    python3 scripts/dependency_decision.py --check

проверяет, что таблица читается: каждая строка — это номер, решение из
известного набора и почему. Незнакомое слово в графе «решение» — это опечатка,
и такая таблица должна падать громко: решение, которое никто не может прочитать,
не решение.

Решения, которые скрипт понимает
-------------------------------
    taken        — взято, слито
    deferred     — отложено, и почему
    unnecessary  — признано ненужным
"""

import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TABLE = Path(
    os.environ.get("DEPENDENCY_DECISIONS", ROOT / "config" / "gates" / "dependency-decisions.tsv")
)

DECISIONS = ("taken", "deferred", "unnecessary")

NONE = "none"


def _rows(path: Path):
    """Строки таблицы: (номер, решение, почему) и строки, которые не разобрались."""
    good = []
    broken = []
    if not path.exists():
        return good, broken
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3 or not parts[0].strip() or not parts[2].strip():
            broken.append((number, "the row has no number, decision or reason"))
            continue
        pr, decision, why = parts[0].strip(), parts[1].strip(), "\t".join(parts[2:]).strip()
        if decision not in DECISIONS:
            broken.append((number, f"'{decision}' is not a decision: expected one of {', '.join(DECISIONS)}"))
            continue
        good.append((pr, decision, why))
    return good, broken


def decision_for(pr: str, path: Path = TABLE) -> str:
    """Решение по номеру pull request'а: `none` или `<решение>\t<почему>`."""
    good, _ = _rows(path)
    for number, decision, why in good:
        if number == pr:
            return f"{decision}\t{why}"
    return NONE


def check(path: Path = TABLE) -> int:
    """Проверить, что таблица читается. 0 — читается, 2 — нет."""
    good, broken = _rows(path)
    for number, message in broken:
        print(f"::error::{path.name}:{number}: {message}")
    if broken:
        return 2
    print(f"{path.name}: {len(good)} decision(s), all readable")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 1
    if argv[1] == "--check":
        return check()
    print(decision_for(argv[1].lstrip("#")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

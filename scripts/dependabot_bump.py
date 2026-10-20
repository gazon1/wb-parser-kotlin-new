#!/usr/bin/env python3
"""Какой версиионный бамп несёт pull request Dependabot.

Зачем
----
Автослияние зависимостей должно сливать только то, что не может сломать код
изменением смысла: patch и minor. Major — это отдельное решение, и оно
остаётся за человеком.

Но «patch и minor» нельзя узнать по названию группы: сгруппированный pull
request называется по группе, а не по бампам, и в его теле перечислены все
обновления. Поэтому бамп вычисляется из самих версий — из каждой пары
«from X to Y», которую Dependabot пишет в теле.

Что скрипт делает
-----------------
Читает тело pull request (файл или stdin) и печатает бамп:

    patch   — только patch
    minor   — есть minor, и всё остальное — patch
    major   — есть major
    mixed   — в теле есть бампы, которые не разобрались
    unknown — в теле нет ни одной разбираемой пары версий

Код возврата 0 только для `patch` и `minor`: всё остальное — «не сливать».
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# Dependabot пишет в теле pull request строки вида
#   Bumps actions/setup-java from 5.7.0 to 6.1.0.
#   - [io.ktor:ktor-client-core] updates from 2.3.13 to 3.6.0
#   Updates `foo` from 1.0.0 to 1.0.1
#
# Версия в версии заканчивается точкой — «to 6.1.0.» — поэтому версия
# сначала берётся целиком, а потом очищается от пунктуации и кавычек.
PAIR = re.compile(r"from\s+(\S+)\s+to\s+(\S+)", re.IGNORECASE)

# Знаки, которые могут стоять вокруг версии в тексте: кавычки Markdown,
# закрывающая скобка, точка в конце предложения, запятая, двоеточие.
JUNK = "`*.,;:)]«»\"'"


def _clean(version: str) -> str:
    """Убрать вокруг версии знаки, которые принадлежат тексту, а не версии."""
    return version.strip().strip(JUNK)


# Версия в начале строки: цифры, разделённые точками, с необязательным
# префиксом `v` и необязательным суффиксом (`-alpha.1`, `+build.7`).
VERSION = re.compile(r"^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?")

PATCH = "patch"
MINOR = "minor"
MAJOR = "major"
MIXED = "mixed"
UNKNOWN = "unknown"


def _numbers(version: str) -> tuple[int, int, int] | None:
    """Разобрать версию в тройку (major, minor, patch). None — если не версия."""
    match = VERSION.match(version.strip())
    if not match:
        return None
    major, minor, patch = match.groups()
    return int(major), int(minor or 0), int(patch or 0)


def _looks_like_a_version(text: str) -> bool:
    """Есть ли в строке цифры — признак того, что это версия, а не слово.

    Тело pull request'а — это не только список обновлений: там есть таблицы,
    примечания к выпускам и проза, и в ней встречаются слова «from» и «to»
    без всяких версий. Такие пары не являются бампом и не должны решать его.

    А вот `22-jre → 24-jre` — версия, которую этот скрипт разобрать не может.
    Она обязана остановить автослияние: решение «не знаю» и решение «не
    опасно» — не одно и то же.
    """
    return any(char.isdigit() for char in text)


def bump_of(old: str, new: str) -> str:
    """Бамп между двумя версиями: major, minor, patch или mixed."""
    before = _numbers(old)
    after = _numbers(new)
    if before is None or after is None:
        return MIXED
    if after[0] != before[0]:
        return MAJOR
    if after[1] != before[1]:
        return MINOR
    if after[2] != before[2]:
        return PATCH
    # Версия не изменилась — Dependabot не присылает такие, но «никакого
    # изменения» это точно не major.
    return PATCH


def pairs(text: str) -> list[tuple[str, str]]:
    """Все пары «откуда → куда» из текста."""
    return [
        (_clean(match.group(1)), _clean(match.group(2))) for match in PAIR.finditer(text)
    ]


def classify(text: str) -> str:
    """Бамп pull request целиком."""
    found = pairs(text)
    if not found:
        return UNKNOWN

    bumps: set[str] = set()
    for old, new in found:
        if not _looks_like_a_version(old) and not _looks_like_a_version(new):
            # Проза, а не обновление: в теле pull request'а полно таблиц и
            # примечаний, и в них «from» и «to» встречаются без версий.
            continue
        bumps.add(bump_of(old, new))

    if not bumps:
        return UNKNOWN
    if MAJOR in bumps:
        return MAJOR
    if MIXED in bumps or UNKNOWN in bumps:
        return MIXED
    if MINOR in bumps:
        return MINOR
    return PATCH


def main(argv: list[str]) -> int:
    if len(argv) > 1:
        body = Path(argv[1]).read_text(encoding="utf-8")
    else:
        body = sys.stdin.read()

    bump = classify(body)
    print(bump)

    # Только patch и minor — это то, что можно слить машине.
    return 0 if bump in (PATCH, MINOR) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))

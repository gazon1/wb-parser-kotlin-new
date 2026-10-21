#!/usr/bin/env python3
"""Что решено по зависимости.

Зачем
----
Major-обновление — это решение, а не обновление: меняется поведение, ломаются
предположения, иногда нужна правка кода. Пока решение не записано, оно каждый
цикл висит как «человек решает» — и список major'ов растёт молча, никто не
видит, сколько их накопилось, и решение о каждом откладывается снова.

Идентичность решения — **зависимость**, а не номер pull request'а. Номер PR —
это запись о конкретном заходе: dependabot открывает следующий pull request по
той же зависимости, номер меняется, и решение, записанное под старым номером,
перестаёт находиться — хотя оно и было принято, и человек, который его
принял, уже ответил на этот вопрос. Решение живёт под номером — значит, оно
умирает вместе с номером.

Решение записывается в `config/gates/dependency-decisions.tsv`: зависимость,
решение, диапазон версий и почему. Таблица лежит рядом с остальными решениями
репозитория, потому что это и есть решение — такое же, как любое другое.

Формат строки
--------------
    <зависимость>\t<решение>\t<диапазон версий>\t<почему>

- **зависимость** — имя, которым dependabot называет обновление:
  `eclipse-temurin`, `actions/setup-java`, `typescript`,
  `io.ktor:ktor-client-core`. В конце имени может стоять `*` — «любая
  зависимость с таким началом»: `io.ktor:*`, `org.springframework:*`. Это
  нужно, потому что один major часто едет целым семейством артефактов, а
  перечислять их по одному — это таблица, которую никто не поддержит;
- **решение** — `taken` (взято), `deferred` (отложено) или `unnecessary`
  (ненужно);
- **диапазон версий** — `22 → 25`: к какому бампу относится решение. `*`
  означает «решение не привязано к конкретному бампу»;
- **почему** — причина по-русски. Номер(а) pull request'а — часть причины:
  он показывает, каким заходом решение было принято, но ключом записи не
  является.

Строка, которую нельзя прочитать, — это опечатка, и таблица должна падать
громко: решение, которое никто не может прочитать, не решение.

Что скрипт делает
-----------------
    python3 scripts/dependency_decision.py <зависимость> [<из> <куда>]

печатает `none`, если решения нет, иначе `<решение>\t<почему>`. Диапазон
версий не обязателен: решение, привязанное к конкретному бампу, находится
только по этому бампу, а решение «на любой бамп» — по любому.

    python3 scripts/dependency_decision.py --pull-request <файл-тела>

печатает решение по pull request'у: зависимость читается из его тела, и
находится решение по ней. `none` — решение не записано, `unknown` —
зависимость из тела прочитать не удалось (и решение могло быть, но его не
нашли).

    python3 scripts/dependency_decision.py --check

проверяет, что таблица читается: каждая строка — это зависимость, решение из
известного набора, диапазон версий и почему.

Решения, которые скрипт понимает
-------------------------------
    taken        — взято, слито
    deferred     — отложено, и почему
    unnecessary  — признано ненужным
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TABLE = Path(
    os.environ.get("DEPENDENCY_DECISIONS", ROOT / "config" / "gates" / "dependency-decisions.tsv")
)

DECISIONS = ("taken", "deferred", "unnecessary")

NONE = "none"
UNKNOWN = "unknown"

#: Любая версия.
ANY = "*"

#: Диапазон версий: `22 → 25`. Стрелка написана тремя способами, потому что
#: таблицу пишут руками, и кавычки в командной строке — тоже руками.
RANGE = re.compile(r"^\s*(.+?)\s*(?:→|->|\.\.)\s*(.+?)\s*$")

#: Собственные слова dependabot об одной зависимости:
#:   Bumps eclipse-temurin from 22-jre to 25-jre.
SINGLE = re.compile(
    r"^Bumps\s+(?P<name>[^\n]+?)\s+from\s+(?P<old>\S+)\s+to\s+(?P<new>\S+?)\.?[ \t]*$",
    re.MULTILINE,
)

#: Те же слова об одной зависимости внутри группы:
#:   Updates `org.springframework:spring-web` from 6.2.1 to 7.0.9
GROUPED = re.compile(
    r"^Updates\s+`(?P<name>[^`]+)`\s+from\s+(?P<old>\S+)\s+to\s+(?P<new>\S+?)\.?[ \t]*$",
    re.MULTILINE,
)

#: Таблица группового pull request'а:
#:   | [org.springframework:spring-web](https://github.com/...) | `6.2.1` | `7.0.9` |
TABLE_ROW = re.compile(
    r"^\|\s*\[(?P<name>[^\]]+)\]\([^)]*\)\s*\|\s*`(?P<old>[^`]+)`\s*\|\s*`(?P<new>[^`]+)`\s*\|",
    re.MULTILINE,
)

#: Версия в начале строки: цифры, разделённые точками. Суффиксы (`-jre`,
#: `-alpine`) отбрасываются: версия — это то, что до них.
CONCRETE = re.compile(r"^(\d+(?:\.\d+)*)")


def _rows(path: Path):
    """Строки таблицы: (зависимость, решение, диапазон, почему) и то, что не разобралось."""
    good = []
    broken = []
    if not path.exists():
        return good, broken
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 4 or not parts[0].strip() or not parts[3].strip():
            broken.append((number, "the row has no dependency, decision, versions or reason"))
            continue
        dependency, decision, versions = parts[0].strip(), parts[1].strip(), parts[2].strip()
        why = "\t".join(parts[3:]).strip()
        if decision not in DECISIONS:
            broken.append(
                (number, f"'{decision}' is not a decision: expected one of {', '.join(DECISIONS)}")
            )
            continue
        if _parse_range(versions) is None:
            broken.append(
                (number, f"'{versions}' is not a version range: expected `22 → 25` or `{ANY}`")
            )
            continue
        good.append((dependency, decision, versions, why))
    return good, broken


def _clean_name(name: str) -> str:
    """Имя зависимости без разметки: `` `name` `` и `[name](link)` — это `name`."""
    name = name.strip().strip("`").strip()
    link = re.match(r"^\[([^\]]+)\]\([^)]*\)$", name)
    if link:
        name = link.group(1)
    return name.strip()


def _pattern(text: str) -> tuple[str, ...] | None:
    """Компоненты шаблона версии: `22` → `('22',)`, `22.x` → `('22', 'x')`.

    None — если это не шаблон версии.
    """
    text = text.strip()
    if text == ANY:
        return None
    components = []
    for part in text.split("."):
        if part in ("x", "X", "*"):
            components.append("x")
        elif part.isdigit():
            components.append(part)
        else:
            return None
    return tuple(components)


def _concrete(text: str) -> tuple[str, ...] | None:
    """Компоненты конкретной версии: `25-jre` → `('25',)`, `5.7.0` → `('5', '7', '0')`."""
    match = CONCRETE.match(text.strip())
    if not match:
        return None
    return tuple(match.group(1).split("."))


def _side_matches(pattern: str, version: str) -> bool:
    """Совпадает ли конкретная версия с шаблоном одной стороны диапазона."""
    if pattern.strip() == ANY:
        return True
    expected = _pattern(pattern)
    if expected is None:
        return False
    actual = _concrete(version)
    if actual is None or len(actual) < len(expected):
        return False
    for wanted, got in zip(expected, actual):
        if wanted == "x":
            continue
        if wanted != got:
            return False
    return True


def _parse_range(text: str) -> tuple[str, str] | None:
    """Диапазон версий: `22 → 25` или `*`. None — если это не диапазон."""
    text = text.strip()
    if text == ANY:
        return (ANY, ANY)
    match = RANGE.match(text)
    if not match:
        return None
    return (match.group(1).strip(), match.group(2).strip())


def _versions(versions) -> tuple[str, str] | None:
    """Приведённые к паре версии запроса: строка `22 → 25` или пара `('22', '25')`."""
    if versions is None:
        return None
    if isinstance(versions, str):
        return _parse_range(versions)
    return (str(versions[0]).strip(), str(versions[1]).strip())


def _matching(rows, dependency: str):
    """Строки, которые относятся к зависимости: сначала точное имя, потом начало имени."""
    exact = [row for row in rows if row[0] == dependency]
    if exact:
        return exact
    globs = [
        row for row in rows if row[0].endswith("*") and dependency.startswith(row[0][:-1])
    ]
    globs.sort(key=lambda row: len(row[0]), reverse=True)
    return globs


def _range_matches(recorded: str, wanted: tuple[str, str]) -> bool:
    """Совпадает ли записанный диапазон с запрошенным."""
    record = _parse_range(recorded)
    if record is None:
        return False
    return _side_matches(record[0], wanted[0]) and _side_matches(record[1], wanted[1])


def decision_for(dependency: str, versions=None, path: Path = TABLE) -> str:
    """Решение по зависимости: `none` или `<решение>\t<почему>`.

    `versions` — диапазон бампа, к которому относится решение. Без него
    находится любая запись по этой зависимости; с ним — только та, чей
    диапазон совпадает.
    """
    good, _ = _rows(path)
    candidates = _matching(good, dependency)
    if not candidates:
        return NONE
    if versions is not None:
        wanted = _versions(versions)
        if wanted is None:
            return NONE
        candidates = [row for row in candidates if _range_matches(row[2], wanted)]
        if not candidates:
            return NONE
    _, decision, _, why = candidates[0]
    return f"{decision}\t{why}"


def dependencies_of(body: str) -> list[tuple[str, str, str]]:
    """Зависимости, которые несёт тело pull request'а: (имя, откуда, куда)."""
    found: list[tuple[str, str, str]] = []
    seen: set[str] = set()

    def add(name: str, old: str, new: str) -> None:
        name = _clean_name(name)
        if not name or name in seen:
            return
        seen.add(name)
        found.append((name, old.strip(), new.strip()))

    for match in SINGLE.finditer(body):
        add(match.group("name"), match.group("old"), match.group("new"))
    for match in TABLE_ROW.finditer(body):
        add(match.group("name"), match.group("old"), match.group("new"))
    for match in GROUPED.finditer(body):
        add(match.group("name"), match.group("old"), match.group("new"))
    return found


def decision_for_pull_request(body: str, path: Path = TABLE) -> str:
    """Решение по pull request'у: по зависимости, которую несёт его тело.

    `none` — решения нет, `unknown` — зависимость из тела прочитать не
    удалось, и решение могло быть, но его не нашли.
    """
    candidates = dependencies_of(body)
    if not candidates:
        return UNKNOWN
    for name, old, new in candidates:
        found = decision_for(name, (old, new), path)
        if found != NONE:
            return found
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


def _usage() -> int:
    print(__doc__)
    return 1


def main(argv: list[str]) -> int:
    arguments = argv[1:]
    if not arguments:
        return _usage()
    if arguments[0] == "--check":
        if len(arguments) != 1:
            return _usage()
        return check()
    if arguments[0] == "--pull-request":
        if len(arguments) != 2:
            return _usage()
        body = Path(arguments[1])
        # Тела может не оказаться — тогда зависимость не прочитать, и это
        # «unknown», а не падение: workflow должен сказать об этом, а не
        # упасть на пустом файле.
        print(decision_for_pull_request(body.read_text(encoding="utf-8") if body.exists() else ""))
        return 0
    dependency = arguments[0]
    versions = None
    if len(arguments) == 2:
        versions = _parse_range(arguments[1])
        if versions is None:
            print(f"'{arguments[1]}' is not a version range: expected `22 → 25`", file=sys.stderr)
            return 1
    elif len(arguments) == 3:
        versions = (arguments[1], arguments[2])
    elif len(arguments) != 1:
        return _usage()
    print(decision_for(dependency, versions))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

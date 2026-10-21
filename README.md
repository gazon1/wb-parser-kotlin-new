# wb-parser-kotlin

Краулер каталога Wildberries: обходит цели, достаёт товары, складывает их в
PostgreSQL и отдаёт через read-модель каталога. Kotlin, Gradle, Spring Boot,
Ktor, Exposed, Flyway, PostgreSQL.

Репозиторий публичный, код распространяется по лицензии Apache-2.0 (см.
раздел «Лицензия»).

## Что внутри

| Модуль | Что это |
|---|---|
| `wbparser/` | ядро краулера: конвейер, модели, HTTP-клиент, репозитории. Без Spring. |
| `app/` | Spring Boot 3.4.1: REST-контроллеры, планировщик, миграции Flyway, каталог. |
| `tests/` | интеграционные тесты на настоящем PostgreSQL (Testcontainers). |
| `scripts/` | гейты, операторские скрипты, разбор бампов Dependabot. |
| `docs/decisions/` | почему так, а не иначе: ADR и их выжимка `DIGEST.md`. |

Карта проекта — в [`AGENTS.md`](AGENTS.md); словарь терминов — в
[`docs/CONTEXT.md`](docs/CONTEXT.md); операторские инструкции — в
[`docs/RUNBOOK.md`](docs/RUNBOOK.md).

## Как проверить

Единственная точка входа — `./check.sh`. Это 11 гейтов: статика, тесты всех
модулей, покрытие, линтер, здоровье зависимостей, рантайм-класпат.

```bash
./check.sh              # всё
SKIP_DB=1 ./check.sh    # без Testcontainers (если нет Docker)
```

Гейты живут в `scripts/ci/static-gates.sh` и вызываются и отсюда, и из CI —
одним реестром, чтобы гейт нельзя было забыть в одном из двух мест.

## Правила работы с ветками

- **Одно решение — один pull request.** В pull request'е — одно решение и
  ничего кроме него; решение, которое не помещается, делится до того, как
  пишется код.
- **Ветка — до ~5 коммитов и ~20 файлов.** Дальше решение перестаёт
  читаться, а конфликт перестаёт быть конфликтом.
- **Перед открытием pull request'а — rebase на свежий `main`.** Не merge
  `main` в ветку: rebase переносит решение целиком на актуальную базу и делает
  это один раз — до того, как решение начали читать.
- **Следующий pull request — от свежего `main`,** а не от текущей ветки:
  иначе решение наследует чужую историю и чужие конфликты.

Почему именно так — в
[`docs/decisions/2026-10-21-one-decision-is-one-pull-request.md`](docs/decisions/2026-10-21-one-decision-is-one-pull-request.md).

## Решения

`docs/decisions/DIGEST.md` — компактная выжимка правил проекта; каждый
решение подробно — в своём файле `docs/decisions/YYYY-MM-DD-*.md`. Запись
нового решения и пересборка выжимки:

```bash
./scripts/refresh-decisions-digest.sh
```

## Лицензия

**Apache License 2.0** — полный текст в файле [`LICENSE`](LICENSE), дословно
по SPDX. Файла `NOTICE` в проекте нет, и Apache-2.0 требует его только
тогда, когда такой файл есть.

Почему именно эта лицензия — в
[`docs/decisions/2026-10-21-the-repository-is-licensed.md`](docs/decisions/2026-10-21-the-repository-is-licensed.md).

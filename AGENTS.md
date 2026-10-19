# wb-parser-kotlin — Agent Cheatsheet

> Этот файл загружается автоматически в каждый чат с агентом.
> Читай `docs/decisions/DIGEST.md` **перед любой нетривиальной задачей** — это
> компактная выжимка всех принятых решений и правил проекта.

## Структура проекта / Project Structure

```
wb-parser-kotlin/
├── wbparser/       — crawler module (java-library): Stage/Side/Interpreter, модели,
│                     Repo interfaces, Ktor client, Exposed. Чистая логика без Spring.
├── app/            — Spring Boot 3.4.1: REST-контроллеры, Scheduler, Flyway migrations
└── tests/          — integration tests (Testcontainers, реальная БД) + гейт старта приложения
```

Граница `wbparser → app` явная: `wbparser` — `java-library` (`api` — только то, что
действительно в типе публичного API модуля), `app` — потребитель. Flyway-миграции
живут в `app`, `app` зависит от `wbparser`, обратной зависимости нет.

## Gradle команды / Gradle Commands

```bash
# Полная проверка (перед каждым commit)
just tests::check      # или: ./check.sh — единственная точка входа
just t                 # алиас

# ./check.sh — 10 нумерованных шагов. Шаг [1] вызывает scripts/ci/static-gates.sh,
# тот же реестр, что джоб `static` в ci.yml: один реестр, два вызывающих.

# Модульные тесты
just wbparser::test    # crawler module unit tests
just tests::common     # integration tests

# Зависимости
just deps::health      # DAGP: падает на новых находках
just deps::report      # текстовый отчёт DAGP

# Сборка / Build
just app::build bootJar    # собрать JAR
just app::run              # запустить локально (Spring Boot)
just app::docker-build     # Docker image
just app::run-docker       # docker-compose up

# Отладка / Debug (локально, VPS не трогаем)
docker compose -f deploy/docker-compose.debug.yml up   # приложение + БД + JDWP :5005

# Деплой / Deploy → 91.188.212.39
just deploy                # ./check.sh → Ansible (image already in GHCR from CI)
just deploy <tag>          # задеплоить уже запушенный тег
just deploy::build         # one-shot: build + push image (минуя CI)
just deploy::dry-run <tag> # ansible --check --diff

# Just aliases: b=build, br=run, td=wbparser::test, ti=wbparser::test-verbose,
#               ld=wbparser::lint, li=wbparser::lint, t=tests::check, d=deploy
```

## 🤖 Decision log workflow

Перед началом любой нетривиальной задачи **прочитай `docs/decisions/DIGEST.md`** — это
компактная выжимка Critical-правил из всех ADR.

### Когда писать новую запись / When to write an ADR

- Выбор между несколькими разумными вариантами.
- Non-obvious workaround (статическая инициализация, JVM-баг, generic-type баг).
- Изменился контракт между модулями / слоями.
- Пользователь явно попросил зафиксировать рассуждение.

**Когда НЕ писать:** опечатки, форматирование, ренеймы без изменения поведения.

### Формат / ADR format

```markdown
---
title: "Short declarative title — what we chose"
date: YYYY-MM-DD
tags: [pipeline, architecture]
---

## Context
What was the situation? What was broken or suboptimal?

## Idea
What were the alternatives? (1–3 bullets)

## Decision
What we actually did. Present tense, declarative.

## Rationale
Why this over the others. Trade-offs accepted.

## Consequences
Bulleted list. Rules go here — use **Always** / **Never** / **MUST** markers
so they surface in DIGEST.md Critical section.
- **Always** do X
- **Never** do Y
- **MUST** Z
```

### Refresh workflow

После создания/изменения ADR:
```bash
./scripts/refresh-decisions-digest.sh    # обновить DIGEST.md
```
Commit с ADR **всегда** включает обновлённый `DIGEST.md` в том же commit.

---

## Гейты / Gates

Единственный реестр — `scripts/ci/static-gates.sh`. Его вызывают и `./check.sh`,
и джоб `static` в CI. **Always** добавлять гейт одной строкой `gate <blocking|advisory> <name> <cmd>`;
**Never** не вызывать гейт напрямую из `ci.yml` — тогда он не попадёт в локальный цикл
(в репозитории был гейт, живший только в CI, и `scripts/check.sh`, живший только локально
и падавший на `./gradlew` из-за `ROOT`, указывавшего на `scripts/`).

- `|| true` на гейте запрещён (часть D). Неблокирующий гейт объявляется как `advisory`.
- Каждый гейт обязан иметь `--self-test` либо строку с обоснованием в
  `config/gates/control-exemptions.tsv` (часть F).
- Расхождение между локальным циклом и CI объявляется в `config/gates/asymmetries.tsv` (часть E).
- Baseline detekt может только уменьшаться; рост — `--allow-growth --reason '…'`
  (`scripts/check-baseline-ratchet.py`).
- «Тесты прошли» ≠ «тесты выполнились»: `scripts/check-test-runs.py` сверяет JUnit XML
  с полом в `config/gates/test-runs-floor.txt`. Гейт **MUST** идти после тестов.

## ❌ Что НЕ делать / What NOT to do

1. **`!!` (force-unwrap) в prod-коде** — используй `require` / `check` с явным `IllegalStateException`, или nullable + let/also
2. **`delay(-1L)` как sentinel** — `delayMs = -1L` невалидный контракт; используй `null` или `Optional.delayMs`
3. **Подключать side-эффекты в обход `SideInterpreterRegistry`** — `ScheduleRetryInterpreter` и `SaveBatchInterpreter` **MUST** быть зарегистрированы в каждом `runTarget` (pipeline ADR)
4. **Игнорировать `Side.Log(ERROR)`** — в save-стадии `else -> {}` молчаливо проглатывает ошибки;StageFailure — тоже logging
5. **`runCatching` без обработки** — ошибка должна либо попасть в `StageFailure`, либо быть залогирована через `LogInterpreter`
6. **Мокки / Mockito** — используй Fake* классы (fake over mock convention)
7. **Чистый код в `wbparser/`** — Ktor client, Exposed-запросы и доменная логика живут в `wbparser/`; Spring-специфичное (REST, Scheduler, Flyway-миграции) — в `app/`. Модуль `wbparser` — `java-library` и не должен тянуть Spring Boot в `api`.
8. **CHANGELOG.md** — не создавай; git log + PR-серии в commit messages покрывают
9. **Save-стадия без retry-loop** — DB/HTTP/внешние ресурсы должны использовать retry-loop (per PR 11, G10). Транзитный DB-блип не должен ронять весь crawl.
10. **`compileOnly` для класса, который грузится в рантайме** — `compileOnly` означает «нет в рантайм-класспате». `exposed-java-time` был объявлен так, при этом `tests/` добавлял его обратно как test-зависимость: все тесты зелёные, а прод падает на первом же запросе к таблице. Тип колонки в схеме — не compile-time метаданные, его вызывают при каждом чтении и записи. **Always** проверять рантайм-класспат сборкой `bootJar`, а не только тестами.
11. **`exitProcess()` в `main()` после `SpringApplication.run()`** — `run()` для веб-сервера возвращается почти сразу, и `exitProcess` убивал JVM через ~70 мс после «Started». В серверном режиме `main()` просто возвращается: Tomcat держит процесс на non-daemon-потоках. Выходить явно нужно только там, где веб-сервера нет (`web-application-type: none`, one-shot-профиль).
12. **Расширять exclude-лист DAGP** — список принятых находок в `build.gradle.kts` может только уменьшаться. Новая запись — только с причиной в ADR. Запись удаляется в том же коммите, что чинит находку.
13. **Запускать `fixDependencies` в CI** — он переписывает build-скрипты и не видит runtime-only зависимости. Только вручную, с ревью диффа.
14. **Объявлять в `implementation` тип, который утекает в публичную сигнатуру** — такой тип должен быть в `api` модуля `wbparser`.

---

## Ключевые ADR (читать перед работой с этими областями)

| Файл | О чём |
|---|---|
| `docs/decisions/2026-09-08-functional-pipeline-refactor.md` | Stage/Side/Interpreter модель; **Always** регистрировать все интерпретаторы |
| `docs/decisions/2026-09-08-domain-coverage-pr4.md` | Domain unit tests; **MUST** тестировать `domain/pipeline/**` |
| `docs/decisions/2026-09-08-conventional-commits-and-cleanup-prs.md` | Commit conventions: `feat:`, `fix:`, `refactor:`, `chore:`, `test:`, `docs:` + `PR N` |
| `docs/decisions/2026-10-19-dependency-analysis-plugin.md` | DAGP как гейт: baseline = exclude-лист, который только уменьшается; `fixDependencies` — только вручную |
| `docs/decisions/2026-10-19-application-startup-gate.md` | Приложение обязано стартовать, и что-то обязано это доказывать (`@SpringBootTest` + Testcontainers) |
| `docs/decisions/2026-10-19-explicit-module-boundary.md` | Граница `wbparser → app` объявлена (`java-library`, `api`/`runtimeOnly`), а не подразумевается |

---

## Tech-debt plan (актуальный)

Кластеры из `.zcode/plans/plan-sess_*.md`:

- **A** — runtime bugs: `SideInterpreterRegistry` пустой в `crawl.kt`, `SaveBatchInterpreter` dead code, `delayMs = -1L` spin-loop
- **B** — purity: `UUID.randomUUID()` и `delay()` внутри `Pipeline.run()`
- **C** — мёртвый код: `StopReason.kt`, `StageFailure.Stopped`
- **F** — (?) см. план

Полный план: `.zcode/plans/plan-sess_871f29cf-f859-4a72-9002-5bbafd737630.md`

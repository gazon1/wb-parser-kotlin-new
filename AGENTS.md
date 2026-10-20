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

# ./check.sh — 11 нумерованных шагов. Шаг [1] вызывает scripts/ci/static-gates.sh,
# тот же реестр, что джоб `static` в ci.yml: один реестр, два вызывающих.

# JDK: версия 22 задана один раз — в root build (`subprojects { }` → `jvmToolchain(22)`).
# Toolchain сам скачивает нужный JDK (foojay-резолвер в settings.gradle.kts), ставить
# ничего не нужно. CI и Docker-образы пинят 22 явно.

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

# Линт / Lint
just lint::autofix      # применить правки detekt (--auto-correct) и ktlint (ktlintFormat)
just lf                 # алиас

# Деплой / Deploy → 91.188.212.39
just deploy                # ./check.sh → Ansible (image already in GHCR from CI)
just deploy <tag>          # задеплоить уже запушенный тег
just deploy::build         # one-shot: build + push image (минуя CI)
just deploy::dry-run <tag> # ansible --check --diff

# Just aliases: b=build, br=run, td=wbparser::test, ti=wbparser::test-verbose,
#               ld=wbparser::lint, li=wbparser::lint, lf=lint::autofix,
#               t=tests::check, d=deploy
```

## CI-поверхности / CI surfaces

Четыре workflow'а — не украшение, а часть гарантий репозитория. Их связность
проверяет гейт `scripts/ci/check-ci-workflows.py` (в реестре
`scripts/ci/static-gates.sh`), поэтому удаление или «опустошение» любого из них —
это finding, а не тихая потеря.

| Workflow | Что делает | Когда |
|---|---|---|
| `.github/workflows/ci.yml` | пайплайн: gates, сборка, тесты, покрытие, линт, публикация образа. Вызывается как `workflow_call` | push, PR, dispatch, вызов из nightly |
| `.github/workflows/nightly.yml` | **точка входа** ночного прогона: `schedule: 0 3 * * *` + `workflow_dispatch`, вызывает `ci.yml` с `alert_on_failure: true` | schedule, dispatch |
| `.github/workflows/codeql.yml` | CodeQL: `java-kotlin` (по скомпилированному коду) + `actions` (сами workflow'и) → code scanning | push, PR, еженедельно, dispatch |
| `.github/workflows/scorecard.yml` | OpenSSF Scorecard → SARIF в code scanning + публикация в Scorecard API | push на main, еженедельно, dispatch |
| `.github/workflows/lint-autofix.yml` | применяет `scripts/ci/lint-autofix.sh` и **предлагает** правки (ветка `lint-autofix/<run-id>` + PR/комментарий) | push, PR, dispatch |

Правила, которые из этого следуют:

- **Always** определять пайплайн один раз и вызывать его: расписание — это
  точка входа, а не свойство пайплайна. Расписание внутри пайплайна — копия
  точки входа, а копия устаревает.
- **Never** давать health-check права релиза: ночной прогон не пушит образ
  и не получает `packages: write`.
- **Never** пушить авто-фикс в ветку, над которой работает человек: правки
  предлагаются, решение — за человеком.
- **Never** считать авто-фикс гейтом: `ktlintCheck` и `detekt` остаются гейтом,
  а находка, которую нельзя исправить автоматически, всё равно валит сборку.
- **Always** запускать корректор одним Gradle-вызовом на модуль и одной задачей
  на вызов: опция задачи (`--auto-correct`) достаётся только последней
  подходящей задаче инвокации.
- Авто-фикс **никогда** не трогает detekt-baseline: baseline — это долг, а не
  форматирование (см. `scripts/check-baseline-ratchet.py`).

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
12. **Расширять exclude-лист DAGP** — список принятых находок в `build.gradle.kts` может только уменьшаться **по существу**: запись, описывающая упаковку зависимости (starter — pom без классов; агрегатор — неразделимая на части), неустранима никаким изменением в этом репозитории и остаётся; запись, скрывающая находку, которую закрыла бы строка кода, запрещена. Новая запись — только с причиной в ADR. Запись удаляется в том же коммите, что чинит находку.
13. **Запускать `fixDependencies` в CI** — он переписывает build-скрипты и не видит runtime-only зависимости. Только вручную, с ревью диффа.
14. **Объявлять в `implementation` тип, который утекает в публичную сигнатуру** — такой тип должен быть в `api` модуля `wbparser`.
15. **Пинить `org.gradle.java.home` в закоммиченном `gradle.properties`** — путь к JDK одного разработчика делает репозиторий несобираемым на любой другой машине. Версия задаётся toolchain'ом в root build, а локальный JDK подтягивается foojay-резолвером.

---

## Ключевые ADR (читать перед работой с этими областями)

| Файл | О чём |
|---|---|
| `docs/decisions/2026-09-08-functional-pipeline-refactor.md` | Stage/Side/Interpreter модель; **Always** регистрировать все интерпретаторы |
| `docs/decisions/2026-09-08-domain-coverage-pr4.md` | Domain unit tests; **MUST** тестировать `domain/pipeline/**` |
| `docs/decisions/2026-09-08-conventional-commits-and-cleanup-prs.md` | Commit conventions: `feat:`, `fix:`, `refactor:`, `chore:`, `test:`, `docs:` + `PR N` |
| `docs/decisions/2026-10-19-dependency-analysis-plugin.md` | DAGP как гейт: baseline = exclude-лист, уменьшающийся по существу (структурные записи неустранимы); `fixDependencies` — только вручную |
| `docs/decisions/2026-10-19-application-startup-gate.md` | Приложение обязано стартовать, и что-то обязано это доказывать (`@SpringBootTest` + Testcontainers) |
| `docs/decisions/2026-10-19-explicit-module-boundary.md` | Граница `wbparser → app` объявлена (`java-library`, `api`/`runtimeOnly`), а не подразумевается |
| `docs/decisions/2026-10-19-jdk-toolchain.md` | Версия JDK — одно число в одном месте; toolchain сам себя обеспечивает |
| `docs/decisions/2026-10-20-the-coverage-floor-measures-the-code-not-the-module.md` | Floors меряются по merged-отчёту с фильтром по пакетам: отчёт модуля не видит покрытие чужих сьютов |
| `docs/decisions/2026-10-20-the-big-classes-are-split-by-decision.md` | Крупные классы делятся по решению, которое они держат: `WbCatalogRequest`/`WbCatalogApi`/`WbCatalogFetchScript`, `Pipeline`/`PipelineBatch`/`PipelinePageProcessor`, `CrawlRunner`/`CrawlJobStore`/`TargetCrawl`/`CrawlMetrics` |

---

## Tech-debt plan (актуальный)

Кластеры A–G прежнего плана (runtime-баги, purity, мёртвый код, дублирование,
инфраструктура, покрытие, tooling) **разобраны** — состояние по каждому пункту в файле
плана. Осталось:

- **BrowserPool** (943 строки) — следующий кандидат на дробление по решению; заодно
  худшее покрытие в модуле (`RealAdsPowerApi` 6 %, `BrowserDownloader` 14 %)
- **Браузерный путь fetch** — не покрыт: ошибка там выглядит как «каталог пуст», а не
  как ошибка
- **Эксплуатационное**: пересборка прод-БД, честный `known_pages_limit`, доля HTTP 498
  (все профили на одном egress IP), права токена для `site-compatibility`

Полный план: `.zcode/plans/plan-sess_871f29cf-f859-4a72-9002-5bbafd737630.md`

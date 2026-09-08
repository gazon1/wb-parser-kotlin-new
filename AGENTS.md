# wb-parser-kotlin — Agent Cheatsheet

> Этот файл загружается автоматически в каждый чат с агентом.
> Читай `docs/decisions/DIGEST.md` **перед любой нетривиальной задачей** — это
> компактная выжимка всех принятых решений и правил проекта.

## Структура проекта / Project Structure

```
wb-parser-kotlin/
├── domain/          — чистый Kotlin: Stage/Side/Interpreter, модели, Repo interfaces
├── infrastructure/  — Ktor client + Exposed + Flyway migrations
├── app/              — Spring Boot 3.4.1, REST-контроллеры, Scheduler
└── tests/           — integration tests (Testcontainers, реальная БД)
```

## Gradle команды / Gradle Commands

```bash
# Полная проверка (перед каждым commit)
just tests::check      # или: ./check.sh
just t                 # алиас

# Модульные тесты
just domain::test      # domain module unit tests
just infra::test       # infrastructure module unit tests
just tests::common     # integration tests

# Сборка / Build
just app::build bootJar    # собрать JAR
just app::run              # запустить локально (Spring Boot)
just app::docker-build     # Docker image
just app::run-docker       # docker-compose up

# Just aliases: b=build, br=run, td=domain::test, ti=infra::test, t=tests::check
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

## ❌ Что НЕ делать / What NOT to do

1. **`!!` (force-unwrap) в prod-коде** — используй `require` / `check` с явным `IllegalStateException`, или nullable + let/also
2. **`delay(-1L)` как sentinel** — `delayMs = -1L` невалидный контракт; используй `null` или `Optional.delayMs`
3. **Подключать side-эффекты в обход `SideInterpreterRegistry`** — `ScheduleRetryInterpreter` и `SaveBatchInterpreter` **MUST** быть зарегистрированы в каждом `runTarget` (pipeline ADR)
4. **Игнорировать `Side.Log(ERROR)`** — в save-стадии `else -> {}` молчаливо проглатывает ошибки;StageFailure — тоже logging
5. **`runCatching` без обработки** — ошибка должна либо попасть в `StageFailure`, либо быть залогирована через `LogInterpreter`
6. **Мокки / Mockito** — используй Fake* классы (fake over mock convention)
7. **Чистый код в `infrastructure/`** — Exposed queries, Ktor client, Flyway migrations; domain-only логика должна жить в `domain/`
8. **CHANGELOG.md** — не создавай; git log + PR-серии в commit messages покрывают
9. **Save-стадия без retry-loop** — DB/HTTP/внешние ресурсы должны использовать retry-loop (per PR 11, G10). Транзитный DB-блип не должен ронять весь crawl.

---

## Ключевые ADR (читать перед работой с этими областями)

| Файл | О чём |
|---|---|
| `docs/decisions/2026-09-08-functional-pipeline-refactor.md` | Stage/Side/Interpreter модель; **Always** регистрировать все интерпретаторы |
| `docs/decisions/2026-09-08-domain-coverage-pr4.md` | Domain unit tests; **MUST** тестировать `domain/pipeline/**` |
| `docs/decisions/2026-09-08-conventional-commits-and-cleanup-prs.md` | Commit conventions: `feat:`, `fix:`, `refactor:`, `chore:`, `test:`, `docs:` + `PR N` |

---

## Tech-debt plan (актуальный)

Кластеры из `.zcode/plans/plan-sess_*.md`:

- **A** — runtime bugs: `SideInterpreterRegistry` пустой в `crawl.kt`, `SaveBatchInterpreter` dead code, `delayMs = -1L` spin-loop
- **B** — purity: `UUID.randomUUID()` и `delay()` внутри `Pipeline.run()`
- **C** — мёртвый код: `StopReason.kt`, `StageFailure.Stopped`
- **F** — (?) см. план

Полный план: `.zcode/plans/plan-sess_871f29cf-f859-4a72-9002-5bbafd737630.md`

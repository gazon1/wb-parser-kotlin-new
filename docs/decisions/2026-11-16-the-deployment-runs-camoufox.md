---
title: "Развёртывание запускает локальный Camoufox и не несёт AdsPower"
date: 2026-11-16
tags: [architecture, operations, browser, deployment]
supersedes: 2026-10-19-adspower-local-api-port-discovery
supersedes: 2026-10-19-adspower-api-first-browser-resolution
supersedes: 2026-10-17-browserpool-testability-adspower-api-interface
supersedes: 2026-10-23-the-reconciliation-covers-every-profile-and-is-retried-until-confirmed
supersedes: 2026-10-20-the-crawler-and-its-browser-share-one-network-namespace
supersedes: 2026-10-19-local-debug-stack-repairs
supersedes: 2026-10-23-the-adapters-are-checked-against-a-real-chrome
supersedes: 2026-10-17-browser-automation-interface
supersedes: 2026-10-23-the-probe-asks-three-questions
supersedes: 2026-10-21-the-profile-id-is-read-too
supersedes: 2026-10-22-egress-groups-are-declared-and-checked
supersedes: 2026-11-02-the-protocol-runs-from-the-repository
supersedes: 2026-10-20-browsers-are-closed-when-they-are-not-needed
supersedes: 2026-10-22-a-browser-that-may-be-running-is-stopped-when-it-is-not-needed
---

## Context

## 271 — последний шаг umbrella #266. #268 дал контракт сессии, #269 реализовал

локальный Camoufox, #270 привёл в порядок состояние профиля и политику отказов.
Но production entry points всё ещё собирали `BrowserPool` с `AdsPowerAutomation`:
пул строил CDP-адаптер по умолчанию, `BrowserConfig` создавал `AdsPowerApi`,
`AdsPowerPreflightRunner` проверял установку AdsPower, `AdsPowerBrowserReconciler`
сверял браузеры на старте, а `EgressProbe` сверял объявленный прокси с ответом
Local API. Offline-инструменты замера тоже водили Chrome через AdsPower.

### Idea

- (1) Оставить два рантайма и переключать их свойством `crawler.spider.runtime`.
- (2) Заменить default на Camoufox, оставив AdsPower как совместимый путь.
- (3) Удалить AdsPower целиком: пул строит Camoufox, внешнего рантайма нет.

### Decision

**Номер (3).**

**Пул строит Camoufox.** `BrowserPool` создаёт `CamoufoxAutomation` на каждый
профиль; шов `browserAutomations` остаётся только для тестов. Установка приходит
провайдером `() -> CamoufoxInstall` и резолвится при первом запуске браузера:
процесс, который никогда не занимает страницу (catalog-only, юнит-тест), не
требует установленного Camoufox. Bootstrap-гейт не зависит от браузера.

**Внешнего рантайма больше нет, и сверять нечего.** `BrowserReconciler`,
`BrowserLifecycle`, `AdsPowerApi`, `AdsPowerPreflight`, порт-дискавери, Chrome
endpoint probe и CDP-адаптер удалены. Каталог профиля занимает
`CamoufoxAutomation.OpenProfiles` внутри процесса; осиротевший после SIGKILL
процесс убирает оператор (`pgrep -af camoufox-bin`), а не HTTP-сверка.

**Статическая сверка прокси удалена.** Локальный браузер запускается с
объявленным прокси, поэтому «объявлено» и «фактически» — одно и то же. Осталась
живая проверка адреса (`EgressAddressProbe`) и проверка изоляции
(`EgressIsolationCheck`); `EgressPreflightRunner` сохраняет прежнее правило:
предупреждение по умолчанию, отказ при `require-isolated-egress-groups`.

**Пин сборки и install — одна команда.** `scripts/camoufox.sh install|verify`
пинит версию пакета и сборку; `just browser::install|verify`. Runtime читает
`active_version` из `~/.cache/camoufox/config.json` и отказывает при
несовпадении. CI браузер не ставит: smoke-тест с настоящим Camoufox
пропускается там, где сборки нет.

**Один образ и один сервис.** `docker.adspower`, `deploy/docker.adspower`,
`deploy/docker-compose.adspower.yml` и второй образ `wb-parser-adspower`
удалены; `deploy/docker-compose.vps.yml` содержит только `wb-parser`. Профили
и fingerprint'ы живут на рабочей машине, VPS даёт только proxy endpoints.

**Offline-инструменты замера — Camoufox.** AdsPower-скрипты
(`adspower_utils.py`, `wb_browser_fetch.py`, `measure_egress.py`,
`measure_catalog_length.py`) удалены вместе с рантаймом. Текущий инструмент
замера — `scripts/camoufox_bench.py`; блок-раннер протокола вернётся в #274.

### Rationale

Два рантайма — это две конфигурации, два пути отказа и два набора тестов,
которые обязаны совпадать. Свойство-переключатель не делает ни один из них
проверенным: оно делает непроверенным тот, который сейчас выключен. #269 и #270
уже доказали локальную схему; держать AdsPower «на всякий случай» — значит
платить за неё контекстом на каждом изменении.

Ленивый резолв установки — не оптимизация, а сохранение bootstrap-гейта:
`ApplicationStartupTest` и catalog-only профиль существуют именно для того,
чтобы приложение поднималось без браузера.

### Consequences

- **Always** строить `BrowserAutomation` профиля из Camoufox в `BrowserPool`; шов для фейков — только тестовый.
- **Never** добавлять второй browser runtime, второй process manager или второй profile claim.
- **Always** резолвить `CamoufoxInstall` лениво, при первом запуске браузера.
- **Never** делать старт приложения зависимым от установленного Camoufox, доступности WB или сети.
- **Never** возвращать startup-сверку браузеров для локального рантайма: осиротевший процесс убирает оператор.
- **Always** пинить одну сборку Camoufox и проверять её `just browser::verify`.
- **Never** публиковать browser image или поднимать browser-service на VPS: браузер запускается на рабочей машине.
- **Always** хранить профили и fingerprint'ы вне репозитория (`PROFILES_DIR`, `*.fingerprint.json`).
- **Never** хранить proxy credentials в репозитории; они приходят из env и вырезаются `toString()`.
- **Always** сохранять `EgressProbe`/`EgressIsolationCheck` и startup gate.
- **Never** оставлять AdsPower-специфичный offline-скрипт без подтверждённого use case: инструмент замера — `scripts/camoufox_bench.py`.

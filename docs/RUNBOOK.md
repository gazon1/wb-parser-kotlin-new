# RUNBOOK — wb-parser-kotlin

Операционный документ для человека на дежурстве: как запустить, как проверить, как
задеплоить и что делать, когда данные выглядят неправильно. Правила проекта и история
решений — в `AGENTS.md` и `docs/decisions/DIGEST.md`; этот документ не пересказывает ADR,
а ссылается на них по имени файла.

---

## 1. Что делает эта система

Краул обходит каталоги Wildberries через внутренний API витрины
`__internal/u-search/exactmatch/ru/common/v18/search`, выбирая категорию **токеном меню WB**
(`crawl_targets.wb_search_query`, например `menu_v3_9467 карты памяти`), и складывает товары
в PostgreSQL: `scraped_items` (append-only: строка на «цель × товар × цена»),
`category_mappings`, `crawl_jobs` (журнал прогонов), `wb_subjects` (справочник предметов WB).
Приложение — Spring Boot 3.4.1: REST (`/api/health/**`, `/api/catalog/**`, `/api/admin/**`),
планировщик обхода, миграции Flyway. Краул — отдельный модуль `wbparser` (`java-library`,
без Spring). Каталог целей — 132 листа в `wb_catalog_targets.yaml`, у каждого
`known_pages_limit: 60` — измеренная длина каталога (§8(f)).

**Честная оговорка.** Продовую базу (91.188.212.209) наполняли старым эндпоинтом
`__internal/u-recom/personal/v9/search` с `resultset=catalog&sort=popular` и без фильтра по
каталогу: он отдаёт «популярную ротацию» WB — пул примерно 1200 товаров, не связанных с
запрошенной категорией. В базе лежат эти товары, разложенные по 132 чужим названиям
(56 509 строк, 1178 уникальных `product_id`). **Эти данные нужно пересобрать после merge
ветки** — §9 и `docs/decisions/2026-10-20-wb-catalog-api-endpoint-and-token.md`.

---

## 2. Предпосылки

| Что | Значение | Источник |
|---|---|---|
| JDK | 22; задаётся один раз — `subprojects { } → jvmToolchain(22)` в root build. Toolchain сам скачивает JDK (foojay-резолвер в `settings.gradle.kts`) | `docs/decisions/2026-10-19-jdk-toolchain.md` |
| Docker + docker compose | debug-стек, Testcontainers-тесты, реестр образов | `deploy/docker-compose.debug.yml` |
| AdsPower Local API | обязателен: краул водит **свой Chrome AdsPower** через CDP, Chromium из Playwright не скачивает | `app/src/main/kotlin/ru/wbparser/app/config/CrawlerConfig.kt` |
| AdsPower-профили | список в `application.yml → crawler.spider.egress-groups` (по профилю на группу); в комментариях помечены неактивные | `app/src/main/resources/application.yml` |
| Меню WB | `https://static-basket-01.wbbasket.ru/vol0/data/main-menu-by-ru-v3.json` — источник токенов `searchQuery` | `scripts/wb-catalog/enrich_catalog_queries.py` |
| Справочник предметов | `https://static-basket-01.wbbasket.ru/vol0/data/subjects.json` | `scripts/wb-catalog/load_subjects.py` |
| Python | ≥ 3.11, зависимости через `uv` (`pyproject.toml`, `uv.lock`) | `pyproject.toml` |

### Переменные окружения

Значения берутся из `.env` (файл в `.gitignore`, образец — `.env.example`). **Секретов в
репозитории нет; здесь имена переменных, а не значения.**

| Переменная | Где используется | Комментарий |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `spring.datasource` и `crawler.database` | `DB_NAME` должен совпадать с базой, из которой читает витрина; legacy-стек .NET использовал `webcrawler` — указание на неё даёт пустой каталог без ошибки |
| `ADMIN_API_KEY` | `AdminApiKeyFilter` | **Обязательна**: без неё приложение падает на старте (`IllegalStateException`). Ключ передаётся в заголовке `X-Api-Key` |
| `ADMIN_PASSWORD`, `ADMIN_USER` | `spring.security.user` | Basic-auth для admin API; предпочтительнее `X-Api-Key` |
| `ADSPOWER_API_KEY` | AdsPower Local API | Требуется для обхода |
| `ADSPOWER_API_BASE` | `AdsPowerApiResolver` | Переопределяет авто-обнаружение порта. Известные порты: 50325 (десктоп-приложение), 50725 (CLI `adspower-browser`) |
| `ADSPOWER_HOME` | каталог профилей AdsPower | В debug-стеке монтируется как `/adspower`; без него pre-flight считает все профили отсутствующими |
| `SERVER_PORT` | порт приложения | В `application.yml` — `8080`; в `just app::run` — `8071`; в debug-стеке — `8082` |
| `SPRING_PROFILES_ACTIVE` | профиль Spring | `worker` — планировщик включён; `once` — один прогон без веб-сервера; `admin` / `public` — планировщик выключен |
| `CRAWLER_SCHEDULE_CRON`, `CRAWLER_SCHEDULE_FRESHNESS_MINUTES` | расписание | Тик и каденция — разные вещи, см. `docs/decisions/2026-10-20-schedule-cadence-single-parameter.md` |
| `OTEL_ENDPOINT` | OTLP-экспорт | Необязателен |

Пароль в `.env` и в `app.env` на VPS — разные вещи: compose интерполирует `${…}` из `.env` и
ломает пароль с `$`, поэтому секреты на VPS идут через `env_file` (`app.env`) дословно.

---

## 3. Локальный запуск

**Собрать jar:** `just app::build bootJar` → `app/build/libs/app.jar`.

**Debug-стек (app + PostgreSQL + JDWP):** `just app::run-docker` — это сборка jar плюс
`docker compose -f deploy/docker-compose.debug.yml up --build`; вручную — те же две команды
по отдельности. `docker compose up --build` из корня делает то же самое: корневой
`docker-compose.yml` — это `include` debug-файла.

| Что | Адрес | Контейнер |
|---|---|---|
| Приложение | http://localhost:8082 | `wb-parser-debug` |
| PostgreSQL | localhost:5433 | `wb-parser-debug-postgres` |
| Отладчик (JDWP) | localhost:5005 | — |
| Логи и heapdump | `deploy/logs` (монтируется в `/logs`) | — |

- jar монтируется **файлом** (`../app/build/libs/app.jar:/app/app.jar:ro`); каталог вместо
  файла даёт «Invalid or corrupt jarfile»;
- после пересборки jar контейнер **пересоздают**, а не перезапускают:
  `docker compose -f deploy/docker-compose.debug.yml up -d --force-recreate app` — иначе JVM
  читает наполовину переписанный архив и отдаёт `NoClassDefFoundError` на классах, которые
  не успели загрузиться;
- `network_mode: host` обязателен: AdsPower Local API и CDP-порты доступны только через
  loopback хоста, в bridge-сети контейнера их нет;
- Flyway в debug-стеке включён (`SPRING_FLYWAY_ENABLED=true`): база пустая, без миграций
  схемы нет.

**Отдельный стек с AdsPower в контейнере:**
`ADSPOWER_API_KEY=<ключ> docker compose -f deploy/docker-compose.adspower.yml up --build` —
сервисы `wbparser-postgres`, `wbparser-adspower` (Xvfb + Fluxbox + x11vnc +
`adspower-browser`, Local API на `localhost:50325`), `wbparser-app` на `127.0.0.1:8082`.

**Приложение без контейнера:** `just app::run` — `bootRun` с дефолтами `SERVER_PORT=8071`,
`DB_HOST=localhost`, `DB_PORT=5433` (база из debug-стека), `ADMIN_API_KEY=dev-admin-key`,
`ADMIN_PASSWORD=dev-password` — это дев-значения, в проде их быть не должно.

**Проверить, что живо:** `curl -s http://localhost:8082/api/health/live` и
`curl -s http://localhost:8082/api/health/ready` (разбор — §6).

---

## 4. Проверка перед любым коммитом

```bash
./check.sh              # единственная точка входа (то же: just tests::check, just t)
SKIP_DB=1 ./check.sh    # без Docker: шаг с Testcontainers пропускается
```

`./check.sh` — 11 нумерованных шагов. Финальная строка `=== ALL CHECKS PASSED ===` означает,
что прошли **все** шаги; любой провал останавливает скрипт с ненулевым кодом.

| Шаг | Что делает | Падает, если |
|---|---|---|
| 1 | `scripts/ci/static-gates.sh` — общий реестр статических гейтов (тот же, что у CI-джоба `static`) | гейт из реестра не проходит |
| 2 | `:wbparser:test` — юнит-тесты домена и инфраструктуры | тесты красные |
| 3 | `:tests:test` — интеграционные тесты на Testcontainers (пропускается при `SKIP_DB=1`) | тесты красные |
| 4 | `:app:compileKotlin` + `:app:test` | не компилируется или тесты красные |
| 5 | `scripts/ci/check-compileonly-runtime.sh` — рантайм-класпат против `compileOnly` | зависимость есть в compile-time, но нет в рантайме (сборка `bootJar` падает) |
| 6 | `scripts/check-test-runs.py` — **число выполненных** тестов не меньше пола | тесты «прошли», но не выполнились |
| 7 | `./gradlew koverXmlReport` + `scripts/check-coverage.py` | покрытие ниже пола |
| 8 | `ktlintCheck` + `detekt` по всем модулям | нарушения стиля или detekt |
| 9 | `scripts/check-gate-wiring.py --tasks-exist --can-fail` — гейты достижимы и умеют падать | гейт не существует или не может упасть |
| 10 | `./gradlew buildHealth` — анализ зависимостей (DAGP) | новая находка вне принятого списка |
| 11 | `scripts/ci/check-runtime-classpath.py` — fat jar против резолвнутого класпатa | в образе лишний, дублирующийся или недостающий класс |

**Полы.** `config/gates/coverage-floor.txt` (покрытие строк, %): `wbparser` — 70, `app` — 25
(факт по комментарию в самом файле: 71.3 % и 26.5 %).
`config/gates/test-runs-floor.txt` (число выполненных тестов): `wbparser` — 359, `tests` —
107, `app` — 20, итого гейт требует минимум **486** выполненных тестов. Пол покрытия
поднимается тем же коммитом, что и покрытие: `./scripts/raise-kover-floor.py`.

**Сколько это занимает.** Точного числа в репозитории нет: длительность зависит от машины и
от состояния кэша Gradle. По составу: три тестовых запуска Gradle (шаги 2–4), Testcontainers
(шаг 3, нужен Docker), отчёт покрытия (7), линтеры (8), анализ зависимостей (10) и сборка
`bootJar` (5). На холодном кэше Gradle это десятки минут, на прогретом — единицы. Без Docker
используйте `SKIP_DB=1 ./check.sh`: шаг 3 будет честно пропущен, а не провален.

---

## 5. Деплой

Целевой хост — **91.188.212.39**; база данных живёт отдельно (91.188.212.209) и наружу не
публикуется. Контракт разделён: этот репозиторий владеет **образом** (`Dockerfile.app`) и
**рантайм-контрактом** (`deploy/docker-compose.vps.yml`), а `Caddy-vps` владеет **хостом** —
копирует compose-файл дословно, пишет `.env` и `app.env`, тянет образ, стартует, проверяет и
публикует маршрут. Playbook лежит **вне этого репозитория**:
`~/Caddy-vps/playbooks/wbparser.yml` (путь переопределяется `CADDY_VPS_DIR`; в
`.just/deploy/mod.just` — `PLAYBOOK`).

```bash
just deploy                # ./check.sh → ansible-playbook, тег = git rev-parse --short HEAD
just deploy <tag>          # задеплоить уже запушенный тег
just deploy::dry-run <tag> # ansible --check --diff
```

`just deploy` проверяет, что playbook на месте (иначе — ошибка с подсказкой про
`CADDY_VPS_DIR`), предупреждает о грязном рабочем дереве (тег не совпадёт с тем, что уедет),
запускает `./check.sh` — гейты обязаны быть зелёными — и выполняет
`ansible-playbook ~/Caddy-vps/playbooks/wbparser.yml -e "wbparser_tag=<tag>" -e
"wbparser_compose_src=<repo>/deploy/docker-compose.vps.yml"`.

Образ **должен уже быть в GHCR** — `just deploy` его не строит. Строит либо CI (джоб
`publish-image` в `.github/workflows/ci.yml`: после `build`, `lint`, `coverage`, `gate-honesty`,
только на push — собирает `Dockerfile.app` и пушит в `ghcr.io/<owner>/wb-parser` с тегами
`branch`, короткий `sha` и `latest` для default-ветки), либо человек вручную: `docker login
ghcr.io -u <user> --password-stdin`, затем `just deploy::build` (docker build) и
`just deploy::push` (docker push). Тег — короткий SHA коммита; фолбэка на `latest` в
compose-файле нет намеренно: забытый `TAG` иначе утащил бы «что registry считает самым новым».

**Что происходит на VPS.** `deploy/docker-compose.vps.yml`: сервис `wb-parser` (контейнер
`wb-parser`), `restart: unless-stopped`, образ
`ghcr.io/${IMAGE_OWNER:-gazon1}/wb-parser:${TAG:?TAG must be set...}`, `env_file: app.env`,
порт **не публикуется** — только `expose: 8080` во внешней сети `caddy_net`, которую создаёт
и владеет роль из `Caddy-vps`. Healthcheck: `wget -q -O /dev/null
http://127.0.0.1:8080/api/health/live || exit 1` — `wget`, а не `curl` (в
`eclipse-temurin:22-jre` нет curl), и `/live`, а не `/ready` (краткий блёп БД не должен
перезапускать краул посреди обхода).

**Как убедиться, что деплой прошёл.** Деплой не считается удачным, пока контейнер не отмечен
здоровым (`docs/decisions/2026-10-14-deploy-crawler-as-registry-image.md`):

```bash
docker ps --filter name=wb-parser          # STATUS: Up ... (healthy)
docker logs --tail 200 wb-parser
docker exec wb-parser wget -q -O - http://127.0.0.1:8080/api/health/live
```

Снаружи к 8080 не обратиться: приложение не опубликовано, маршрут публикует Caddy
(конфигурация маршрута — в `Caddy-vps`, здесь её нет).

**Первый деплой: Flyway baseline.** В базе на 91.188.212.209 лежит старая схема .NET, а
Flyway в контейнере настроен с `baseline-on-migrate: false` — на непустой схеме он откажется
что-то применять, пока не проставлен baseline. Процедура **один раз, до первого деплая**:
`ssh user@91.188.212.39 "bash -s" < scripts/vps-flyway-baseline.sh`. Скрипт (по умолчанию
`VPS_HOST=91.188.212.39`, `SSH_USER=user`, `SSH_KEY=$HOME/.ssh/ci_caddy_vps`) подключается по
SSH и выполняет внутри контейнера `wb-parser`: `java -cp app.jar
org.flywaydb.commandline.Main baseline -baselineVersion=1 -baselineDescription="NET schema
baseline"` и затем `... Main migrate`. Контейнер к этому моменту должен быть запущен
(`docker exec` требует живой процесс). После baseline обычный деплой применяет миграции сам.

---

## 6. Как читать health-эндпоинты

`app/src/main/kotlin/ru/wbparser/app/api/health/HealthRoutes.kt`. `/api/health/**` и
`/actuator/**` открыты без аутентификации (`WebSecurityConfig`); `/api/admin/**` — с
`X-Api-Key`.

`GET /api/health/live` → `{"status":"UP"}`. Живость процесса: ничего, кроме ответа
HTTP-сервера, не проверяет. Именно его использует healthcheck контейнера.

`GET /api/health/ready`:

```json
{
  "status": "UP",
  "checks": {
    "database": "UP",
    "subject_dictionary": "UP (8174 subjects)",
    "targets_without_search_token": "UP"
  }
}
```

| Проверка | Что значит | Плохое значение |
|---|---|---|
| `status` | `UP`, только если БД доступна. Проверки данных **не** роняют `status` — они предупреждают | `DOWN` — БД недоступна: нет соединения, отказ авторизации, ошибка драйвера |
| `database` | `SELECT 1` через `DataSource` | `DOWN: <класс исключения>: <сообщение>` — база недоступна или неверны `DB_*` |
| `subject_dictionary` | число строк в `wb_subjects` | `EMPTY — products are stored without category names` — справочник не загружен, имена категорий будут NULL. Лечится `just catalog::load-subjects` |
| `targets_without_search_token` | сколько целей в `crawl_targets` без `wb_search_query` | `<N> target(s) cannot be crawled — the internal API would answer with WB's popular list instead of the category` — такие цели краул обходить откажется; причина — токены не обогащены или протухли |
| любая из двух последних | `UNKNOWN: <сообщение>` — сама проверка упала (например, таблицы ещё нет) | проверьте, применены ли миграции |

**Секция `crawler`** (в текущей ветке; `app/src/main/kotlin/ru/wbparser/app/api/health/CrawlerStats.kt`)
— сводка счётчиков краула:

| Поле | Что значит | Плохое значение |
|---|---|---|
| `state` | `not_started` — краул ещё не работал (счётчиков нет); `started` — работал | `not_started` после ожидаемого прогона — краул не стартовал |
| `requests` | сумма `crawl.requests` по всем тегам | 0 при `state=started` — обход не идёт |
| `antibot_responses` | сумма `crawl.antibot.responses` | см. `refusal_share` |
| `refusal_share` | доля отказов `antibot_responses / requests` строкой вида `12.3%`; `n/a`, когда запросов ещё не было | устойчиво выше ~10 % — §8(b) |
| `presets_responses` | сумма `crawl.presets.responses` | больше нуля — протухший токен, §8(a). Ноль — токены ещё живы |
| `retries` | разбивка `crawl.retries` по `reason` | растёт `antibot` — §8(b) |
| `pages`, `items` | суммы `crawl.pages` и `crawl.items` | `pages` растёт, `items` нет — §8(c) |

**Прочие эндпоинты:** `GET /actuator/health` (состояние приложения, детали — `when_authorized`),
`GET /actuator/metrics` (список Micrometer-метрик, §7), `GET /api/admin/targets` (цели,
`X-Api-Key`), `GET /api/admin/jobs?limit=20&targetId=<uuid>` (последние прогоны: `status`,
`pagesCrawled`, `itemsSaved`, `errorMessage`), `GET /api/admin/errors?limit=100&unresolvedOnly=true`
(записанные ошибки краула).

> В `application.yml` включён `management.prometheus.metrics.export.enabled: true`, но
> зависимости `micrometer-registry-prometheus` в сборке нет — `/actuator/prometheus` может не
> отдавать данные. Ориентируйтесь на `/actuator/metrics`.

---

## 7. Метрики и что они значат

Домен эмитит метрики как данные через `Side.Metric`; интерпретатор
`MicrometerMetricInterpreter` добавляет префикс `crawl.` и регистрирует их в Micrometer.

| Метрика | Что означает | Теги | Плохое значение |
|---|---|---|---|
| `crawl.requests` | сколько запросов к WB сделано — **знаменатель** доли отказов | `target_id` (UUID цели в `crawl_targets`) | не растёт при работающем крауле — обход не идёт |
| `crawl.antibot.responses` | сколько запросов WB отказал (HTTP 498/403/401) — **числитель** | `target_id` | доля выше ~10 % — темп или IP не устраивают WB |
| `crawl.retries` | повторы по причине | `reason` ∈ `antibot` \| `rate_limited` \| `server_error` \| `stale_context` \| `database` | `reason="antibot"` растёт быстрее `crawl.pages` — запросы идут в блок |
| `crawl.presets.responses` | WB ответил «популярной ротацией» вместо каталога: токен цели протух | `target_id` | больше нуля — **всегда**: это чужие товары, которые не сохранили, но цель надо обогатить заново |
| `crawl.pages`, `crawl.items` | страниц прочитано и товаров сохранено (из раннера) | `target` (`wb_catalog_id` или имя цели) | `pages` = 0 при работающем планировщике; `items` = 0 при ненулевом `pages` — §8(c) |
| `crawl.targets.without_items` | целей, у которых страницы есть, а товаров нет | `target` | растёт — обход идёт, но каталог пуст или отфильтрован |
| `crawl.targets.truncated` (в текущей ветке) | цель остановилась на лимите, а каталог ещё не дочитан — сохранён **срез** каталога, а не каталог | `target`, `reason` (`pages` \| `depth`) | ненулевое значение — данные неполные, поднимите `known_pages_limit` |
| `crawl.items.without_category_name` | товаров, у которых `subject_id` есть, а имени нет | — | растёт — справочник предметов перестал совпадать с данными WB |
| `crawl.drops`, `crawl.errors`, `crawl.circuit_breaker` | задекларированы в KDoc `MicrometerMetricInterpreter`, но в коде **не эмитятся** — ориентироваться на них нельзя | `reason` / — | — |

**Доля отказов** = `crawl.antibot.responses / crawl.requests`. Без знаменателя числитель
ничего не значит — это зафиксировано в
`docs/decisions/2026-10-20-anti-bot-refusals-are-measured.md`. Ориентир: около 38 % отказов
наблюдалось при темпе 3 с и нескольких профилях с одного IP. **Профиль — не решение:** все
профили AdsPower деплоя выходят в сеть **с одного IP**, и ограничитель WB видит их суммарный
поток. Ротация профилей и пауза после 498 бьют по сессии, а не по лимиту IP: лимит этим не
выигрывается.

**Темп и back-off:**

| Параметр | Значение | Где |
|---|---|---|
| `browser-min-request-interval-ms` | 3000 мс — на группу, а не на деплой | `CrawlProperties` |
| `browser-jitter-min-ms` / `-max-ms` | 200 / 500 мс — jitter **pacer'а**, а не браузера | `docs/decisions/2026-10-20-the-request-rate-has-one-owner.md` |
| back-off антибота | 10 / 20 / 40 / 80 / 160 с (база 10 с против общей базы 1 с) | `RetryPolicy.antibotBaseDelayMs` |
| пауза профиля после 498 | 2 минуты | `BrowserPool.ProfileState.flagFor` |
| открытая цепь | запрос ждёт до 75 с, пока цепь не станет half-open | `EgressRouter` (цепь на группу) |
| закрытие простаивающего браузера | 5 минут без запроса → браузер останавливается в AdsPower | `crawler.spider.browser-idle-timeout-ms` |
| как часто ищутся простаивающие | раз в минуту | `crawler.spider.browser-janitor-interval-ms` |
| остановка браузеров на выходе | включено | `crawler.spider.close-browsers-on-exit` |

Темп задаёт **один `RequestPacer` на egress-группу**: если в конфиге «3 секунды между
запросами» — это 3,0–3,5 с, а не 3 с. Групп с разными IP — столько же независимых
паузеров, сколько адресов: ограничение WB считает источник по IP, и один паузер на
деплой означал бы один бюджет на все адреса сразу. Разбор — в
`docs/operations/egress-groups-runbook.md`, решение — в
`docs/decisions/2026-10-21-egress-groups-are-the-unit-of-rate-limiting.md`.

**Браузеры закрываются.** Профиль, которым не пользовались дольше
`browser-idle-timeout-ms`, останавливается в AdsPower — следующий заёмщик откроет его
заново (пул подключается лениво). На выходе приложения останавливаются все браузеры,
которыми краул пользовался: браузер, переживший один запуск, не должен переживать и
следующий. Если браузеры не закрываются, проверьте `close-browsers-on-exit` и доступность
Local API AdsPower — причина обычно в том, что AdsPower уехал раньше приложения.

---

## 8. Диагностика

### (a) `crawl.presets.responses` больше нуля

**Симптом.** WB отвечает «популярной ротацией» вместо каталога: ответ 200, `products` полны,
но это не та категория. Страница не сохраняется и не считается прочитанной, в лог идёт WARN
«WB answered with the popular list instead of the catalog». **Причина.** Токен цели протух:
`searchQuery` узла меню WB меняется, и цель со старым токеном получает живой ответ с чужой
ротацией (`docs/decisions/2026-10-20-a-popular-list-is-not-a-catalog.md`).

**Действие.**

1. Найти цель по тегу `target_id` из метрики: `psql -h <host> -p <port> -U <user> -d <db> -c
   "SELECT id, wb_catalog_id, name, wb_search_query FROM crawl_targets WHERE id = '<target_id>';"`
2. Переобогатить YAML: `uv run scripts/wb-catalog/enrich_catalog_queries.py
   wb_catalog_targets.yaml --dry-run`, затем без `--dry-run`. Скрипт печатает статистику:
   сколько целей обогащено, сколько без токена в меню, сколько вообще не найдено. Цели без
   токена в меню токена не получат — их закрывают вручную или исключают из каталога.
3. Перезагрузить цели в базу: `just catalog::load-confirm`.
4. Проверить, что цель снова обходится: `crawl.presets.responses` перестал расти, а
   `crawl.items` по этой цели ненулевой.

### (b) Высокая доля ответов 498

**Симптом.** `crawl.antibot.responses / crawl.requests` устойчиво выше ~10 %,
`crawl.retries{reason="antibot"}` растёт, `crawl.pages` почти не растёт. **Причина.** WB
отказывает сессии; причин четыре, и они складываются: (1) **темп** — ниже ~3 с между
запросами отказ гарантирован; (2) **back-off** — отказ повторён слишком рано, тот же профиль
и те же куки, база антибота 10 с, а не 1 с; (3) **cooldown профиля** — получивший 498 профиль
должен отдыхать 2 минуты, иначе `borrowPage` берёт его снова; (4) **egress IP** — все профили
деплоя выходят с одного IP, лимитер считает их сумму, и **это ограничение не обходится
профилями**.

**Действие.** Проверить фактический темп (`browser-min-request-interval-ms` не меньше 3000 мс,
jitter не выключен); проверить, что в `crawl.retries` есть `reason="antibot"` и что back-off
антибота не подменён общим (`RetryPolicy.antibotBaseDelayMs` = 10 000); убедиться, что
`crawler.spider.target-concurrency` не больше числа открытых профилей (пул сериализует по
профилю, лишние воркеры копятся в очередь); уменьшить `target-concurrency` и/или
`browser-min-request-interval-ms`. Долгосрочно — уйти с одного IP: единственное, что реально
снимает лимит.

### (c) Краул отчитывается о страницах, а товаров ноль

**Симптом.** `crawl.pages` растёт, `crawl.items` не растёт, растёт
`crawl.targets.without_items`.

| Причина | Признак |
|---|---|
| WB отдал пустой каталог | `crawl.presets.responses` не растёт, страницы пустые |
| Токен протух | растёт `crawl.presets.responses` → (a) |
| Товары отсечены правилами фильтра | в лог падает WARN `Item dropped [reason]` (счётчика `crawl.drops` в коде нет) |
| У товара нет цены | правило «товар без цены не сохраняется» — единственное активное по умолчанию (`crawler.spider.rules`) |
| Пустой справочник предметов | `subject_dictionary: EMPTY` в `/api/health/ready` → имена категорий NULL, но товары сохраняются |

**Действие.** Проверить `/api/health/ready`, затем логи на `Item dropped`; при пустом
справочнике — `just catalog::load-subjects`.

### (d) Приложение не стартует

| Симптом | Причина | Действие |
|---|---|---|
| `IllegalStateException: ADMIN_API_KEY environment variable is not set` | `AdminApiKeyFilter` требует ключ на старте | задайте `ADMIN_API_KEY` (в `.env` или в окружении рецепта) |
| `Flyway ... found non-empty schema(s) ... baseline` | на базе уже есть схема, а `baseline-on-migrate: false` | для БД с историей — `scripts/vps-flyway-baseline.sh` (один раз); для новой БД Flyway применит миграции сам |
| `Port 8080 was already in use` / `BindException` | порт занят | смените `SERVER_PORT`: 8082 в debug-стеке, 8071 в `just app::run`, 8080 в `application.yml` |
| `Cannot connect to the Docker daemon` | Docker не запущен или нет доступа к сокету | запустите Docker; `SKIP_DB=1 ./check.sh`, если Testcontainers не нужны |
| compose ругается на версию API | устаревший docker-compose | обновите docker/compose: compose-файл использует `name:`, `include:`, `depends_on: condition: service_healthy` |
| «Invalid or corrupt jarfile app.jar» | в контейнер смонтирован каталог вместо файла | §3: jar монтируется файлом, контейнер пересоздаётся |
| AdsPower Local API недоступен, «профиль отсутствует» | не запущен AdsPower или неверный `ADSPOWER_HOME` | pre-flight в логах (`AdsPowerPreflightRunner`) |
| `NoClassDefFoundError` на ровно что-то, но HTTP отвечает | устаревший контейнер после пересборки jar | `up -d --force-recreate app` |

### (e) В базе строки со старого эндпоинта

**Симптом.** 56 509 строк при 1178 уникальных `product_id`; один и тот же товар встречается
под десятками разных категорий; имена категорий не совпадают с товарами. **Причина.** Данные
собраны старым `v9/search` без фильтра по каталогу (§1). **Действие.** Пересобрать данные —
§9. До пересборки витрина показывает чужие товары, и это не дефект текущего кода.

### (f) `crawl.targets.truncated{reason="pages"}` растёт

**Симптом.** Цель остановилась на лимите страниц, а каталог ещё не дочитан: счётчик
`crawl.targets.truncated` с тегом `reason="pages"` растёт. **Причина.** `known_pages_limit`
цели меньше настоящей длины каталога. Лимит живёт в двух местах: в
`wb_catalog_targets.yaml` и в `crawl_targets.known_pages_limit` в базе — меняются оба.
**Действие.** Измерить длину и записать её; вручную число не выставляется
(`docs/decisions/2026-10-20-the-catalog-length-is-measured-at-the-pagination-boundary.md`).

```bash
# 1. измерить: отчёт JSON перезаписывается после каждого каталога
just catalog::measure-limits report=/tmp/catalog_lengths.json

# 2. посмотреть, что изменится
just catalog::apply-limits report=/tmp/catalog_lengths.json

# 3. записать (без --dry-run) и перезагрузить цели в базу
just catalog::apply-limits report=/tmp/catalog_lengths.json write=
just catalog::load-confirm wb_catalog_targets.yaml
```

Что важно знать о процедуре:

- **Замер требует живого AdsPower и живого WB.** При отказе антибота каталог попадает
  в отчёт со статусом `blocked`, и для него число **не** выставляется: шаг применения
  перечисляет такие цели и оставляет прежний лимит.
- **Предел пагинации WB — 60 страниц.** Лимит выше предела — это не «ещё одна
  страница», а падение цели: страница сверх предела приходит ошибкой, а не пустым
  списком.
- **`boundary: api_cap` в отчёте — честный признак**, что каталог длиннее, чем WB
  отдаёт: счётчик усечения будет срабатывать, и это правильно.
- **Пагинация WB меняется между прогонами** (один и тот же каталог измерениями давал
  59 и 60 доступных страниц), поэтому лимит перезамеряют, а не достраивают вручную.


---

## 9. Пересборка данных

Данные, собранные старым эндпоинтом, в проде смысла не имеют и должны быть заменены:
`scraped_items` append-only по (цель, товар, цена), поэтому новые товары просто встанут рядом
со старыми, и любой вопрос «что в этой категории» будет отвечаться старой половиной.

**Прод:**

```bash
bash scripts/rebuild_prod_data.sh --dry-run      # план, ничего не меняется
bash scripts/rebuild_prod_data.sh --host 91.188.212.39 --user user --yes
```

Процедура сама разбирается, что запущено на хосте: контейнер образа
(`wb-parser`), systemd-юнит (`wbparser`) или ничего. Хост, где краул не развёрнут,
— это состояние, а не ошибка: останавливать и ждать там нечего, а данные
пересобираются в любом случае. Пароль БД берётся из env-файла краула на хосте
(`/opt/wbparser/app.env`, `/home/user/wbparser/.env` или
`/home/user/wbparser/wbparser-new/.env` — первый, где он есть); если хост недоступен
или пароля там нет, он задаётся флагом `--db-password` или переменной
`WBPARSER_DB_PASSWORD`. Бэкап снимается `pg_dump`, а если его нет ни на машине, ни
на хосте — данные копируются клиентом PostgreSQL (дамп данных, не схемы: схемой
занимается Flyway).

Скрипт (по умолчанию `--host 91.188.212.39`, `--user user`, ключ
`$HOME/.ssh/ci_caddy_vps` — те же значения, что у `scripts/vps-flyway-baseline.sh`):
(1) останавливает контейнер краула; (2) снимает бэкап базы (`pg_dump`) в файл с меткой
времени; (3) печатает количество строк в каждой затронутой таблице; (4) удаляет старые
наблюдения и историю прогонов (**не** `crawl_targets` — это каталог, и **не** `wb_subjects` —
это словарь WB); (5) перезагружает каталог целей и справочник предметов существующими
загрузчиками; (6) запускает контейнер и ждёт, пока он отрапортует здоровым; (7) печатает
количество строк после изменения. Каждый разрушающий шаг спрашивает подтверждение; `--yes`
отвечает на все сразу, `--dry-run` ничего не меняет. Схему скрипт не меняет: на этом хосте
Flyway выключен намеренно, образ не передеплоивается (контейнер просто останавливается и
стартует на том же теге). Точный набор флагов и требуемое подтверждение —
`scripts/rebuild_prod_data.sh --help`.

**Локально или вручную** — тот же порядок:

```bash
just catalog::load-confirm     # цели (без токенов загрузчик пропускает со счётчиком skipped)
just catalog::load-subjects     # справочник предметов, иначе имена категорий останутся NULL
uv run scripts/wb-catalog/enrich_catalog_queries.py wb_catalog_targets.yaml
just catalog::load-confirm     # перезагрузка цели с новыми токенами
java -jar app/build/libs/app.jar --spring.profiles.active=once
```

Профиль `once` отключает веб-сервер и планировщик и выполняет один прогон. Коды возврата:
0 — успех, 1 — провал, 2 — не запустился (advisory-lock занят другим экземпляром),
3 — отменён.

**Проверка результата:** `/api/health/ready` (`targets_without_search_token: UP`,
`subject_dictionary: UP (…)`), `crawl.items` в метриках, выборка из
`latest_product_observations` (view: последнее наблюдение каждого товара). Полный обход 132
каталогов по 60 страниц при темпе 3 с занимает около 7 часов — это ожидаемо, а не зависание.

---

## 10. Известные ограничения

| Ограничение | Что это значит на практике |
|---|---|
| **≈38 % запросов WB отвечает 498** | Все профили AdsPower деплоя выходят с одного IP, и лимитер считает их суммарный поток. Ротация профилей, пауза после отказа и back-off бьют по конкретной сессии, но **лимит по IP этим не выигрывается**. Пока деплой живёт на одном IP, доля отказов в десятки процентов — норма, а не сбой конфигурации |
| **Читается срез каталога, а не каталог целиком** | Обход идёт `known_pages_limit` страниц на цель; в `wb_catalog_targets.yaml` у всех 132 целей стоит `known_pages_limit: 60` — это измеренная величина, а не круглое число (§8(f)). У 118 целей пагинацию обрезает предел API WB (60 страниц, страница сверх приходит ошибкой), поэтому счётчик `crawl.targets.truncated{reason="pages"}` для них срабатывает честно: каталог длиннее, чем WB отдаёт |
| **Токен каталога — данные WB, и они протухают** | `searchQuery` узла меню WB меняется, и цель со старым токеном получает живой ответ 200 с популярной ротацией. Единственный признак — `catalog_type = "presets"`, то есть метрика `crawl.presets.responses`. Токены приходится переобогащать по мере старения |
| **Покрытие модуля `tests` не видно kover** | Kover 0.9.11 не агрегирует покрытие зависимых модулей, поэтому отчёт модуля `tests` пуст; измеримый код переносится в измеримый модуль — `docs/decisions/2026-10-20-coverage-is-measured-where-the-code-lives.md` |
| **В проде нет ни наблюдений, ни развёрнутого краула** | База `wbparser` на 91.188.212.209 переведена на Flyway-схему v6 и наполнена каталогом (132 цели с измеренным `known_pages_limit`) и словарём предметов (8174 строк), но сам краул на 91.188.212.39 не развёрнут: нет ни контейнера `wb-parser`, ни systemd-юнита `wbparser`. Пока он не запущен, наблюдения не собираются, а витрина не читает эту базу — в её `.env` на VPS `DATABASE_URL` пуст (см. `deploy/provision-readonly-role.sql` в репозитории витрины) |
| **Два конфликтующих контракта деплоя** | Этот репозиторий везёт образ и запускает его через compose (`deploy/docker-compose.vps.yml`, `just deploy`), а роль `wbparser` в Caddy-vps клонирует репозиторий, собирает JAR на хосте и запускает его через systemd (`/home/user/wbparser`, java 21). Контракты не совпадают, и старый checkout на хосте (`/home/user/wbparser/wbparser-new`, коммит от 13 октября) клонировать заново нельзя: каталог не пуст |
| **Данные в проде до пересборки — чужие** | §1 и §8(e) |
| **Деплой разделён между двумя репозиториями** | Compose-файл и образ — здесь, хост, маршрут и переменные — в `Caddy-vps`. Playbook в этом репозитории не лежит: `just deploy` требует checkout `Caddy-vps` (путь — `CADDY_VPS_DIR`, по умолчанию `~/Caddy-vps`) |
| **На VPS нет исходящей сети** | Решение из `docs/decisions/2026-10-14-deploy-crawler-as-registry-image.md`: сборка идёт на машине с сетью, на VPS приезжает только образ из GHCR |

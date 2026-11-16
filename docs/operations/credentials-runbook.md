# Credentials — создание и проверка

Операционный документ: как создать креды, которые нужны краулеру, и как доказать,
что каждое из них работает. Проверка — не «приложение запустилось», а конкретный
запрос, который отвечает **401/403 без ключа** и **200 с ключом**.

Правила проекта и ADR — в `AGENTS.md` и `docs/decisions/DIGEST.md`; общий порядок
запуска — в `docs/RUNBOOK.md`. Этот документ про одну вещь: секреты.

**Проверять креды вручную не нужно** — есть CLI, и он проверяет все сразу:
`just parser::status` (или `uv run scripts/parser/wbparser.py status`). Он печатает
`✓ ADMIN_API_KEY: accepted` или `✗ …: refused or missing` и выходит с кодом 1, если
что-то не так. Ручные команды ниже остаются, чтобы было видно, **что** именно
проверяется.

---

## 1. Что обязательно, а что нет

| Переменная | Обязательна | Кто требует |
|---|---|---|
| `ADMIN_API_KEY` | **да** | `AdminApiKeyFilter` бросает `IllegalStateException` на старте |
| `CATALOG_API_KEY` | **да**, кроме профиля `once` | `CatalogApiKeyFilter.fromConfiguredValue` |
| `DB_*` (host/port/name/user/password) | да | `spring.datasource` |
| `CRAWLER_DATABASE_PASSWORD` | да для debug-стека | интерполируется compose'ом в `POSTGRES_PASSWORD` |
| `CATALOG_DB_USER` / `CATALOG_DB_PASSWORD` | да, если каталог вообще читают | отдельный пул, отдельная роль `wb_catalog_ro` |
| `PROFILES_DIR` + отпечатки профилей | да, если `use-browser-fetch: true` | `BrowserProfileConfig` отказывает профилю без `<id>.fingerprint.json` |
| `CAMOUFOX_HOME` / `CAMOUFOX_BUILD` | нет, дефолты совпадают | пин сверяется при первом запуске браузера |
| `EGRESS_PROXY_*` | нет, только для прокси-групп | `DeclaredProxy` |

`ADMIN_API_KEY` и `CATALOG_API_KEY` — **разные потребители**, и приложение
отказывается стартовать, если `CATALOG_API_KEY` содержит значение
`ADMIN_API_KEY`. Один ключ на оба API превратил бы компрометацию одного в
компрометацию другого.

---

## 2. Две ловушки, из-за которых файл «выглядит правильным», а старт падает

### 2.1 Пустое значение ≠ отсутствующее

`crawler.spider.egress-groups[*].proxy.port` имеет тип `Int`. Строка
`EGRESS_PROXY_PORT_HTTP=` (пустая) биндится пустой строкой, и Spring падает — ошибка
называет ту группу, чей порт оказался пустым:

```
APPLICATION FAILED TO START
Property: crawler.spider.egress-groups[1].proxy.port
Value: "${EGRESS_PROXY_PORT_HTTP:0}"
Reason: failed to convert java.lang.String to int
A null value cannot be assigned to a primitive type
```

Пустое значение — это значение. Поэтому в `.env` строки прокси **либо заполнены,
либо отсутствуют**; закомментированные строки в `.env.example` — именно про это.

### 2.2 `$` в пароле ломает интерполяцию compose

Compose подставляет `${…}` в файле `deploy/docker-compose.debug.yml` из `.env`.
Пароль, содержащий `$`, compose разрежет, и в контейнер уедет обрезанное значение
(на VPS ровно поэтому секреты идут через `env_file: app.env`, а не через
интерполяцию). Локально проще всего генерировать hex — в нём `$` не бывает.

### 2.3 Пароль базы живёт в двух местах

Debug-стек создаёт суперюзера `postgres` из `CRAWLER_DATABASE_PASSWORD`, а
приложение логинится через `DB_PASSWORD`. Если значения разошлись, контейнер
поднимается (volume уже инициализирован старым паролем), а приложение получает
отказ авторизации. На чистом volume оба значения обязаны совпадать — они
генерируются одной командой ниже.

---

## 3. Создание кредов

Все секреты — в `.env` в корне проекта (файл в `.gitignore`, `chmod 600`).
Значения ниже генерируются, а не придумываются: hex-строка нужной длины.

```bash
cd /home/max/wb-parser-kotlin-new

ADMIN=$(openssl rand -hex 32)         # 64 hex — ADMIN_API_KEY
CATK=$(openssl rand -hex 32)          # 64 hex — CATALOG_API_KEY, другой ключ
DBPW=$(openssl rand -hex 24)          # 48 hex — пароль базы (DB_PASSWORD == CRAWLER_DATABASE_PASSWORD)
CATPW=$(openssl rand -hex 24)         # 48 hex — пароль роли wb_catalog_ro
ADMPW=$(openssl rand -hex 16)         # 32 hex — Basic Auth, запасной путь

cat > .env <<EOF
DB_NAME=wbparser
DB_PORT=5433
DB_USER=postgres
DB_PASSWORD=$DBPW

POSTGRES_USER=postgres
POSTGRES_PASSWORD=$DBPW
CRAWLER_DATABASE_PASSWORD=$DBPW

ADMIN_API_KEY=$ADMIN
ADMIN_USER=admin
ADMIN_PASSWORD=$ADMPW

CATALOG_API_KEY=$CATK
CATALOG_DB_USER=wb_catalog_ro
CATALOG_DB_PASSWORD=$CATPW

CAMOUFOX_HOME=$HOME/.cache/camoufox
CAMOUFOX_BUILD=browsers/official/158.0.1-beta.41-3f2eb1d0
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
PROFILES_DIR=$HOME/.wbparser/profiles
EOF
chmod 600 .env
```

Проверка, что секреты действительно разные и нужной длины:

```bash
set -a; . ./.env; set +a
[ "$ADMIN_API_KEY" != "$CATALOG_API_KEY" ] && echo "keys differ: ok"
printf 'admin=%s catalog=%s\n' "${#ADMIN_API_KEY}" "${#CATALOG_API_KEY}"
```

### 3.1 Роль каталога `wb_catalog_ro`

Роль нужна, чтобы каталог не мог писать — это право PostgreSQL, а не настройка
приложения. Порядок: сначала миграции (схема должна существовать), потом роль —
скрипт проверит, что таблицы из read-set на месте, и упадёт, если схема не совпала.

```bash
# 1. миграции: приложение применит Flyway на старте (debug-стек: SPRING_FLYWAY_ENABLED=true)
# 2. роль — пароль передаётся переменной psql, а не аргументом
set -a; . ./.env; set +a
docker cp db/roles/provision-catalog-readonly-role.sql wb-parser-debug-postgres:/tmp/provision.sql
docker cp db/roles/catalog-readonly-role.sql          wb-parser-debug-postgres:/tmp/catalog-readonly-role.sql
docker cp db/roles/verify-catalog-readonly-role.sql   wb-parser-debug-postgres:/tmp/verify.sql

docker exec wb-parser-debug-postgres psql -U postgres -d wbparser \
  -v ON_ERROR_STOP=1 -v catalog_password="$CATALOG_DB_PASSWORD" -f /tmp/provision.sql
```

### 3.2 Отпечатки профилей браузера

Профиль без `<profile-id>.fingerprint.json` не запускается: браузер не придумывает
identity сам. Каталог называется по endpoint'у (`none` для direct-группы), поэтому
`[PROFILES_DIR]/none/[profile-id]`.

```bash
set -a; . ./.env; set +a
PY=~/.local/share/uv/tools/camoufox/bin/python
for p in k18a6j35 k17bnqsi; do
  mkdir -p "$PROFILES_DIR/none/$p"
  "$PY" - "$PROFILES_DIR/none/$p.fingerprint.json" <<'PYEOF'
import json, sys
from camoufox.fingerprints import generate_fingerprint, from_fpgen
cfg = from_fpgen(generate_fingerprint(os="lin"), ff_version="158.0")
open(sys.argv[1], "w").write(json.dumps(cfg))
PYEOF
done
```

Имена профилей — это `crawler.spider.egress-groups[*].profiles` в
`app/src/main/resources/application.yml`. Закоммиченный файл объявляет только группу
`direct` (профили `k18a6j35`, `k17bnqsi`); группа с HTTP-прокси закомментирована до
того момента, когда у развёртывания появится адрес прокси (см. §3.3).

### 3.3 Группа с прокси: почему она закомментирована

Прокси у развёртывания — HTTP с Basic auth (SOCKS5 нет, и Playwright не умеет
аутентифицироваться в SOCKS5 всё равно). Группа лежит в `application.yml`
закомментированной, и это не осторожность, а лечение конкретного симптома.

Объявленная группа без адреса **не безвредна**. Роутер считает её доступной — она
никогда не отказывала, она просто не запускалась, — и на каждый запрос предлагает ей
работу. Пул не находит ни одного пригодного профиля и платит попыткой заёма. В логе
это выглядит так, десятки раз в минуту:

```
[kxpu63n] Browser start failed: The profile 'kxpu63n' has no stored fingerprint
Profile kxpu63n unusable, trying next
Egress group socks5 has no free profile: No browser profile freed within 120000ms
```

Ни одна из этих строк не говорит оператору правду: «адрес прокси не задан». Хуже
того, кран всё равно не поедет — `PlaywrightCamoufoxLauncher` отвергает профиль без
`host`/`port`, так что даже созданный профиль с отпечатком не запустится.

Включение — это четыре шага, и все четыре нужны:

```bash
# 1. адрес и креды прокси в .env
EGRESS_PROXY_HOST_HTTP=<host>
EGRESS_PROXY_PORT_HTTP=<port>
EGRESS_PROXY_USERNAME_HTTP=<user>
EGRESS_PROXY_PASSWORD_HTTP=<password>

# 2. каталог профиля под endpoint'ом и его отпечаток (см. §3.2)
#    endpoint в имени каталога — это адрес: профиль нельзя открыть под другим

# 3. раскомментировать группу в application.yml и вписать profile-id

# 4. проверить, что группа действительно вышла в сеть
uv run scripts/parser/wbparser.py status
```

Каталог профиля называется по endpoint'у (`http-<host>-<port>`), поэтому профиль,
созданный для прямого адреса, в группу с прокси не переносится: cookies привязаны к
адресу.

### 3.4 Локальный прогон без прокси

Ничего переопределять не нужно: в закоммиченном файле объявлена одна группа `direct`,
и это ровно то, что работает на машине без прокси. Проверка — в логе обе строки:

```
The Camoufox of the profile 'k18a6j35' started.
Egress group direct: address=<ip>, isolated=true
```

Если группу с прокси всё же включили, а адреса нет, `SPRING_APPLICATION_JSON`
заменяет список групп целиком:

```bash
export SPRING_APPLICATION_JSON='{"crawler":{"spider":{"egress-groups":[
  {"id":"direct","profiles":["k18a6j35","k17bnqsi"],"proxy":{"type":"none"}}]}}}'
```

---

## 4. Проверка кредов

Каждая проверка — это либо **отказ без ключа**, либо **ответ с ключом**. «Приложение
запустилось» ничего не доказывает: запуститься оно может и с ключом, который потом
не подходит ни к одному API.

### 4.1 Ключи API

Запустить приложение (любой профиль, кроме `once`) и проверить:

```bash
set -a; . ./.env; set +a
BASE=http://localhost:8071      # 8082 для debug-стека

# admin: без ключа и с чужим ключом — отказ
curl -s -o /dev/null -w 'admin  no-key   %{http_code}\n' "$BASE/api/admin/targets"
curl -s -o /dev/null -w 'admin  wrong    %{http_code}\n' -H 'X-Api-Key: nope' "$BASE/api/admin/targets"
# admin: с настоящим ключом — 200
curl -s -o /dev/null -w 'admin  real     %{http_code}\n' -H "X-Api-Key: $ADMIN_API_KEY" "$BASE/api/admin/targets"

# catalog: без ключа — 401, с ключом администратора — тоже 401
curl -s -o /dev/null -w 'catalog no-key   %{http_code}\n' "$BASE/api/catalog/v1/health"
curl -s -o /dev/null -w 'catalog admin    %{http_code}\n' -H "X-Api-Key: $ADMIN_API_KEY" "$BASE/api/catalog/v1/health"
# catalog: со своим ключом — 200
curl -s -o /dev/null -w 'catalog real     %{http_code}\n' -H "X-Api-Key: $CATALOG_API_KEY" "$BASE/api/catalog/v1/health"
```

Ожидаемо: `403`, `403`, `200`, `401`, `401`, `200`. Первые два — `403`, а не `401`,
потому что admin-цепь отвечает на отсутствие аутентификации отказом в доступе;
catalog-фильтр отвечает `401` сам.

### 4.2 Ключ не пускает в чужой API

Это отдельная проверка, а не следствие предыдущей: административный ключ,
открывающий каталог, означал бы, что цепь каталога читает не свою переменную.
Строка `catalog admin → 401` в §4.1 — это и есть проверка.

### 4.3 Защиты на старте (доказательство, что они есть)

```bash
# ключи совпали — приложение обязано отказаться стартовать
CATALOG_API_KEY="$ADMIN_API_KEY" java -jar app/build/libs/app.jar --spring.profiles.active=admin
# → CATALOG_API_KEY must not contain ADMIN_API_KEY

env -u CATALOG_API_KEY java -jar app/build/libs/app.jar --spring.profiles.active=admin
# → CATALOG_API_KEY is not set. The catalog API must not be reachable without a key.

env -u ADMIN_API_KEY   java -jar app/build/libs/app.jar --spring.profiles.active=admin
# → ADMIN_API_KEY environment variable is not set.
```

### 4.4 Роль каталога: права и их отсутствие

Два вопроса, и оба обязательны: «что роль может» и «что она не может».

```bash
# (а) эффективные права — скрипт, который только смотрит
docker exec wb-parser-debug-postgres psql -U postgres -d wbparser \
  -v ON_ERROR_STOP=1 -f /tmp/verify.sql
# → wb_catalog_ro holds CONNECT, USAGE and SELECT, and nothing else.

# (б) рантайм-проверка: отказ должен прийти от сервера (42501), а не от приложения
set -a; . ./.env; set +a
for op in \
  "INSERT INTO public.scraped_items (id, target_id, scraped_at) VALUES (gen_random_uuid(), gen_random_uuid(), now())" \
  "UPDATE public.scraped_items SET scraped_at = now()" \
  "DELETE FROM public.scraped_items" \
  "CREATE TABLE public.nope (x int)"; do
  docker exec -e PGPASSWORD="$CATALOG_DB_PASSWORD" wb-parser-debug-postgres \
    psql -U wb_catalog_ro -h 127.0.0.1 -d wbparser -v ON_ERROR_STOP=1 -c "$op" 2>&1 | grep -oE 'ERROR:.*' || true
done
# → permission denied for table scraped_items (×3), permission denied for schema public

# (в) чтение, ради которого роль существует
docker exec -e PGPASSWORD="$CATALOG_DB_PASSWORD" wb-parser-debug-postgres \
  psql -U wb_catalog_ro -h 127.0.0.1 -d wbparser -tAc "select count(*) from public.scraped_items"
```

`(в)` возвращает число — роль читает. Если вместо числа пришёл отказ, роль
провиженилась против другой схемы: скрипт уже сравнит read-set с реальными
таблицами и упадёт с их списком.

### 4.5 Пароль базы и пароль для загрузчиков

Приложение и загрузчики каталога ходят в базу под одним `DB_USER`/`DB_PASSWORD`.
Проверка — один SELECT под этими кредами и dry-run загрузчика:

```bash
set -a; . ./.env; set +a
docker exec -e PGPASSWORD="$DB_PASSWORD" wb-parser-debug-postgres \
  psql -U "$DB_USER" -h 127.0.0.1 -d "$DB_NAME" -tAc "select 'db-login-ok'"

uv run scripts/wb-catalog/load_catalog_to_pg.py wb_catalog_targets.yaml \
  --db-host "$DB_HOST" --db-port "$DB_PORT" --db-name "$DB_NAME" \
  --db-user "$DB_USER" --db-password "$DB_PASSWORD" --dry-run
# → Loaded 132 categories from wb_catalog_targets.yaml
# → DRY-RUN: 132 records prepared.
```

Пароль базы меняется на живом volume одной командой, и после неё `.env` обязан
содержать то же значение:

```bash
docker exec wb-parser-debug-postgres psql -U postgres -d postgres \
  -c "ALTER ROLE $DB_USER PASSWORD '$DB_PASSWORD';"
```

### 4.6 Профиль браузера

Кредо здесь — сам каталог профиля и его отпечаток. Проверка:

```bash
set -a; . ./.env; set +a
ls -d "$PROFILES_DIR"/none/*/ ; ls -l "$PROFILES_DIR"/none/*.fingerprint.json
just browser::verify      # сборка совпадает с пином
```

Полная проверка — не файлы, а запуск: профиль обязан открыться **и** заявить
user-agent из отпечатка. Делает это приложение профилем `worker` (preflight probe
поднимает по браузеру на группу):

```bash
SPRING_PROFILES_ACTIVE=worker DB_HOST=localhost DB_PORT=5433 SERVER_PORT=8075 \
  java -jar app/build/libs/app.jar
# → The Camoufox of the profile 'k18a6j35' started.
# → Egress group direct: address=<ip>, isolated=true
```

Отпечаток, который браузер не принял бы, виден как другая ошибка: «The profile '<id>'
has no stored fingerprint» (файла нет) или отказ `CamoufoxEnvironment` (файл есть, но
в нём нет `navigator.userAgent`, он пуст, не JSON или не объект).

### 4.7 Полный прогон: доказательство, что все креды вместе работают

Отдельные проверки выше доказывают каждое кредо по отдельности. Последняя проверка —
одна, и она про их сочетание: краул обязан запуститься, поднять браузер и **записать
строки**. Профиль `once` для этого и существует (веб-сервер и ключи API ему не нужны):

```bash
set -a; . ./.env; set +a
export SPRING_APPLICATION_JSON='{"crawler":{"spider":{"egress-groups":[
  {"id":"direct","profiles":["k18a6j35","k17bnqsi"],"proxy":{"type":"none"}}]}}}'
java -jar app/build/libs/app.jar --spring.profiles.active=once

# что должно быть в логе
#   The Camoufox of the profile 'k18a6j35' started.
#   One-shot crawl finished with exit code 0

# и главное — строки в базе
docker exec wb-parser-debug-postgres psql -U postgres -d wbparser \
  -tAc "select count(*) from scraped_items;"
```

Ноль строк при `exit code 0` — это `NothingDue`/пустой каталог, а не успех; смотрите
`crawl.presets.responses` и §8 в `docs/RUNBOOK.md`. Отдельно: доля ответов 498 в
десятки процентов — ожидаемое поведение одного IP, а не признак плохих кредов.

---

## 5. Если что-то не так

| Симптом | Причина | Действие |
|---|---|---|
| `A null value cannot be assigned to a primitive type` на `egress-groups[].proxy.port` | пустое `EGRESS_PROXY_PORT_HTTP=` | удалить строку из `.env`, а не оставлять пустой (§2.1) |
| `CATALOG_API_KEY is not set` | ключа нет в окружении | §3, затем перезапуск |
| `CATALOG_API_KEY must not contain ADMIN_API_KEY` | один ключ на оба API | сгенерировать второй ключ |
| `ADMIN_API_KEY environment variable is not set` | то же для admin | §3 |
| `password authentication failed for user "<user>"` | `DB_PASSWORD` ≠ пароль в volume | `ALTER ROLE … PASSWORD` (§4.5) и синхронизировать `.env` |
| `the catalog reads tables that do not exist` | роль провиженится до миграций | применить Flyway (запустить приложение), затем повторить §3.1 |
| `Failed to bind … ${…}` после правки `.env` | в значении есть `$` | перегенерировать hex-паролем (§2.2) |
| `The profile '<id>' has no stored fingerprint` | нет `<id>.fingerprint.json` | §3.2 |
| `Egress group 'socks5': the address could not be determined` | у группы нет профиля с адресом | WARN; для локального прогона оставить одну direct-группу |
| `relation "flyway_schema_history" ... checksum` | volume от другой ревизии миграций | debug-стек одноразовый: `docker compose rm -sf postgres && docker volume rm wbparser_debug_data` |

Профиль `once` не требует **ни одного** из двух ключей API: он выключает веб-сервер
и `WebSecurityConfig` (`@Profile("!once")`), поэтому ключи для него — не проверка,
а отсутствие проверки. Если нужен прогон без ключей — это `once`, и только он.

---

## 6. Что не хранить в репозитории

- `.env` — в `.gitignore`, права `600`. В репозитории живёт только `.env.example`.
- Креды прокси — только через окружение (`EGRESS_PROXY_*`); `ProxyConfig.toString()`
  и `DeclaredProxy.toString()` вырезают username/password, поэтому адрес в логе —
  адрес, а не пара кредов.
- Каталоги профилей — cookies и storage, то есть сессия. Они вне репозитория
  (`PROFILES_DIR`) и копируются только вместе с отпечатком.
- `scripts/wb_context.json` — снятая живая сессия WB, это кредо, а не фикстура.

---

## 7. CLI: наблюдение за краулером

Отдельного веб-интерфейса в этом репозитории нет — витрина живёт в другом месте и
читает каталог через API. Всё, что нужно оператору, закрывает один скрипт:
`scripts/parser/wbparser.py`. Он ходит **только в HTTP API** (SQL принадлежит API —
это правило из `docs/decisions/DIGEST.md`), поэтому у него нет ни своей схемы, ни
своего доступа к базе. Лог — единственное исключение: строки лога никогда не жили в
таблице, и их отдаёт `docker logs`.

Зависимости объявлены в `pyproject.toml`, поэтому установка — один шаг: `uv sync`.
Дальше — `uv run scripts/parser/wbparser.py …` или `just parser::…`, который делает то
же самое.

```bash
just parser::status      # или: uv run scripts/parser/wbparser.py status
```

| Команда | Отвечает на вопрос |
|---|---|
| `up` | поднять стек и дождаться готовности (`--build` — пересобрать jar) |
| `status` | жив ли, готов ли, приняты ли ключи, как идёт краул, что с egress |
| `items` | **что спарсено** — с пагинацией, поиском, сортировкой и фильтрами |
| `stats` | сколько товаров и наблюдений, насколько свежи данные |
| `runs` | **журнал прогонов**: цель, группа, адрес, исход, страницы, товары |
| `schedule` | тик, окно свежести, **какие цели сейчас due** |
| `crawl` | один прогон в форграунде (профиль `once`, ключи не нужны) |
| `logs` | лог приложения: `--follow`, `--grep ERROR` |
| `down` | остановить стек (том базы сохраняется; `--volumes` — удалить) |

### Что смотреть

**Спарсенное.** `items` — те же данные, что получит витрина:

```bash
just parser::items --sort PriceDesc --page-size 10
just parser::items -q "компьютер" --min-cashback 5
just parser::items --target <uuid> --in-stock
```

Вывод — таблица с ценой в рублях, кэшбэком и возрастом наблюдения; внизу — подсказка
для следующей страницы и **момент данных** (`dataAsOf`), а не момент запроса.
`--json` отдаёт ровно тело ответа API, без обёрток, чтобы работало `| jq`.

**Расписание.** `schedule` печатает тик (`CRAWLER_SCHEDULE_CRON`), окно свежести
(`CRAWLER_SCHEDULE_FRESHNESS_MINUTES`) и цели, которые сейчас due, с временем
последнего обхода. Тик — это «как часто планировщик смотрит», а не «как часто
краулим»: тик, не нашедший работы, не начинает ничего.

Одно расхождение известно и названо вслух: цель, которую держит lease прерванного
прогона, `schedule` покажет как due, а краул её пропустит — admin-API не отдаёт
`lease_until`. Lease истекает сам (2 часа по умолчанию).

**Журнал прогонов.** `runs` показывает три вещи сразу: прогоны целей (кто обслужил,
с какого адреса, чем кончилось), задания (`crawl_jobs`) и нерешённые ошибки. Доля
`antibot` в исходах — это про адрес, а не про цель.

**Логи.** `logs -f` для наблюдения, `logs --grep ERROR -n 500` — чтобы не читать
тысячу строк DEBUG (в debug-стеке `LOGGING_LEVEL_ROOT=DEBUG`).

### Коды возврата

`0` — команда сделала то, что обещает. `1` — сделала и что-то не так (проверка не
прошла, ничего не спарсено, API отказал). `2` — не смогла запуститься (нет стека,
нет кредов, нет ответа). Это делает команды пригодными для `if`/`&&`:

```bash
just parser::status || just parser::up
```

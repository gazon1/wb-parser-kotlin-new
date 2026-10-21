# CONTEXT — глоссарий краулера

Словарь терминов, которыми этот репозиторий говорит о выходе в сеть, о планировании и о состоянии целей. Файл существует, чтобы новичку не пришлось читать код, чтобы
узнать, что «egress-группа» — это не «профиль», а что back-off у цели и back-off у адреса — это два разных числа. «Где правда» указывает файлы, которые **владеют**
термином: если код и этот файл разошлись, прав код.

## Как это связано (с чего читать)

1. `docs/decisions/2026-10-21-egress-groups-are-the-unit-of-rate-limiting.md` — почему пауза и circuit breaker принадлежат группе, а не профилю.
2. `docs/decisions/2026-10-22-egress-groups-are-declared-and-checked.md` — почему объявление групп сверяется с фактом, и почему «проверка прошла» и «проверка не выполнилась» — разные вещи.
3. `docs/operations/egress-groups-runbook.md` — операторская версия: как настроить и что делать, когда что-то не так.
4. Этот файл — словарь; `docs/decisions/DIGEST.md` — выжимка всех решений.

## Egress-группа (EgressGroup)

Набор профилей AdsPower, которые, как ожидается, выходят с одного egress IP. WB считает источник запроса по IP, поэтому группа, а не профиль, владеет бюджетом запросов,
своим circuit breaker и своей долей антибот-бюджета. Слово «ожидается» намеренно: прокси живёт в AdsPower, а краулер объявляет только кто с кем делит адрес. Профилей в
группе лучше один — ради пропускной способности; два-три покупают живучесть, но не скорость.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressGroup.kt` — `EgressGroup`, `EgressGroup.DEFAULT_ID`;
`app/src/main/kotlin/ru/wbparser/app/config/EgressGroups.kt` — `EgressGroups.resolve`, `EgressGroupConfig`;
`app/src/main/kotlin/ru/wbparser/app/config/CrawlProperties.kt` — `Spider.egressGroups` (ключ `crawler.spider.egress-groups` — единственный список профилей).

## Профиль браузера (BrowserProfileConfig)

Один профиль AdsPower: идентификатор профиля, необязательный CDP WebSocket-URL и имя egress-группы, которой он принадлежит. Профиль задаёт fingerprint и сессию; группа
задаёт, кого WB считает одним источником. Профиль вне группы — ошибка конфигурации: он молча разделил бы бюджет с той группой, к которой не принадлежит.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/BrowserProfileConfig.kt` — `BrowserProfileConfig`, поле `egressGroup`;
`app/src/main/kotlin/ru/wbparser/app/config/EgressGroups.kt` — `EgressGroups.resolve` (запрет профиля в двух группах).

## Пул браузеров (BrowserPool)

Пул Playwright-подключений к уже открытым профилям AdsPower. Каждому профилю соответствует свой паузер, то есть свой бюджет запросов на группу. Пул отдаёт профиль в заём
и умеет сказать, когда у конкретной группы освободится слот (`millisUntilSlot`) и сколько этот слот будет ждать (`awaitRequestSlot`).

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/BrowserPool.kt` — `BrowserPool`, `egressGroupIds`, `awaitRequestSlot`, `millisUntilSlot`, `borrowPage`,
`tryBorrowPage`; `wbparser/src/main/kotlin/ru/wbparser/infra/http/BrowserProfileState.kt` — состояние профиля (warm, отдых после антибота).

## Заимствованная страница (BorrowedPage)

Страница Playwright вместе с id профиля, из которого она взята. Несёт `egressGroup` — адрес, под которым WB её увидит: два профиля одной группы делят IP и потому делят
бюджет запросов, два профиля разных групп — нет. `markWarm()` и `markAntibot(cooldownMs)` сообщают пулу о состоянии сессии, освобождение — `close()`.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/BorrowedPage.kt` — `BorrowedPage`, `BorrowedPageHooks`, `BrowserPoolExhaustedException`.

## Паузер запросов (RequestPacer, RequestPacing)

Бюджет запросов группы: минимальный интервал между двумя запросами плюс джиттер. Правило одно на всю группу, а не на профиль: per-profile пауза даёт всплеск `профили ×
1/интервал` — ровно тот рисунок, на который смотрит лимитер. Джиттер — часть паузы, а не украшение запроса: постоянная пауза оставляет запросы на метрономе. Три числа
(`minIntervalMs`, `jitterMinMs`, `jitterMaxMs`) — один объект `RequestPacing`; ноль отключает паузер.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/RequestPacer.kt` — `RequestPacer`, `RequestPacing`, `RequestPacer.jitterMs`;
`wbparser/src/main/kotlin/ru/wbparser/infra/http/BrowserPool.kt` — по одному паузеру на группу;
`docs/decisions/2026-10-21-egress-groups-are-the-unit-of-rate-limiting.md`.

## Маршрутизатор выхода (EgressRouter)

Решает, какая egress-группа обслужит следующий запрос, и ведёт книги каждой группы. Правило выбора — «у кого раньше освободится слот паузера», а не «у кого больше
профилей»: бюджет группы задаёт пауза. Порядок обязателен — сначала резервируется профиль, потом выдерживается пауза, иначе слот тратится на группу, у которой все профили
заняты. Circuit breaker — один на группу (`egress-<groupId>`), с прежними глобальными порогами: 50 % отказов, минимум 5 вызовов, 30 с в открытом состоянии. Открытый
circuit группы не спрашивается, пока другие отвечают.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressRouter.kt` — `EgressRouter`, `EgressRouter.borrow`, `EgressRouter.onOutcome`,
`EgressRouter.defaultCircuitConfig`; `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressRouterConfig.kt` — `EgressRouterConfig`.

## Состояние группы (EgressGroupState)

Снимок того, как группа выглядит сейчас: сколько миллисекунд до следующего слота, что группа надела с момента последней смены адреса (запросы, отказы, страницы), доля отказов
**в окне** и в каком состоянии circuit (`Closed` / `HalfOpen` / `Open`). Из этих чисел выводятся два вердикта: `available` (об этом адресе сейчас не спрашивают — circuit открыт, адрес
остывает) и `healthy` (адрес отвечает нормально — надёжный свидетель в споре о сломанном каталоге). Это два разных вопроса, и совпадать они не обязаны.

Состояние группы — **один ответ на один вопрос**. Считает его единственное место — `EgressRouter.groupState` — и читают все, кому он нужен: роутер (выбор группы), health-эндпоинт (что
видит оператор) и раннер (чей адрес виноват). Отдельного ответа у health-эндпоинта больше нет: картинка из метрик расходилась с тем, во что верит раннер.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressGroupState.kt` — `EgressGroupState`, `EgressGroupCounters`;
`wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressCircuitState.kt` — `EgressCircuitState`;
`wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressGroupStates.kt` — `EgressGroupStates`;
`wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressRouter.kt` — `EgressRouter.groupState`, `EgressRouter.groupStates`.

## Окно здоровья и атрибуция (EgressAttribution)

Вопросы, которые роутер умеет отвечать. Первый: какая группа обслужила эту цель последней (`lastGroupFor`) — ответ на «чей адрес виноват». Второй: каково состояние группы
(`groupState`) и какой из него вердикт `healthy` — группа, отказывающая в большинстве запросов, не является надёжным свидетелем в споре о сломанном каталоге. Здоровье
измеряется долей отказов **за окно** (по умолчанию 10 минут), а не за всё время: группа, отказывавшая час назад и отвечающая сейчас, здорова; группа без запросов в окне — тоже.
Раннер спрашивает то же состояние, которое показывает health-эндпоинт, поэтому оператор и краулер не могут обвинить в одном и том же разные адреса.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressAttribution.kt` — `EgressAttribution`;
`wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressRouter.kt` — `EgressRouter.DEFAULT_HEALTH_WINDOW_MS`,
`EgressRouter.DEFAULT_HEALTH_REFUSAL_THRESHOLD`; `wbparser/src/main/kotlin/ru/wbparser/infra/runner/CrawlRunner.kt` — `CrawlRunner.recordOutcome` (решение «адрес или каталог»).

## Исчерпание групп выхода (AllEgressGroupsUnavailable)

Исключение, которое роутер бросает, когда ни одна группа не может обслужить запрос в течение `maxOpenWaitMs` (по умолчанию 75 с): у всех circuit открыт либо все профили
заняты. Это **не** отказ WB — отказывать было некому. Это собственная ёмкость краулера, и цель за это не наказывается.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressRouter.kt` — `AllEgressGroupsUnavailable`, `EgressRouter.borrow`,
`EgressRouter.DEFAULT_MAX_OPEN_WAIT_MS`.

## Исход запроса (DownloadOutcome)

Как закончился один запрос: `Success`, `Antibot` (WB ответил антибот-статусом — 498, 403, 401) или `Error` (JS-ошибка, таймаут, пустой ответ). Различие, которое имеет
значение, — `Antibot` против всего остального: отказ принадлежит адресу, а не каталогу.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/DownloadOutcome.kt` — `DownloadOutcome`;
`wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressRouter.kt` — `EgressRouter.onOutcome`, `EgressRouter.recordCircuit`.

## Проба выхода (EgressProbe, EgressProbeConfig)

Проверка того, что группы — это то, что объявлено. Сверяет объявление с фактом, потому что прокси живёт в AdsPower, а поле `ip` в ответе Local API — записанное значение,
а не живой замер. Проба читает прокси всех профилей и проверяет два свойства, а затем открывает по одному профилю на группу и спрашивает у эхо-сервиса, какой адрес видит
мир. Проба **сама по себе не блокирует старт**: недоступный сторонний сервис — предупреждение, а не отказ; отказом старта это становится только при включённом
`crawler.spider.require-isolated-egress-groups`. Проба идёт только там, где работает краулер — она открывает браузер.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressProbe.kt` — `EgressProbe`, `EgressProbeConfig`, `EgressReport`, `EgressGroupProbe`,
`EgressProbe.DIRECT_IDENTITY`; `app/src/main/kotlin/ru/wbparser/app/scheduling/AdsPowerPreflightRunner.kt` — `AdsPowerPreflightRunner.probeEgressGroups`;
`docs/decisions/2026-10-22-egress-groups-are-declared-and-checked.md`.

## Объявленный прокси (DeclaredProxy)

Что конфигурация говорит о том, как группа выходит в сеть. Прокси живёт в AdsPower, но **объявлен** он в конфигурации краулера: `proxy.type` — вид прокси (`none` для прямого
выхода, `socks5`, `http`, `https`), а `host` и `port` — необязательное уточнение адреса. Пароля здесь нет и быть не должно: краулер никогда не ходит в прокси сам, это делает
браузер, и учётные данные браузера живут в AdsPower.

Объявление **обязательно**. Группа, которая ничего не объявила, — это не «прямая группа», это группа с неизвестным адресом, и такая конфигурация отвергается при старте: «не
сказал» и «прямой выход» — разные вещи, и второе не должно получаться молча. Объявленный вид сверяется с тем, что AdsPower показывает для профилей группы; расхождение
останавливает краулер **безусловно** — это не вопрос изоляции, а вопрос «та ли это группа».

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/DeclaredProxy.kt` — `DeclaredProxy`, `DeclaredProxy.matches`;
`app/src/main/kotlin/ru/wbparser/app/config/EgressGroups.kt` — `ProxyConfig`, `EgressGroups.validateProxy`;
`wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressProbe.kt` — `EgressProbe.checkStatic`.

## Статическая проверка прокси

Дешёвый слой: читает у AdsPower прокси каждого профиля (один HTTP-вызов, без браузера) и проверяет, что **внутри** группы все профили за одним прокси и что **между**
группами прокси разные. Именно она ловит ошибку конфигурации: две группы за одним прокси — это один адрес и один бюджет, а не два. Профиль без прокси — это адрес машины,
на которой работает краулер; одна такая «прямая» группа допустима, две — нет. Прокси, детали которого AdsPower не отдаёт (`proxy_soft: other` с пустым host), — это
прокси, а не «прямое подключение»: путать их нельзя. Третье, что она проверяет: **объявленный** прокси группы совпадает с тем, что AdsPower показывает — иначе группа
получает бюджет и метрику чужого адреса, и это видно только по цифрам.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressProbe.kt` — `EgressProbe.checkStatic`, `EgressProbe.readConfiguredProxies`,
`EgressProbe.groupProxyIdentity`; `wbparser/src/main/kotlin/ru/wbparser/infra/http/AdsPowerApi.kt` — `AdsPowerProfileProxy.identity`, `AdsPowerProfileProxy.isDirect`,
`AdsPowerProfileProxy.hasOpaqueProxy`.

## Живая проверка адреса

Последний слой: по одному профилю на группу открывается браузер, и эхо-сервис (`crawler.spider.egress-probe-urls`, по порядку — отвечает первый) сообщает адрес, под
которым группа видна миру. Ловит то, чего не видно статикой: два разных прокси, сошедшиеся на одном адресе, и профили вообще без прокси. Группы, увиденные по одному
адресу, помечаются как не изолированные (`isolated = false`) — это ошибка изоляции, а не конфигурации: краулер работает, просто не получает обещанного дополнительного
бюджета.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressProbe.kt` — `EgressProbe.probeAddresses`, `EgressProbe.probeGroup`, `EgressProbe.readAddress`,
`EgressProbe.checkIsolation`, `EgressProbe.extractIp`; `app/src/main/kotlin/ru/wbparser/app/config/CrawlProperties.kt` — `Spider.egressProbeUrls`,
`Spider.egressProbeTimeoutMs`.

## «Проверка не смогла выполниться» и «проверка провалилась»

Это два разных исхода, и путать их нельзя. Проверка **провалилась** — факт известен и он плохой: у группы разные прокси, две группы за одним прокси, две группы видны по
одному адресу. Проверка **не смогла выполниться** — факта нет: AdsPower не описал ни одного профиля, часть профилей группы не описана, эхо-сервис не ответил, не хватило
бюджета времени. Второе — это предупреждение, а не успех: `EgressReport.misconfigured` тогда `false`, а нарушение попадает в `EgressReport.problems` словами, с которыми
оператор может что-то сделать. Пустой ответ проверки нельзя выдавать за «всё в порядке».

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressProbe.kt` — `EgressReport.misconfigured`, `EgressReport.problems`, `EgressProbe.probe`;
`docs/decisions/2026-10-22-egress-groups-are-declared-and-checked.md` — правило «MUST различать „проверка прошла“ и „проверка не смогла выполниться“».

## Смена адреса группы

Адрес группы может смениться: прокси с ротацией или переключение на другой адрес приводит к тому, что группа видна по новому IP. Это **новая личность**: cookies профилей
привязаны к старому адресу, а у нового адреса свой бюджет. Probe сравнивает текущий адрес с предыдущим и заводит `ipChanged`; счётчик изменений ведёт сама таблица
`crawl_egress_groups`.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressProbe.kt` — `EgressGroupProbe.ipChanged`, `EgressProbe.probe`;
`wbparser/src/main/kotlin/ru/wbparser/infra/db/repositories/EgressGroupQueries.kt` — `EgressGroupRecord.ipChanges`, `upsertEgressGroup`;
`app/src/main/kotlin/ru/wbparser/app/scheduling/AdsPowerPreflightRunner.kt` — предупреждение о смене адреса в `probeEgressGroups`.

## Класс отказа (FailureClass)

Чей вины отказ — `Egress` (виноват адрес) или `TargetCandidate` (виноват, возможно, каталог). `Egress` — WB отказал, circuit группы открылся, свободных профилей нет;
`TargetCandidate` — WB ответил, но ответом был не каталог: популярный список вместо категории или тело, которое не распарсилось. `TargetCandidate` — это **кандидат**, а
не приговор: выжженный адрес отвечает деградированным телом тоже, а деградированное тело неотличимо от сломанного каталога, пока его не увидели с более чем одного адреса.
Решение принимает раннер: если группа здорова или та же аномалия виделась с другой группы — это каталог.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/domain/scheduling/FailureClass.kt` — `FailureClass`, `DomainError.failureClass()`;
`wbparser/src/main/kotlin/ru/wbparser/infra/runner/CrawlRunner.kt` — `CrawlRunner.recordOutcome`; `wbparser/src/main/kotlin/ru/wbparser/domain/scheduling/Target.kt` —
`Target.anomalyGroup`.

## Back-off цели (TargetBackoff)

Два back-off, и они не взаимозаменяемы. **Целевой** растёт до суток: 30 мин → 1 ч → 2 ч → 4 ч → 8 ч → 16 ч → 24 ч (`TargetBackoff.targetDelay`). **Адресный** остаётся в
минутах: 15 мин → 30 мин → 1 ч → 2 ч (`TargetBackoff.egressDelay`) — цель тут не виновата, и ждать надо только, чтобы адреса остыли.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/domain/scheduling/FailureClass.kt` — `TargetBackoff`, `TargetBackoff.targetDelay`, `TargetBackoff.egressDelay`,
`TargetBackoff.TARGET_BASE_MINUTES`, `TargetBackoff.EGRESS_BASE_MINUTES`.

## Аренда цели (lease)

**Что это:** `crawl_targets.lease_owner` и `crawl_targets.lease_until` — кто сейчас
читает цель и до какого момента. Хост забирает цели одним утверждением
(`FOR UPDATE SKIP LOCKED`), поэтому два хоста никогда не берут одну цель. Аренда
истекает сама: хост, который умер, не держит цели вечно.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/db/repositories/TargetQueries.kt` —
`claimDueTargets`, `releaseTargetLeases`; миграция
`app/src/main/resources/db/migration/V11__crawl_target_leases.sql`.

**Связано с:** область блокировки (`crawler.lock.scope`).

## Свежесть (FreshnessPolicy, claimDueTargets)

Политика свежести: сколько минут должно пройти с последнего чтения цели, чтобы её снова читали (по умолчанию 240 минут). Цель не due по двум причинам: её недавно
прочитали (окно свежести не прошло) либо она ждёт после отказа (не прошла пауза).

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/scheduler/FreshnessPolicy.kt` —
`FreshnessPolicy`; правило «что due» — в SQL забора цели
(`claimDueTargets` в `wbparser/src/main/kotlin/ru/wbparser/infra/db/repositories/TargetQueries.kt`).
Правило одно: его утверждение — в базе.

## Состояние цели живёт в базе

`last_crawled_at`, `next_attempt_at` и оба счётчика отказов лежат в `crawl_targets`, а не в памяти краулера: раньше состояние жило в памяти процесса и умирало вместе с
ним — после рестарта каждая цель выглядела непрочитанной, и краулер заново читал весь каталог. `last_crawled_at` двигается только у цели, которую действительно прочитали:
упавшая цель сохраняет старое значение и остаётся due.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/domain/pipeline/ExposedTables.kt` — `CrawlTargetsTable.lastCrawledAt`, `CrawlTargetsTable.nextAttemptAt`,
`CrawlTargetsTable.consecutiveFailures`, `CrawlTargetsTable.egressFailureCount`, `CrawlTargetsTable.anomalyGroup`;
`wbparser/src/main/kotlin/ru/wbparser/infra/db/repositories/TargetQueries.kt` — `markTargetCrawled`, `markTargetFailed`, `markTargetRequeued`;
`app/src/main/resources/db/migration/V9__crawl_target_state.sql`.

## Исход прогона (RunOutcome)

Словарь одного прогона цели: `ok` (цель прочитали), `antibot` (WB отказал — виноват адрес), `target_candidate` (WB ответил не каталогом), `error` (прогон сломался без
класса). `dbValue` — то, что лежит в колонке. `RunOutcome.of` переводит исход прогона в этот словарь: нет отказа → `Ok`, отказ с классом → этот класс, отказ без класса →
`Error`.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/domain/scheduling/RunOutcome.kt` — `RunOutcome`, `RunOutcome.of`.

## Агрегат цели и прогоны (crawl_targets и crawl_target_runs)

Две разные таблицы, и их нельзя смешивать. `crawl_targets` — **агрегат цели**: что цель представляет сейчас (последнее чтение, пауза, счётчики, аномалия).
`crawl_target_runs` — **прогоны сами по себе**: одна строка на попытку прочитать цель, с адресом, исходом, числом страниц и товаров и временем начала. Адрес в прогоне не
передаётся: он читается из `crawl_egress_groups` тем же запросом, чтобы факт об адресе жил в одном месте. `targetsLastServedBy(groupId)` отдаёт цели, чей **последний**
прогон обслужила эта группа, а `releaseEgressPenalty(groupId)` снимает наложенное адресом наказание: обнуляет `egress_failure_count` и пересчитывает `next_attempt_at` по
**собственному** счётчику цели (`consecutive_failures` через `TargetBackoff.targetDelay`) — собственная история цели не трогается.

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/domain/scheduling/RunOutcome.kt` — `RunOutcome`;
`wbparser/src/main/kotlin/ru/wbparser/infra/db/repositories/TargetRunQueries.kt` — `TargetRun`, `recordTargetRun`, `targetsLastServedBy`, `releaseEgressPenalty`,
`fetchTargetRuns`; `app/src/main/resources/db/migration/V10__crawl_target_runs.sql` — колонки и ограничения таблицы прогонов.

## Таблицы

| Таблица | Что это | Миграция |
|---|---|---|
| `crawl_targets` | Цели краулера и их состояние: что читать, как глубоко, когда прочитано в последний раз, когда можно пробовать снова | `app/src/main/resources/db/migration/V1__init.sql`, `app/src/main/resources/db/migration/V9__crawl_target_state.sql` |
| `crawl_target_runs` | Отдельные прогоны цели: адрес, исход, страницы, товары, времена | `app/src/main/resources/db/migration/V10__crawl_target_runs.sql` |
| `crawl_egress_groups` | Наблюдения пробы: какой адрес у группы, изолирована ли она, сколько раз адрес менялся. Журнал, а не управляющая таблица | `app/src/main/resources/db/migration/V8__crawl_egress_groups.sql` |
| `crawl_jobs` | Прогоны паука как таковые: статус, счётчики запросов, heartbeat | `app/src/main/resources/db/migration/V1__init.sql` |
| `scraped_items` | Сохранённые товары: одна строка на товар в рамках цели | `app/src/main/resources/db/migration/V1__init.sql`, `app/src/main/resources/db/migration/V2__catalog_columns.sql`, `app/src/main/resources/db/migration/V3__expand_content_hash.sql` |
| `category_mappings` | Дерево категорий, увиденное при обходе: категория, в которой видели товар, и её родитель | `app/src/main/resources/db/migration/V1__init.sql` |
| `wb_subjects` | Словарь предметов WB: id, который присылает API каталога, и имя, которое этому id соответствует | `app/src/main/resources/db/migration/V5__wb_subjects.sql` |

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/domain/pipeline/ExposedTables.kt` — `CrawlTargetsTable`, `CrawlJobsTable`, `ScrapedItemsTable`,
`CategoryMappingsTable`, `WbSubjectsTable`, `CrawlEgressGroupsTable`; `app/src/main/resources/db/migration/` — миграции Flyway, владеющие схемой.

## Метрики

`crawl.egress.*` — по egress-группам, тег `egress_group`:

| Метрика | Тип | Что говорит |
|---|---|---|
| `crawl.egress.requests` | counter | Сколько запросов ушло через группу (любой исход) |
| `crawl.egress.pages` | counter | Сколько из них завершились успехом |
| `crawl.egress.antibot.responses` | counter | Сколько ответов было антибот-отказами |
| `crawl.egress.circuit.state` | gauge | Состояние circuit: `0` closed, `1` half-open, `2` open |
| `crawl.egress.pacer.wait` | timer | Сколько запрос ждал слот своей группы |
| `crawl.egress.all_down` | counter | Ни одна группа не смогла обслужить запрос в отведённое время (без тега) |
| `crawl.egress.isolated` | gauge | Изолирована ли группа по адресу: `1` / `0` |
| `crawl.egress.ip.changes` | counter | Сколько раз адрес группы менялся |

`crawl.targets.*` — по целям, тег `target` (значение `wb_catalog_id`, иначе имя):

| Метрика | Тип | Что говорит |
|---|---|---|
| `crawl.targets.without_items` | counter | Страницы прочитаны, товаров не сохранено ни одного |
| `crawl.targets.truncated` | counter | Обход остановился на лимите, каталог прочитан не до конца; тег `reason`: `pages` или `depth` |

**Где правда:** `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressRouter.kt` — `crawl.egress.requests`, `crawl.egress.pages`, `crawl.egress.antibot.responses`,
`crawl.egress.circuit.state`, `crawl.egress.pacer.wait`, `crawl.egress.all_down`; `wbparser/src/main/kotlin/ru/wbparser/infra/http/EgressProbe.kt` —
`crawl.egress.isolated`, `crawl.egress.ip.changes`; `wbparser/src/main/kotlin/ru/wbparser/infra/runner/CrawlMetrics.kt` — `crawl.pages`, `crawl.items`,
`crawl.targets.without_items`, `crawl.targets.truncated`, `crawl.items.without_category_name`.

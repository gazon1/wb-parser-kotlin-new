#!/usr/bin/env python3
"""
WB Browser Fetch — получает товары каталога WB через Chrome (AdsPower) via page.evaluate(fetch()).
Обходит антибот проверку: fetch() внутри браузера использует Chrome networking.

Эндпоинт: `__internal/u-search/exactmatch/ru/common/v18/search` — именно он отдаёт
товары каталога. Старый `__internal/u-recom/personal/v9/search` отдаёт ротацию
«популярных пресетов», к конкретному каталогу не привязанных.

Фильтр каталога живёт в параметре `query`: это токен из WB-меню
(например `menu_v3_9467 карты памяти`). Меню берётся из
`main-menu-by-ru-v3.json` (поле `searchQuery` узла), но основной путь —
явно переданный `--query`.

Цены WB приходят числами в копейках (`sizes[0].price.basic` = 272000 → 2720 ₽).

Usage:
    uv run python scripts/wb_browser_fetch.py --catalog 9467 --query "menu_v3_9467 карты памяти" [--pages 2]
    uv run python scripts/wb_browser_fetch.py --catalog 9467 --menu-file /tmp/menu.json --pages 1
"""

import argparse, asyncio, json, sys, time
from pathlib import Path
from urllib.parse import quote, urlencode

sys.path.insert(0, str(Path(__file__).parent))

from adspower_utils import get_cdp_ws_url, resolve_api_base

API_URL = "https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search"

DEFAULT_DEST = "1259570991"

# Параметры запроса WB к поисковому эндпоинту. Порядок и состав зафиксированы
# самим WB: dest — регион выдачи, hide_vflags — маска скрытых флагов,
# resultset=catalog и sort=popular — режим выдачи.
DEFAULT_HEADERS = {
    "sec-ch-ua-platform": '"Linux"',
    "sec-ch-ua": '"Google Chrome";v="143", "Chromium";v="143", "Not A(Brand";v="24"',
    "sec-ch-ua-mobile": "?0",
    "deviceid": "site_a39361daf4ec45f2a68c5c6ca73c8adf",
    "x-requested-with": "XMLHttpRequest",
    "x-clientinfo": "appType=1&curr=rub&dest=1259570991&spp=30&hide_vflags=4294967296&ab_testing=false&lang=ru",
    "user-agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
    "x-userid": "0",
    "x-spa-version": "14.28.3",
    "accept": "*/*"
}

# Задержка прогрева сессии: антибот-челлендж WB живёт несколько секунд.
DEFAULT_WARMUP_DELAY = 3.5

# HTTP-статусы, которыми WB отказывает. Именно они считаются отказом в замере
# доли отказов (docs/operations/egress-groups-runbook.md).
REFUSAL_STATUSES = {498, 403, 401}


def build_params(query: str, page_num: int = 1, dest: str = DEFAULT_DEST) -> dict:
    """Параметры запроса к WB search-эндпоинту (чистая функция)."""
    return {
        "ab_testing": "false",
        "appType": "1",
        "curr": "rub",
        "dest": dest,
        "hide_vflags": "4294967296",
        "lang": "ru",
        "locale": "ru",
        "page": str(page_num),
        "query": query,
        "resultset": "catalog",
        "sort": "popular",
        "spp": "30",
    }


def build_url(query: str, page_num: int = 1, dest: str = DEFAULT_DEST) -> str:
    """URL страницы выдачи. `query` уходит в URL-кодировке (кириллица, пробелы)."""
    params = build_params(query, page_num, dest)
    return API_URL + "?" + urlencode(params, quote_via=quote)


def kopecks_to_rubles(value):
    """Цены WB приходят в копейках: 272000 → 2720.0 руб. None/мусор → None."""
    if value is None or isinstance(value, bool):
        return None
    try:
        return round(float(value) / 100.0, 2)
    except (TypeError, ValueError):
        return None


def find_menu_node(nodes, catalog_id: str):
    """Найти узел WB-меню по id каталога (рекурсивно по `childs`)."""
    target = str(catalog_id)
    for node in nodes or []:
        if str(node.get("id")) == target:
            return node
        found = find_menu_node(node.get("childs") or [], catalog_id)
        if found is not None:
            return found
    return None


def resolve_query(catalog_id, query=None, menu_file=None):
    """
    Определить `query`-токен для выдачи.

    Порядок источников:
      1. явно переданный `--query` (основной путь);
      2. `--menu-file` — узел WB-меню с этим id каталога, поле `searchQuery`;
      3. fallback из id каталога: `menu_v3_<id>` (без текста запроса).

    Возвращает (query, источник, узел меню или None).
    """
    if query:
        return query, "--query", None

    if menu_file:
        nodes = json.loads(Path(menu_file).read_text(encoding="utf-8"))
        node = find_menu_node(nodes, catalog_id)
        if node and node.get("searchQuery"):
            return node["searchQuery"], f"--menu-file ({menu_file})", node
        print(
            f"Warning: в меню нет узла с id={catalog_id} и searchQuery; "
            f"используется fallback из id каталога.",
            file=sys.stderr,
        )

    if catalog_id:
        return f"menu_v3_{catalog_id}", "fallback (menu_v3_<catalog id>)", None

    raise ValueError("Не указан ни --query, ни --catalog — нечего запрашивать")


def default_warmup_url(query: str, menu_node=None) -> str:
    """
    Страница, на которой прогревается WB-сессия.

    Из меню берём канонический URL категории (`/catalog/elektronika/.../karty-pamyati`),
    иначе — поиск по запросу.
    """
    if menu_node and menu_node.get("url"):
        return "https://www.wildberries.ru" + menu_node["url"]
    return "https://www.wildberries.ru/catalog/0/search.aspx?search=" + quote(query)


async def fetch_page_with_status(page, query: str, page_num: int, headers: dict) -> tuple[int, dict]:
    """Fetch one page, returning the HTTP status and the parsed body.

    The status is what the refusal measurement needs: WB answers 498/403/401
    with a body that is not a catalog, and the body alone cannot tell a refusal
    from an empty catalog.
    """
    url = build_url(query, page_num)
    referer_headers = {**headers, "referer": "https://www.wildberries.ru/"}

    script = f"""
    async () => {{
        const resp = await fetch("{url}", {{
            method: 'GET',
            headers: {json.dumps(referer_headers)},
            credentials: 'include'
        }});
        const text = await resp.text();
        let body = null;
        try {{
            body = JSON.parse(text);
        }} catch (e) {{
            body = {{__raw: text.slice(0, 500)}};
        }}
        return {{status: resp.status, body: body}};
    }}
    """
    result = await page.evaluate(script)
    status = int(result.get("status") or 0)
    body = result.get("body")
    return status, body if isinstance(body, dict) else {}


async def fetch_products_page(page, query: str, page_num: int, headers: dict) -> dict:
    """Fetch one page using page.evaluate(fetch())"""
    _, body = await fetch_page_with_status(page, query, page_num, headers)
    return body


def extract_product_fields(prod: dict) -> dict:
    sizes = prod.get('sizes') or []
    primary = sizes[0] if sizes else {}
    price = primary.get('price') or {}
    return {
        "nm_id": prod.get('id'),
        "name": prod.get('name', ''),
        "brand": prod.get('brand', ''),
        "brand_id": prod.get('brandId'),
        "supplier": prod.get('supplier', ''),
        "supplier_id": prod.get('supplierId'),
        "supplier_rating": prod.get('supplierRating'),
        "root": prod.get('root'),
        "subject_id": prod.get('subjectId'),
        "subject_parent_id": prod.get('subjectParentId'),
        "rating": prod.get('rating'),
        "review_rating": prod.get('reviewRating'),
        "feedbacks": prod.get('feedbacks'),
        "pics": prod.get('pics'),
        # Цены WB — числа в копейках; *_rub — то же в рублях.
        "price_basic": price.get('basic'),
        "price_basic_rub": kopecks_to_rubles(price.get('basic')),
        "price_product": price.get('product'),
        "price_product_rub": kopecks_to_rubles(price.get('product')),
        "price_logistics": price.get('logistics'),
        "price_logistics_rub": kopecks_to_rubles(price.get('logistics')),
        "price_cashback": price.get('cashback'),
        "price_cashback_rub": kopecks_to_rubles(price.get('cashback')),
        "total_quantity": prod.get('totalQuantity'),
        "wh": prod.get('wh'),
        "warehouse_id": primary.get('wh') if primary else None,
        "preset_type": prod.get('preset_type'),
    }


async def resolve_cdp_ws_url(profile_id: str, start_timeout: float = 60.0) -> str | None:
    """
    CDP WebSocket URL профиля AdsPower через Local API (v2, без api-key).

    Local API на loopback не требует ключа: `/api/v2/browser-profile/active`
    отдаёт `data.ws.puppeteer`. Если профиль не запущен — стартуем его
    (`POST /api/v2/browser-profile/start`, id в теле запроса) и ждём.
    """
    import httpx

    base = resolve_api_base()

    def _active() -> str | None:
        try:
            r = httpx.get(
                f"{base}/api/v2/browser-profile/active",
                params={"profile_id": profile_id},
                timeout=10,
            )
            data = (r.json() or {}).get("data") or {}
            ws = (data.get("ws") or {}).get("puppeteer")
            if ws:
                return ws
        except Exception as e:
            print(f"[adspower] active probe failed: {e}", file=sys.stderr)
        return None

    ws_url = _active()
    if ws_url:
        return ws_url

    print(f"[adspower] профиль {profile_id} не запущен, запускаю...", file=sys.stderr)
    try:
        httpx.post(
            f"{base}/api/v2/browser-profile/start",
            json={"profile_id": profile_id},
            timeout=30,
        )
    except Exception as e:
        print(f"[adspower] start failed: {e}", file=sys.stderr)

    deadline = time.monotonic() + start_timeout
    while time.monotonic() < deadline:
        await asyncio.sleep(2)
        ws_url = _active()
        if ws_url:
            return ws_url

    return None


async def fetch_catalog(
    ws_url: str,
    query: str,
    pages: int = 1,
    delay_between_pages: float = 2.0,
    warmup_url: str | None = None,
    warmup_delay: float = DEFAULT_WARMUP_DELAY,
    headers: dict | None = None,
    count: int | None = None,
) -> dict:
    """Connect to AdsPower Chrome and fetch products via page.evaluate(fetch()).

    Two modes:

    - **pages mode** (the default): fetch up to `pages` catalog pages and stop
      at the first refusal or empty page. This is what a crawl wants.
    - **count mode** (`count` given): make exactly `count` requests, whatever
      the answers are, and report how many were refused. This is what the
      egress measurement wants: a fixed request volume is the denominator the
      refusal share is computed against, and stopping early would make the
      sample size depend on the very thing being measured.
    """
    from playwright.async_api import async_playwright

    headers = headers if headers is not None else DEFAULT_HEADERS
    products: list[dict] = []
    page_counts: list[int] = []
    exhausted = False
    catalog_total: int | None = None
    outcomes: list[dict] = []

    async with async_playwright() as p:
        browser = await p.chromium.connect_over_cdp(ws_url)
        ctx = browser.contexts[0]

        # Create a new page in the existing (authenticated) context
        page = await ctx.new_page()

        try:
            target = warmup_url or default_warmup_url(query)
            print(f"Прогрев WB-сессии: {target}", file=sys.stderr)
            try:
                await page.goto(target, timeout=30000)
            except Exception as e:
                print(f"  Прогрев не удался ({e}), продолжаю", file=sys.stderr)
            # Wait for anti-bot challenge to resolve
            await asyncio.sleep(warmup_delay)

            total_requests = count if count is not None else pages
            for attempt in range(total_requests):
                # The request shape is held constant: the same catalog, the same
                # headers, the same page range. Only the rate and the profile
                # change between the runs of the measurement.
                page_num = (attempt % pages) + 1
                label = f"{attempt + 1}/{total_requests}"
                print(f"Fetching request {label} (page {page_num})...", file=sys.stderr)

                try:
                    status, data = await fetch_page_with_status(page, query, page_num, headers)
                    refused = status in REFUSAL_STATUSES
                    outcome = {
                        "n": attempt + 1,
                        "page": page_num,
                        "status": status,
                        "refused": refused,
                        "error": None,
                    }
                    if refused:
                        print(f"  Refused with HTTP {status}", file=sys.stderr)
                        outcomes.append(outcome)
                        if count is None:
                            break
                        if attempt + 1 < total_requests:
                            await asyncio.sleep(delay_between_pages)
                        continue

                    if not isinstance(data, dict) or 'products' not in data:
                        # WB на антиботе/ошибке отдаёт конверт без `products` —
                        # пустой список здесь означал бы «каталог кончился», а это ложь.
                        reason = (data or {}).get('message') or (data or {}).get('msg') or data
                        outcome["error"] = f"unexpected response: {str(reason)[:200]}"
                        outcomes.append(outcome)
                        if count is None:
                            break
                        if attempt + 1 < total_requests:
                            await asyncio.sleep(delay_between_pages)
                        continue
                    page_products = data.get('products') or []

                    if not page_products:
                        print(f"  No products on page {page_num}.", file=sys.stderr)
                        outcome["empty"] = True
                        outcomes.append(outcome)
                        if count is None:
                            exhausted = True
                            break
                        if attempt + 1 < total_requests:
                            await asyncio.sleep(delay_between_pages)
                        continue

                    meta_total = (data.get('metadata') or {}).get('total') or data.get('total')
                    if isinstance(meta_total, int):
                        catalog_total = meta_total

                    for prod in page_products:
                        extracted = extract_product_fields(prod)
                        extracted['page'] = page_num
                        products.append(extracted)

                    page_counts.append(len(page_products))
                    outcomes.append(outcome)
                    print(f"  Got {len(page_products)} products (total: {len(products)})", file=sys.stderr)

                except Exception as e:
                    print(f"  Error on request {label}: {e}", file=sys.stderr)
                    outcomes.append({
                        "n": attempt + 1,
                        "page": page_num,
                        "status": 0,
                        "refused": False,
                        "error": str(e)[:200],
                    })
                    if count is None:
                        break

                if attempt + 1 < total_requests:
                    await asyncio.sleep(delay_between_pages)
        finally:
            # Закрываем свою вкладку только если в контексте есть и другие:
            # если наша вкладка — единственная, её закрытие убьёт Chrome AdsPower.
            # browser.close() для CDP-подключения закрывает сам браузер, поэтому
            # его не вызываем — профиль должен остаться жить.
            try:
                if len(ctx.pages) > 1:
                    await page.close()
            except Exception:
                pass

    return {
        "products": products,
        "page_counts": page_counts,
        "pages_fetched": len(page_counts),
        "exhausted": exhausted,
        "catalog_total": catalog_total,
        "outcomes": outcomes,
    }


def price_stats_rub(products: list[dict]) -> dict:
    """Минимум/максимум/медиана по ценам в рублях (цены WB — копейки)."""
    values = sorted(
        p["price_basic_rub"] for p in products if p.get("price_basic_rub") is not None
    )
    if not values:
        return {"min": None, "max": None, "median": None, "count": 0}
    mid = len(values) // 2
    median = values[mid] if len(values) % 2 else round((values[mid - 1] + values[mid]) / 2, 2)
    return {"min": values[0], "max": values[-1], "median": median, "count": len(values)}


def measurement_of(outcomes: list[dict], label: str | None) -> dict:
    """Сводка замера: объём запросов, отказы, доля отказов.

    Доля отказов = отказы / запросы. Знаменатель обязателен: без него числитель
    ничего не значит (docs/decisions/2026-10-20-anti-bot-refusals-are-measured.md).
    """
    requests = len(outcomes)
    refused = sum(1 for o in outcomes if o.get("refused"))
    errored = sum(1 for o in outcomes if o.get("error"))
    succeeded = requests - refused - errored
    return {
        "label": label,
        "requests": requests,
        "refused": refused,
        "errored": errored,
        "succeeded": succeeded,
        "refusal_ratio": round(refused / requests, 4) if requests else None,
        "statuses": sorted({o.get("status", 0) for o in outcomes}),
    }


def print_summary(summary: dict, catalog_id, query: str, query_source: str, pages_requested: int) -> None:
    """Короткая сводка по выдаче."""
    stats = summary["price_stats"]
    print("=== Summary ===", file=sys.stderr)
    print(f"catalog: {catalog_id}", file=sys.stderr)
    if summary.get("catalog_total") is not None:
        print(f"catalog total (WB metadata): {summary['catalog_total']}", file=sys.stderr)
    print(f"query: {query} (source: {query_source})", file=sys.stderr)
    print(
        f"pages: requested={pages_requested}, fetched={summary['pages_fetched']}, "
        f"catalog exhausted: {'yes' if summary['exhausted'] else 'no'}",
        file=sys.stderr,
    )
    per_page = ", ".join(f"page {i + 1}: {c}" for i, c in enumerate(summary["page_counts"]))
    print(f"products: {summary['total']} ({per_page})", file=sys.stderr)
    if stats["count"]:
        print(
            f"prices (RUB, ₽; WB returns kopecks): min={stats['min']}, "
            f"max={stats['max']}, median={stats['median']} (n={stats['count']})",
            file=sys.stderr,
        )
    else:
        print("prices (RUB, ₽): no prices in the response", file=sys.stderr)

    measurement = summary.get("measurement")
    if measurement:
        label = f" [{measurement['label']}]" if measurement.get("label") else ""
        print(f"=== Measurement{label} ===", file=sys.stderr)
        print(
            f"requests={measurement['requests']}, refused={measurement['refused']}, "
            f"succeeded={measurement['succeeded']}, errored={measurement['errored']}",
            file=sys.stderr,
        )
        ratio = measurement.get("refusal_ratio")
        print(f"refusal ratio: {ratio if ratio is not None else 'n/a'}", file=sys.stderr)


async def main():
    parser = argparse.ArgumentParser(description="Fetch WB catalog products via browser")
    parser.add_argument("--catalog", help="WB catalog ID")
    parser.add_argument("--query", help="WB search query token, e.g. 'menu_v3_9467 карты памяти'")
    parser.add_argument("--search-query", dest="search_query", help="Alias for --query")
    parser.add_argument("--menu-file", help="Path to WB main-menu-by-ru-v3.json (fallback source of --query)")
    parser.add_argument("--pages", type=int, default=1, help="Number of pages to fetch")
    parser.add_argument(
        "--count",
        type=int,
        help=(
            "Make exactly this many requests, whatever the answers are, and report the "
            "refusal share. Overrides --pages: the request volume is the denominator "
            "the refusal share is computed against."
        ),
    )
    parser.add_argument(
        "--label",
        help="Label of this run (A0..A6 in the egress measurement protocol)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print the measurement summary as JSON on stdout instead of the products dump",
    )
    parser.add_argument("--profile", default="k18a6v75", help="AdsPower profile ID")
    parser.add_argument("--output", "-o", help="Output JSON file")
    parser.add_argument("--delay", type=float, default=2.0, help="Delay between pages (seconds)")
    parser.add_argument("--warmup-url", help="URL used to warm the WB session")
    parser.add_argument(
        "--warmup-delay",
        type=float,
        default=DEFAULT_WARMUP_DELAY,
        help="Seconds to wait for the anti-bot challenge (default: %(default)s)",
    )
    parser.add_argument("--dest", default=DEFAULT_DEST, help="WB delivery region code")
    args = parser.parse_args()

    query = args.query or args.search_query
    if args.query and args.search_query and args.query != args.search_query:
        parser.error("--query and --search-query disagree; pass only one")

    if not query and not args.catalog:
        parser.error("нужен --query или --catalog")

    try:
        query, query_source, menu_node = resolve_query(args.catalog, query, args.menu_file)
    except ValueError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

    ws_url = await resolve_cdp_ws_url(args.profile)
    if not ws_url:
        ws_url = get_cdp_ws_url(args.profile)
    if not ws_url:
        print(
            f"Error: не удалось получить CDP URL профиля '{args.profile}'. "
            f"Открой его в AdsPower или проверь Local API.",
            file=sys.stderr
        )
        sys.exit(1)

    print(f"Connecting to AdsPower Chrome (profile {args.profile})...", file=sys.stderr)
    result = await fetch_catalog(
        ws_url=ws_url,
        query=query,
        pages=args.pages,
        delay_between_pages=args.delay,
        warmup_url=args.warmup_url or default_warmup_url(query, menu_node),
        warmup_delay=args.warmup_delay,
        count=args.count,
    )

    products = result["products"]
    for prod in products:
        prod['catalog_id'] = args.catalog

    measurement = measurement_of(result["outcomes"], args.label)
    summary = {
        "total": len(products),
        "page_counts": result["page_counts"],
        "pages_fetched": result["pages_fetched"],
        "exhausted": result["exhausted"],
        "catalog_total": result["catalog_total"],
        "price_stats": price_stats_rub(products),
        "measurement": measurement,
    }
    print_summary(summary, args.catalog, query, query_source, args.pages)

    output = {
        "catalog_id": args.catalog,
        "query": query,
        "query_source": query_source,
        "pages_requested": args.pages,
        "pages_fetched": result["pages_fetched"],
        "exhausted": result["exhausted"],
        "catalog_total": result["catalog_total"],
        "total_products": len(products),
        "measurement": measurement,
        "products": products
    }

    if args.output:
        with open(args.output, 'w') as f:
            json.dump(output, f, indent=2, ensure_ascii=False)
        print(f"Written {len(products)} products to {args.output}", file=sys.stderr)
    elif args.json:
        # The measurement run does not need the goods — it needs the numbers, and
        # they have to land somewhere a comparison script can read.
        print(json.dumps(measurement, indent=2, ensure_ascii=False))
    else:
        print(json.dumps(output, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())

#!/usr/bin/env python3
"""
WB Browser Fetch — получает товары через Chrome (AdsPower) via page.evaluate(fetch()).
Обходит антибот проверку: fetch() внутри браузера использует Chrome networking.

Usage:
    uv run python scripts/wb_browser_fetch.py --catalog 131954 [--pages 3] [--profile k18a6v75]
"""

import argparse, asyncio, json, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from adspower_utils import get_cdp_ws_url


API_URL = "https://www.wildberries.ru/__internal/u-recom/personal/v9/search"

DEFAULT_HEADERS = {
    "sec-ch-ua-platform": '"Linux"',
    "type": "purchases_incorrect_search",
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


def build_url(catalog_id: str, page_num: int, dest: str = "1259570991") -> str:
    params = {
        "ab_testing": "false", "appType": "1", "curr": "rub", "dest": dest,
        "spp": "30", "hide_vflags": "4294967296", "lang": "ru", "locale": "ru",
        "page": str(page_num), "query": "0", "resultset": "catalog", "sort": "popular"
    }
    return API_URL + "?" + "&".join(f"{k}={v}" for k, v in params.items())


async def fetch_products_page(page, catalog_id: str, page_num: int, headers: dict) -> dict:
    """Fetch one page using page.evaluate(fetch())"""
    url = build_url(catalog_id, page_num)
    referer_headers = {**headers, "referer": f"https://www.wildberries.ru/catalog/{catalog_id}/list.aspx"}

    script = f"""
    async () => {{
        const resp = await fetch("{url}", {{
            method: 'GET',
            headers: {json.dumps(referer_headers)},
            credentials: 'include'
        }});
        return await resp.json();
    }}
    """
    return await page.evaluate(script)


def extract_product_fields(prod: dict) -> dict:
    sizes = prod.get('sizes', [])
    primary = sizes[0] if sizes else {}
    price = primary.get('price', {}) if primary else {}
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
        "price_basic": price.get('basic'),
        "price_product": price.get('product'),
        "price_logistics": price.get('logistics'),
        "price_cashback": price.get('cashback'),
        "total_quantity": prod.get('totalQuantity'),
        "wh": prod.get('wh'),
        "warehouse_id": primary.get('wh') if primary else None,
        "preset_type": prod.get('preset_type'),
    }


async def fetch_catalog(
    ws_url: str,
    catalog_id: str,
    pages: int = 1,
    delay_between_pages: float = 2.0
) -> list[dict]:
    """Connect to AdsPower Chrome and fetch products via page.evaluate(fetch())"""
    from playwright.async_api import async_playwright

    products = []

    async with async_playwright() as p:
        browser = await p.chromium.connect_over_cdp(ws_url)
        ctx = browser.contexts[0]

        # Create a new page in the existing (authenticated) context
        page = await ctx.new_page()

        print(f"Navigating to WB catalog {catalog_id}...", file=sys.stderr)
        await page.goto(
            f"https://www.wildberries.ru/catalog/{catalog_id}/list.aspx",
            timeout=30000
        )
        # Wait for anti-bot challenge to resolve
        await asyncio.sleep(5)

        for page_num in range(1, pages + 1):
            print(f"Fetching page {page_num}/{pages}...", file=sys.stderr)

            try:
                data = await fetch_products_page(page, catalog_id, page_num, DEFAULT_HEADERS)
                page_products = data.get('products', [])

                if not page_products:
                    print(f"  No products on page {page_num}, stopping.", file=sys.stderr)
                    break

                for prod in page_products:
                    extracted = extract_product_fields(prod)
                    extracted['catalog_id'] = catalog_id
                    extracted['page'] = page_num
                    products.append(extracted)

                print(f"  Got {len(page_products)} products (total: {len(products)})", file=sys.stderr)

                if page_num < pages:
                    await asyncio.sleep(delay_between_pages)

            except Exception as e:
                print(f"  Error on page {page_num}: {e}", file=sys.stderr)
                break

        await browser.close()

    return products


async def main():
    parser = argparse.ArgumentParser(description="Fetch WB catalog products via browser")
    parser.add_argument("--catalog", required=True, help="WB catalog ID")
    parser.add_argument("--pages", type=int, default=1, help="Number of pages to fetch")
    parser.add_argument("--profile", default="k18a6v75", help="AdsPower profile ID")
    parser.add_argument("--output", "-o", help="Output JSON file")
    parser.add_argument("--delay", type=float, default=2.0, help="Delay between pages (seconds)")
    args = parser.parse_args()

    ws_url = get_cdp_ws_url(args.profile)
    if not ws_url:
        print(
            f"Error: Browser for profile '{args.profile}' is not running. "
            f"Open it in AdsPower first.",
            file=sys.stderr
        )
        sys.exit(1)

    print(f"Connecting to AdsPower Chrome (CDP port detection)...", file=sys.stderr)
    products = await fetch_catalog(
        ws_url=ws_url,
        catalog_id=args.catalog,
        pages=args.pages,
        delay_between_pages=args.delay
    )

    output = {
        "catalog_id": args.catalog,
        "total_products": len(products),
        "products": products
    }

    if args.output:
        with open(args.output, 'w') as f:
            json.dump(output, f, indent=2, ensure_ascii=False)
        print(f"Written {len(products)} products to {args.output}", file=sys.stderr)
    else:
        print(json.dumps(output, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())

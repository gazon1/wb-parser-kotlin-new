from __future__ import annotations

import importlib.util
import sys
import unittest
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from uuid import UUID

try:
    from pydantic import ValidationError
except ModuleNotFoundError:
    # The gate registry's unit-test run uses a stdlib-only interpreter. The models are
    # Pydantic, so there is nothing to check there; `uv run pytest scripts/tests` is the
    # run that exercises them, and a skip here is honest about which one ran.
    raise unittest.SkipTest("pydantic is not installed in this interpreter") from None


def _load_models():
    path = Path(__file__).resolve().parents[1] / "parser" / "catalog_models.py"
    spec = importlib.util.spec_from_file_location("catalog_models", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    # Pydantic resolves the module's postponed annotations through sys.modules, so the
    # module has to be registered before its classes are built.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


models = _load_models()

CARD = {
    "id": "3f2c0000-0000-4000-8000-000000000001",
    "targetId": "3f2c0000-0000-4000-8000-000000000002",
    "targetName": "Ноутбуки",
    "title": 'Ноутбук 15,6", 16 ГБ',
    "priceKopecks": 12999000,
    "salePriceKopecks": 9999000,
    "brand": "ASUS",
    "seller": None,
    "cashbackPercent": 5.55,
    "cashbackRubles": 1234.56,
    "inStock": True,
    "imageUrl": "https://basket-01.wbbasket.ru/vol1/part1/1/images/big/1.webp",
    "productUrl": "https://www.wildberries.ru/catalog/1/detail.aspx",
    "scrapedAt": "2026-11-17T10:15:30Z",
}

PAGE = {
    "items": [CARD],
    "totalCount": 1,
    "page": 0,
    "pageSize": 24,
    "totalPages": 1,
    "hasNextPage": False,
    "hasPrevPage": False,
    "instanceId": "crawler-1",
    "dataAsOf": "2026-11-17T10:15:30Z",
}

STATS = {
    "totalItems": 1000,
    "totalProducts": 900,
    "totalTargets": 132,
    "newestScrapedAt": "2026-11-17T10:15:30Z",
    "withCashback": 500,
    "productsWithCashback": 450,
    "asOf": "2026-11-17T10:20:00Z",
    "instanceId": "crawler-1",
    "dataAsOf": "2026-11-17T10:15:30Z",
}


class ProductCardTest(unittest.TestCase):
    def test_camel_case_payload_reads_into_snake_case_fields(self) -> None:
        card = models.ProductCard.model_validate(CARD)

        self.assertEqual(card.id, UUID(CARD["id"]))
        self.assertEqual(card.target_name, "Ноутбуки")
        self.assertEqual(card.price_kopecks, 12999000)
        self.assertIsNone(card.seller)
        self.assertTrue(card.in_stock)
        self.assertIsInstance(card.scraped_at, datetime)
        self.assertIsNotNone(card.scraped_at.tzinfo)

    def test_money_is_decimal_and_keeps_the_wire_value(self) -> None:
        card = models.ProductCard.model_validate(CARD)

        self.assertEqual(card.cashback_rubles, Decimal("1234.56"))
        self.assertEqual(card.cashback_percent, Decimal("5.55"))
        # float would fail this: float("1234.56") == Decimal("1234.56") is False.
        self.assertNotEqual(float(CARD["cashbackRubles"]), card.cashback_rubles)

    def test_optional_fields_default_to_none_when_absent(self) -> None:
        minimal = {key: CARD[key] for key in ("id", "targetId", "targetName", "title", "productUrl", "scrapedAt")}
        card = models.ProductCard.model_validate(minimal)

        self.assertIsNone(card.price_kopecks)
        self.assertIsNone(card.cashback_rubles)
        self.assertIsNone(card.image_url)

    def test_a_required_field_is_required(self) -> None:
        payload = dict(CARD)
        del payload["productUrl"]

        with self.assertRaises(ValidationError):
            models.ProductCard.model_validate(payload)


class PagedProductCardTest(unittest.TestCase):
    def test_items_come_from_the_referenced_card(self) -> None:
        parsed = models.PagedProductCard.model_validate(PAGE)

        self.assertIsInstance(parsed.items[0], models.ProductCard)
        self.assertEqual(parsed.total_count, 1)
        self.assertFalse(parsed.has_next_page)
        self.assertIsInstance(parsed.data_as_of, datetime)

    def test_null_data_moment_is_accepted_and_absent_one_is_null(self) -> None:
        empty = models.PagedProductCard.model_validate({**PAGE, "items": [], "dataAsOf": None})

        self.assertEqual(empty.data_as_of, None)
        self.assertEqual(empty.items, [])

    def test_serialisation_keeps_the_contract_aliases(self) -> None:
        dumped = models.PagedProductCard.model_validate(PAGE).model_dump(mode="json", by_alias=True)

        self.assertIn("totalCount", dumped)
        self.assertIn("pageSize", dumped)
        self.assertIn("dataAsOf", dumped)
        self.assertNotIn("total_count", dumped)


class ProductDetailTest(unittest.TestCase):
    def test_all_of_merges_the_card_and_the_history(self) -> None:
        payload = {
            **CARD,
            "history": [
                {
                    "scrapedAt": "2026-11-16T09:00:00Z",
                    "priceKopecks": 13999000,
                    "cashbackPercent": 3.0,
                    "cashbackRubles": 41.97,
                }
            ],
            "instanceId": "crawler-1",
            "dataAsOf": None,
        }

        detail = models.ProductDetail.model_validate(payload)

        self.assertIsInstance(detail, models.ProductCard)
        self.assertEqual(detail.title, CARD["title"])
        self.assertEqual(detail.history[0].price_kopecks, 13999000)
        self.assertEqual(detail.history[0].cashback_rubles, Decimal("41.97"))
        self.assertIsNone(detail.data_as_of)


class SiteStatsTest(unittest.TestCase):
    def test_counters_and_both_moments_are_read(self) -> None:
        stats = models.SiteStats.model_validate(STATS)

        self.assertEqual(stats.total_products, 900)
        self.assertEqual(stats.products_with_cashback, 450)
        self.assertEqual(stats.instance_id, "crawler-1")
        self.assertIsNotNone(stats.as_of)
        self.assertIsNotNone(stats.data_as_of)

    def test_the_instance_that_answered_is_required(self) -> None:
        payload = dict(STATS)
        del payload["instanceId"]

        with self.assertRaises(ValidationError):
            models.SiteStats.model_validate(payload)


if __name__ == "__main__":
    unittest.main()

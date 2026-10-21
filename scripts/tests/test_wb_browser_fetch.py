#!/usr/bin/env python3
"""Тесты чистых частей `wb_browser_fetch.py`.

Что покрыто
-----------
Только то, что не ходит в сеть: сборка URL/параметров выдачи, разбор цен
(копейки → рубли) и определение `query`-токена. Всё, что связано с браузером
и AdsPower, намеренно не тестируется — это требует живого профиля.

Файл написан на `unittest.TestCase` по конвенции соседнего
`test_gate_scripts.py`: тот же файл запускается и гейтом
(`python3 -m unittest discover -s scripts/tests`), и pytest
(`uv run pytest scripts/tests/test_wb_browser_fetch.py`).
"""

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent


def load(name: str, filename: str):
    """Импортировать скрипт по пути — они не пакет, а имя файла с дефисом."""
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    assert spec and spec.loader, f"cannot load {filename}"
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


FETCH = load("wb_browser_fetch", "wb_browser_fetch.py")

QUERY = "menu_v3_9467 карты памяти"


class TestBuildUrl(unittest.TestCase):
    """URL выдачи: endpoint, набор параметров, кодировка, пагинация."""

    def test_uses_exactmatch_v18_endpoint(self):
        url = FETCH.build_url(QUERY, 1)
        self.assertTrue(
            url.startswith("https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search?"),
            url,
        )

    def test_does_not_use_the_old_recom_endpoint(self):
        """Старый эндпоинт отдаёт «популярные пресеты», а не каталог."""
        self.assertNotIn("u-recom", FETCH.build_url(QUERY, 1))

    def test_carries_the_full_parameter_set(self):
        params = FETCH.build_params(QUERY, 1)
        self.assertEqual(
            params,
            {
                "ab_testing": "false",
                "appType": "1",
                "curr": "rub",
                "dest": "1259570991",
                "hide_vflags": "4294967296",
                "lang": "ru",
                "locale": "ru",
                "page": "1",
                "query": QUERY,
                "resultset": "catalog",
                "sort": "popular",
                "spp": "30",
            },
        )

    def test_query_is_url_encoded(self):
        """Кириллица и пробел должны быть закодированы, а не вставлены как есть."""
        url = FETCH.build_url(QUERY, 1)
        self.assertIn("query=menu_v3_9467%20%D0%BA%D0%B0%D1%80%D1%82%D1%8B%20%D0%BF%D0%B0%D0%BC%D1%8F%D1%82%D0%B8", url)
        self.assertNotIn(" ", url)

    def test_page_number_changes_the_url(self):
        self.assertIn("page=1", FETCH.build_url(QUERY, 1))
        self.assertIn("page=7", FETCH.build_url(QUERY, 7))

    def test_dest_is_configurable(self):
        self.assertIn("dest=1259570991", FETCH.build_url(QUERY, 1))
        self.assertIn("dest=103191", FETCH.build_url(QUERY, 1, dest="103191"))


class TestKopecksToRubles(unittest.TestCase):
    """WB отдаёт цены числами в копейках."""

    def test_converts_kopecks(self):
        self.assertEqual(FETCH.kopecks_to_rubles(272000), 2720.0)

    def test_converts_string_number(self):
        self.assertEqual(FETCH.kopecks_to_rubles("272000"), 2720.0)

    def test_keeps_two_decimals(self):
        self.assertEqual(FETCH.kopecks_to_rubles(123456), 1234.56)

    def test_none_stays_none(self):
        self.assertIsNone(FETCH.kopecks_to_rubles(None))

    def test_garbage_stays_none(self):
        self.assertIsNone(FETCH.kopecks_to_rubles("не цена"))


class TestExtractProductFields(unittest.TestCase):
    """Цена берётся из `sizes[0].price` — это числа в копейках."""

    PRODUCT = {
        "id": 123456,
        "name": "Карта памяти",
        "sizes": [{"price": {"basic": 272000, "product": 249900, "cashback": 0, "logistics": 100}, "wh": 5}],
    }

    def test_reads_price_from_first_size(self):
        fields = FETCH.extract_product_fields(self.PRODUCT)
        self.assertEqual(fields["price_basic"], 272000)
        self.assertEqual(fields["price_basic_rub"], 2720.0)
        self.assertEqual(fields["price_product_rub"], 2499.0)

    def test_keeps_raw_kopecks_and_rubles(self):
        fields = FETCH.extract_product_fields(self.PRODUCT)
        self.assertEqual(fields["price_logistics"], 100)
        self.assertEqual(fields["price_logistics_rub"], 1.0)

    def test_product_without_sizes_has_no_price(self):
        fields = FETCH.extract_product_fields({"id": 1, "name": "x"})
        self.assertIsNone(fields["price_basic"])
        self.assertIsNone(fields["price_basic_rub"])

    def test_size_without_price_does_not_raise(self):
        fields = FETCH.extract_product_fields({"id": 1, "sizes": [{"wh": 1}]})
        self.assertIsNone(fields["price_basic"])


class TestPriceStats(unittest.TestCase):
    def test_min_max_median_in_rubles(self):
        products = [
            {"price_basic_rub": 100.0},
            {"price_basic_rub": 300.0},
            {"price_basic_rub": 200.0},
        ]
        stats = FETCH.price_stats_rub(products)
        self.assertEqual(stats["min"], 100.0)
        self.assertEqual(stats["max"], 300.0)
        self.assertEqual(stats["median"], 200.0)
        self.assertEqual(stats["count"], 3)

    def test_even_count_median_is_the_middle(self):
        products = [{"price_basic_rub": 100.0}, {"price_basic_rub": 300.0}]
        self.assertEqual(FETCH.price_stats_rub(products)["median"], 200.0)

    def test_products_without_price_are_ignored(self):
        products = [{"price_basic_rub": None}, {"price_basic_rub": 50.0}]
        stats = FETCH.price_stats_rub(products)
        self.assertEqual(stats["count"], 1)
        self.assertEqual(stats["min"], 50.0)

    def test_no_prices_is_not_a_crash(self):
        stats = FETCH.price_stats_rub([])
        self.assertIsNone(stats["min"])
        self.assertEqual(stats["count"], 0)


class TestResolveQuery(unittest.TestCase):
    """`query`-токен: явный аргумент — основной путь, меню — запасной."""

    MENU = {
        "id": 9467,
        "name": "Карты памяти",
        "url": "/catalog/elektronika/smartfony-i-telefony/karty-pamyati",
        "searchQuery": QUERY,
        "childs": [{"id": 1, "searchQuery": "menu_v3_1 прочее"}],
    }

    def test_explicit_query_wins(self):
        query, source, node = FETCH.resolve_query("9467", QUERY, None)
        self.assertEqual(query, QUERY)
        self.assertEqual(source, "--query")
        self.assertIsNone(node)

    def test_fallback_from_catalog_id(self):
        query, source, node = FETCH.resolve_query("9467", None, None)
        self.assertEqual(query, "menu_v3_9467")
        self.assertIsNone(node)
        self.assertIn("fallback", source)

    def test_menu_file_supplies_the_search_query(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "menu.json"
            path.write_text(json.dumps([self.MENU], ensure_ascii=False), encoding="utf-8")
            query, source, node = FETCH.resolve_query("9467", None, str(path))
        self.assertEqual(query, QUERY)
        self.assertIn("--menu-file", source)
        self.assertEqual(node["id"], 9467)

    def test_menu_node_is_found_in_children(self):
        nested = [{"id": 100, "childs": [self.MENU]}]
        found = FETCH.find_menu_node(nested, "9467")
        self.assertIsNotNone(found)
        self.assertEqual(found["searchQuery"], QUERY)

    def test_unknown_catalog_id_in_menu_falls_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "menu.json"
            path.write_text(json.dumps([self.MENU], ensure_ascii=False), encoding="utf-8")
            query, source, node = FETCH.resolve_query("999999", None, str(path))
        self.assertEqual(query, "menu_v3_999999")
        self.assertIsNone(node)


class TestWarmupUrl(unittest.TestCase):
    """Прогрев сессии идёт на страницу каталога WB."""

    def test_uses_menu_node_url(self):
        node = {"url": "/catalog/elektronika/smartfony-i-telefony/karty-pamyati"}
        self.assertEqual(
            FETCH.default_warmup_url(QUERY, node),
            "https://www.wildberries.ru/catalog/elektronika/smartfony-i-telefony/karty-pamyati",
        )

    def test_falls_back_to_search_page(self):
        url = FETCH.default_warmup_url(QUERY, None)
        self.assertTrue(url.startswith("https://www.wildberries.ru/catalog/0/search.aspx?search="))
        self.assertNotIn(" ", url)


class TestMeasurement(unittest.TestCase):
    """Сводка замера: объём, отказы, доля отказов.

    Доля отказов обязана иметь знаменатель — иначе числитель ничего не значит
    (docs/decisions/2026-10-20-anti-bot-refusals-are-measured.md).
    """

    def test_counts_refusals_and_successes(self):
        outcomes = [
            {"n": 1, "status": 200, "refused": False, "error": None},
            {"n": 2, "status": 498, "refused": True, "error": None},
            {"n": 3, "status": 200, "refused": False, "error": None},
        ]
        m = FETCH.measurement_of(outcomes, "A1")
        self.assertEqual(m["requests"], 3)
        self.assertEqual(m["refused"], 1)
        self.assertEqual(m["succeeded"], 2)
        self.assertEqual(m["errored"], 0)

    def test_refusal_ratio_is_refused_over_requests(self):
        outcomes = [{"status": 498, "refused": True, "error": None}] * 3
        outcomes += [{"status": 200, "refused": False, "error": None}] * 7
        m = FETCH.measurement_of(outcomes, None)
        self.assertEqual(m["requests"], 10)
        self.assertAlmostEqual(m["refusal_ratio"], 0.3)

    def test_errors_are_not_refusals(self):
        """Ошибка сети — это не отказ WB: смешивать их нельзя."""
        outcomes = [
            {"status": 0, "refused": False, "error": "timeout"},
            {"status": 498, "refused": True, "error": None},
        ]
        m = FETCH.measurement_of(outcomes, None)
        self.assertEqual(m["refused"], 1)
        self.assertEqual(m["errored"], 1)
        self.assertEqual(m["succeeded"], 0)

    def test_no_requests_is_not_a_crash(self):
        m = FETCH.measurement_of([], None)
        self.assertEqual(m["requests"], 0)
        self.assertIsNone(m["refusal_ratio"])

    def test_label_is_kept(self):
        m = FETCH.measurement_of([{"status": 200, "refused": False, "error": None}], "A5")
        self.assertEqual(m["label"], "A5")

    def test_refusal_statuses_are_the_antibot_ones(self):
        self.assertEqual(FETCH.REFUSAL_STATUSES, {498, 403, 401})


if __name__ == "__main__":
    unittest.main()

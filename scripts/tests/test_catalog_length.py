#!/usr/bin/env python3
"""Тесты чистых частей измерения длины каталога и применения лимита.

Что покрыто
-----------
Только то, что не ходит в сеть: как читается `total` из ответа WB, сколько страниц
из него следует, какой лимит получается на каждой из двух границ пагинации, и как
отчёт превращается в `known_pages_limit` в YAML. Всё, что связано с браузером и
AdsPower, намеренно не тестируется — это требует живого профиля.

Файл написан на `unittest.TestCase` по конвенции соседних тестов: тот же файл
запускается и гейтом (`python3 -m unittest discover -s scripts/tests`), и pytest.
"""

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
CATALOG_SCRIPTS = SCRIPTS / "wb-catalog"

# `catalog_length` и `apply_measured_limits` импортируют модули по имени — они
# лежат рядом с собой, а не в пакете.
sys.path.insert(0, str(CATALOG_SCRIPTS))


def load(name: str, path: Path):
    """Импортировать скрипт по пути — они не пакет, а имя файла."""
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader, f"cannot load {path}"
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


LENGTH = load("catalog_length", CATALOG_SCRIPTS / "catalog_length.py")
APPLY = load("apply_measured_limits", CATALOG_SCRIPTS / "apply_measured_limits.py")


class TestReadTotal(unittest.TestCase):
    """`total` — это число товаров в выдаче, а не длина каталога."""

    def test_reads_top_level_total(self):
        self.assertEqual(LENGTH.read_total({"total": 49394, "products": []}), 49394)

    def test_reads_metadata_total(self):
        self.assertEqual(LENGTH.read_total({"metadata": {"total": 120}}), 120)

    def test_accepts_a_numeric_string(self):
        self.assertEqual(LENGTH.read_total({"total": "42"}), 42)

    def test_missing_total_is_none(self):
        """Нет total — значит «неизвестно», а не «ноль»."""
        self.assertIsNone(LENGTH.read_total({"products": []}))

    def test_non_dict_is_none(self):
        self.assertIsNone(LENGTH.read_total("not json"))

    def test_boolean_is_not_a_number(self):
        self.assertIsNone(LENGTH.read_total({"total": True}))


class TestEstimatePages(unittest.TestCase):
    def test_rounds_up(self):
        self.assertEqual(LENGTH.estimate_pages(250, 100), 3)

    def test_exact_multiple(self):
        self.assertEqual(LENGTH.estimate_pages(200, 100), 2)

    def test_zero_is_zero(self):
        self.assertEqual(LENGTH.estimate_pages(0, 100), 0)

    def test_observed_page_size_of_99(self):
        self.assertEqual(LENGTH.estimate_pages(200, 99), 3)


class TestKnownPagesLimit(unittest.TestCase):
    """Две границы — два разных числа."""

    def test_catalog_end_gets_the_empty_page(self):
        """Каталог кончился сам: лимит на одну страницу больше, чтобы прочитать пустую."""
        self.assertEqual(LENGTH.known_pages_limit(59, LENGTH.BOUNDARY_CATALOG_END), 60)

    def test_api_cap_does_not_get_an_extra_page(self):
        """Дальше API не отдаёт: лимит — последняя читаемая страница."""
        self.assertEqual(LENGTH.known_pages_limit(60, LENGTH.BOUNDARY_API_CAP), 60)

    def test_limit_never_exceeds_the_api_cap(self):
        """Страница выше предела API приходит ошибкой — просить её нельзя."""
        self.assertEqual(LENGTH.clamp_to_api_cap(61, 60), 60)
        self.assertEqual(LENGTH.clamp_to_api_cap(13, 60), 13)


class TestIsPageRefusal(unittest.TestCase):
    """Отказ по номеру страницы — не то же самое, что отказ вообще."""

    def test_page_param_envelope_is_a_refusal(self):
        data = {"error": "binding request: page param malformed", "code": 500}
        self.assertTrue(LENGTH.is_page_refusal(data))

    def test_a_normal_page_is_not_a_refusal(self):
        self.assertFalse(LENGTH.is_page_refusal({"products": [], "total": 0}))

    def test_another_error_is_not_a_refusal(self):
        self.assertFalse(LENGTH.is_page_refusal({"error": "internal error", "code": 500}))

    def test_non_dict_is_not_a_refusal(self):
        self.assertFalse(LENGTH.is_page_refusal("<!doctype html>"))


class TestPlanLimit(unittest.TestCase):
    def test_keeps_the_estimate_next_to_the_measurement(self):
        """Оценка по `total` и измеренное число — рядом: их расхождение и есть факт."""
        plan = LENGTH.plan_limit(113786, 100, LENGTH.BOUNDARY_CATALOG_END, 59, 60)
        self.assertEqual(plan["estimated_pages"], 1138)
        self.assertEqual(plan["pages_available"], 59)
        self.assertEqual(plan["known_pages_limit"], 60)

    def test_without_a_cap_the_limit_is_untouched(self):
        plan = LENGTH.plan_limit(200, 100, LENGTH.BOUNDARY_CATALOG_END, 2)
        self.assertEqual(plan["known_pages_limit"], 3)


class TestMeasuredLimits(unittest.TestCase):
    """В YAML попадает только то, что реально измерили."""

    REPORT = {
        "catalogs": [
            {
                "id": "9467",
                "status": "measured",
                "known_pages_limit": 60,
                "boundary": "api_cap",
                "pages_available": 60,
            },
            {
                "id": "9463",
                "status": "blocked",
                "known_pages_limit": 5,
                "error": "WB served a challenge",
            },
            {
                "id": "9477",
                "status": "measured",
                "known_pages_limit": 0,
                "boundary": "api_cap",
                "pages_available": 0,
            },
        ]
    }

    def test_only_measured_catalogs_with_a_number(self):
        limits = APPLY.measured_limits(self.REPORT)
        self.assertEqual(list(limits.keys()), ["9467"])

    def test_a_blocked_measurement_has_no_limit(self):
        limits = APPLY.measured_limits(self.REPORT)
        self.assertNotIn("9463", limits)

    def test_a_zero_limit_is_not_a_limit(self):
        limits = APPLY.measured_limits(self.REPORT)
        self.assertNotIn("9477", limits)


class TestPlanChanges(unittest.TestCase):
    PAGES = [
        {"id": "9467", "name": "Карты памяти", "known_pages_limit": 5},
        {"id": "9463", "name": "Смартфоны", "known_pages_limit": 5},
    ]

    LIMITS = {
        "9467": {
            "id": "9467",
            "status": "measured",
            "known_pages_limit": 60,
            "boundary": "api_cap",
            "pages_available": 60,
        }
    }

    def test_only_the_measured_target_changes(self):
        changes, missing = APPLY.plan_changes(self.PAGES, self.LIMITS)
        self.assertEqual([c.catalog_id for c in changes], ["9467"])
        self.assertEqual(changes[0].old, 5)
        self.assertEqual(changes[0].new, 60)
        self.assertEqual(missing, [])

    def test_a_measured_catalog_missing_from_the_yaml_is_reported(self):
        limits = dict(self.LIMITS)
        limits["9999"] = {
            "id": "9999",
            "status": "measured",
            "known_pages_limit": 10,
            "boundary": "api_cap",
            "pages_available": 10,
        }
        changes, missing = APPLY.plan_changes(self.PAGES, limits)
        self.assertEqual(missing, ["9999"])

    def test_an_unchanged_limit_is_not_a_change(self):
        pages = [{"id": "9467", "name": "Карты памяти", "known_pages_limit": 60}]
        changes, _ = APPLY.plan_changes(pages, self.LIMITS)
        self.assertEqual(changes, [])


class TestApplyToYaml(unittest.TestCase):
    def test_writes_the_measured_limit_and_keeps_the_rest(self):
        pages = [
            {"id": "9467", "name": "Карты памяти", "known_pages_limit": 5},
            {"id": "9463", "name": "Смартфоны", "known_pages_limit": 5},
        ]
        limits = {
            "9467": {
                "id": "9467",
                "status": "measured",
                "known_pages_limit": 60,
                "boundary": "api_cap",
                "pages_available": 60,
            }
        }
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "targets.yaml"
            path.write_text(
                "pages:\n"
                "- id: '9467'\n"
                "  name: Карты памяти\n"
                "  known_pages_limit: 5\n"
                "- id: '9463'\n"
                "  name: Смартфоны\n"
                "  known_pages_limit: 5\n",
                encoding="utf-8",
            )
            loaded = APPLY.load_pages(path)
            changes, _ = APPLY.plan_changes(loaded, limits)
            by_id = {c.catalog_id: c for c in changes}
            for page in loaded:
                change = by_id.get(str(page.get("id")))
                if change:
                    page["known_pages_limit"] = change.new
            APPLY.write_pages(path, loaded)

            result = APPLY.load_pages(path)

        self.assertEqual(result[0]["known_pages_limit"], 60)
        self.assertEqual(result[1]["known_pages_limit"], 5)
        self.assertEqual(result[0]["name"], "Карты памяти")


class TestMergeReport(unittest.TestCase):
    """Повторный замер дополняет отчёт, а не затирает его."""

    MEASURE = load("measure_catalog_length", CATALOG_SCRIPTS / "measure_catalog_length.py")

    def test_a_replaced_catalog_keeps_its_place(self):
        existing = {
            "version": 1,
            "catalogs": [
                {"id": "9467", "status": "measured", "known_pages_limit": 60},
                {"id": "9463", "status": "blocked", "error": "challenge"},
            ],
        }
        fresh = {
            "version": 1,
            "runs": 2,
            "catalogs": [
                {"id": "9463", "status": "measured", "known_pages_limit": 60},
            ],
        }
        merged = self.MEASURE.merge_report(existing, fresh)

        self.assertEqual([c["id"] for c in merged["catalogs"]], ["9467", "9463"])
        self.assertEqual(merged["catalogs"][1]["status"], "measured")
        self.assertEqual(merged["runs"], 2)

    def test_a_new_catalog_is_appended(self):
        existing = {"version": 1, "catalogs": [{"id": "9467", "status": "measured"}]}
        fresh = {"version": 1, "catalogs": [{"id": "9477", "status": "measured"}]}
        merged = self.MEASURE.merge_report(existing, fresh)
        self.assertEqual([c["id"] for c in merged["catalogs"]], ["9467", "9477"])


class TestReportShape(unittest.TestCase):
    """Отчёт должен читаться: у каждой записи есть статус и, если измерено, число."""

    def test_report_round_trip(self):
        report = {
            "version": 1,
            "catalogs": [
                {
                    "id": "9467",
                    "status": "measured",
                    "known_pages_limit": 60,
                    "boundary": "api_cap",
                    "pages_available": 60,
                    "wb_total": 49394,
                }
            ],
        }
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "report.json"
            path.write_text(json.dumps(report, ensure_ascii=False), encoding="utf-8")
            loaded = APPLY.load_report(path)

        limits = APPLY.measured_limits(loaded)
        self.assertEqual(limits["9467"]["known_pages_limit"], 60)
        self.assertEqual(limits["9467"]["wb_total"], 49394)


if __name__ == "__main__":
    unittest.main()

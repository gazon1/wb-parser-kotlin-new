#!/usr/bin/env python3
"""Тесты адаптера chrome-use `wildberries/catalog-request`.

Зачем эти тесты
---------------
Адаптер — прибор, а не код: он показывает, какие заголовки браузер отбросил, а какие
ушли на провод. Поэтому он ценен ровно настолько, насколько точен: заголовок, который
браузер отбросил, но адаптер отметил как отправленный, делает прибор врётцом — и врёт
он молча, потому что адаптер продолжает работать и отвечать.

Что покрыто
-----------
Правило «браузер отбрасывает этот заголовок» проверяется исполнением: адаптер
запускается под Node с подменённым `fetch`, который записывает ушедший запрос. Так
проверяется и список запрещённых имён, и префиксы, и условный случай
`X-HTTP-Method*` (запрещён, только если значение — запрещённый метод).

Почему это отдельный тест
--------------------------
Правило живёт в JavaScript-файле, а не в Kotlin: дублировать его в тесте значило бы
проверять копию, а не прибор. Node запускает сам адаптер, поэтому тест падает, когда
правило расходится с поведением браузера.

Написан на `unittest.TestCase` по конвенции соседних тестов: тот же файл запускается
гейтом (`uv run pytest scripts/tests`). Node берётся из `mise.toml`; если его нет —
тест пропускается, а не проходит молча.
"""

import json
import os
import shutil
import subprocess
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
ADAPTER = REPO_ROOT / "devtools" / "chrome-use-sites" / "wildberries" / "catalog-request.js"

#: Запускает адаптер и печатает, что он просил отправить и что браузер отбросил.
#:
#: `fetch` подменён: адаптер отвечает JSON-телом, а настоящий запрос туда не уходит.
RUNNER = r"""
const fs = require('fs');

const source = fs.readFileSync(process.env.ADAPTER_PATH, 'utf8');
// Адаптер — это `@meta`-комментарий и одна функция; комментарий отбрасывается,
// а сама функция вычисляется как выражение.
const body = source.replace(/\/\*[\s\S]*?\*\//, '').trim();
const adapter = eval('(' + body + ')');

let sent = null;
globalThis.fetch = async (url, init) => {
  sent = {url: url, init: init};
  return {
    status: 200,
    ok: true,
    headers: {get: () => 'text/plain'},
    text: async () => '{"products":[]}'
  };
};

const args = JSON.parse(process.env.ADAPTER_ARGS);
adapter(args).then((result) => {
  process.stdout.write(JSON.stringify({result: result, sent: sent}));
});
"""

NODE = shutil.which("node")


def run_adapter(extra_headers: dict | None = None) -> dict:
    """Запускает адаптер с заданными дополнительными заголовками."""
    if NODE is None:
        raise unittest.SkipTest("node is not installed")
    args = {"catalog": "131954", "query": "menu_redirect_131954 колонки"}
    if extra_headers:
        args["extraHeaders"] = json.dumps(extra_headers)
    env = dict(os.environ, ADAPTER_PATH=str(ADAPTER), ADAPTER_ARGS=json.dumps(args))
    completed = subprocess.run(
        [NODE, "-"],
        input=RUNNER,
        capture_output=True,
        text=True,
        check=False,
        env=env,
    )
    if completed.returncode != 0:
        raise AssertionError(f"the adapter failed to run: {completed.stderr}")
    return json.loads(completed.stdout)


class ChromeUseAdapterTest(unittest.TestCase):
    """Поведение адаптера: что он считает отброшенным браузером."""

    def test_the_forbidden_names_are_reported_as_dropped(self) -> None:
        # Набор имён из Fetch Standard: браузер владеет ими, и значение из
        # JavaScript страницы отбрасывается без ошибки.
        result = run_adapter(
            {
                "cookie": "wb_session=abc",
                "user-agent": "Mozilla/5.0 Chrome/143",
                "referer": "https://example.com",
                "set-cookie": "a=b",
            }
        )["result"]

        dropped = {name.lower() for name in result["request"]["droppedByBrowser"]}
        self.assertEqual(
            dropped,
            {"cookie", "user-agent", "referer", "set-cookie"},
        )

    def test_the_forbidden_prefixes_are_reported_as_dropped(self) -> None:
        # Запрещён целый префикс, а не только перечисленные имена.
        result = run_adapter(
            {
                "proxy-authorization": "Basic Zm9v",
                "sec-ch-ua-mobile": "?0",
            }
        )["result"]

        dropped = {name.lower() for name in result["request"]["droppedByBrowser"]}
        self.assertEqual(dropped, {"proxy-authorization", "sec-ch-ua-mobile"})

    def test_a_header_the_browser_sends_is_reported_as_sent(self) -> None:
        # `permissions-policy` и `http2-settings` в запрещённый список не входят, и
        # браузер их отправляет: адаптер не должен врать в другую сторону.
        result = run_adapter(
            {
                "permissions-policy": "x=1",
                "http2-settings": "x",
            }
        )["result"]

        sent = {name.lower() for name in result["request"]["sentByBrowser"]}
        self.assertIn("permissions-policy", sent)
        self.assertIn("http2-settings", sent)
        self.assertEqual(result["request"]["droppedByBrowser"], [])

    def test_the_conditional_names_are_dropped_only_for_a_forbidden_method(self) -> None:
        # `X-HTTP-Method` и его родственники запрещены не всегда, а только когда
        # значение — запрещённый метод (`CONNECT`, `TRACE`, `TRACK`).
        result = run_adapter(
            {
                "x-http-method": "TRACE",
                "x-method-override": "CONNECT",
                "x-http-method-override": "GET, TRACE",
            }
        )["result"]

        dropped = {name.lower() for name in result["request"]["droppedByBrowser"]}
        self.assertEqual(
            dropped,
            {"x-http-method", "x-method-override", "x-http-method-override"},
        )

    def test_the_conditional_names_are_sent_with_an_ordinary_method(self) -> None:
        # С обычным значением эти заголовки браузер отправляет, и адаптер обязан
        # сказать то же самое.
        result = run_adapter(
            {
                "x-http-method": "GET",
                "x-http-method-override": "POST",
            }
        )["result"]

        sent = {name.lower() for name in result["request"]["sentByBrowser"]}
        self.assertIn("x-http-method", sent)
        self.assertIn("x-http-method-override", sent)
        self.assertEqual(result["request"]["droppedByBrowser"], [])

    def test_the_request_reaches_the_fetch_with_the_crawler_headers(self) -> None:
        # Тот же набор заголовков, что и у краулера: адаптер проверяет production-
        # запрос, и без `deviceid` WB отвечает 403.
        sent = run_adapter()["sent"]

        headers = sent["init"]["headers"]
        self.assertEqual(
            sorted(name.lower() for name in headers),
            ["deviceid", "x-requested-with", "x-spa-version", "x-userid"],
        )
        self.assertEqual(sent["init"]["credentials"], "include")
        self.assertIn("u-search/exactmatch", sent["url"])


if __name__ == "__main__":
    unittest.main()

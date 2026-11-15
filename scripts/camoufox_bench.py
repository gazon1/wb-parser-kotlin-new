#!/usr/bin/env python3
"""
camoufox_bench.py — what a local Camoufox profile actually delivers, per profile count.

Why this exists
---------------
The question the Camoufox migration rests on is not "does the browser launch" but "how
much useful work does one machine get out of N profiles, and at what refusal cost". That
question is answered by a stepped run: 1, 2, 3, 4 profiles, each with its own persistent
`user_data_dir`, each issuing the crawler's own catalog API call from inside the page.

Two numbers are kept apart on purpose:

  • **refused** — the anti-bot answer (498/403/401), the same split `wb_browser_fetch.py`
    makes. Mixing it with transport failures would make the share say something about
    the network that it does not say about the site.
  • **errored** — the request never completed. A refusal is the site answering; an error
    is the request not arriving, and only one of the two is about the address.

A worker stops after three consecutive refusals. The point of the measurement is useful
throughput, not how many refusals a fixed quota can be made to produce: a run that keeps
going past a block measures the block, and the fixed quota then says where the block
started rather than how much work the profile got done.

The session, not the address, is what refuses
---------------------------------------------
The first refusal lands between the 9th and 19th request of a session, in a burst, and
moves earlier as more profiles share one address. So `first_refusal` per profile is
reported next to the totals: it is the number that says whose counter was spent.

Usage:
    uv run --with camoufox python scripts/camoufox_bench.py --profiles 1 --count 20
    uv run --with camoufox python scripts/camoufox_bench.py --profiles 4 --proxy socks5://127.0.0.1:1081

The proxy is taken from `--proxy`, else from `CAMOUFOX_PROXY`. A SOCKS5 endpoint with a
username and a password does NOT work here: Playwright refuses it outright with "Browser
does not support socks5 proxy authentication". Point this at an unauthenticated loopback
tunnel (`ssh -N -D 127.0.0.1:1081 <host>`) or at an HTTP proxy, which does accept Basic
auth. Credentials belong in the environment, never in a command line.
"""

from __future__ import annotations

import argparse
import json
import os
import threading
import time
from pathlib import Path
from urllib.parse import quote

# The browser is imported where it is launched, not at module load: the gate that runs
# the pure parts of this file (`python3 -m unittest discover -s scripts/tests`) runs on a
# bare Python, and a Camoufox install is not something `static` should need.
Camoufox = None

API = "https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search"
CATALOG = "https://www.wildberries.ru/catalog/9467/list.aspx?search=" + quote("menu_v3_9467 карты памяти")
QUERY = "menu_v3_9467 карты памяти"

# The anti-bot answer, kept apart from a transport failure.
REFUSAL = {498, 403, 401}

# A worker stops here. Three in a row is a block, not a flake.
CONSECUTIVE_REFUSALS = 3

# The crawler's own headers (`WbCatalogApi`): the request shape must not be the variable.
HEADERS = {
    "deviceid": "site_a39361daf4ec45f2a68c5c6ca73c8adf",
    "x-requested-with": "XMLHttpRequest",
    "x-spa-version": "14.28.3",
    "x-userid": "0",
    "accept": "*/*",
}

FETCH_JS = """
async (args) => {
  const [url, headers] = args;
  const t0 = performance.now();
  const r = await fetch(url, {credentials: 'include', headers});
  const text = await r.text();
  let products = 0;
  try {
    const b = JSON.parse(text);
    products = (b.products || (b.data && b.data.products) || []).length;
  } catch (e) {}
  return {status: r.status, products, ms: performance.now() - t0, bytes: text.length};
}
"""


def api_url(page: int) -> str:
    """The catalog call, with the parameters the crawler sends."""
    params = {
        "ab_testing": "false",
        "appType": "1",
        "curr": "rub",
        "dest": "1259570991",
        "hide_vflags": "4294967296",
        "lang": "ru",
        "locale": "ru",
        "page": page,
        "query": QUERY,
        "resultset": "catalog",
        "sort": "popular",
        "spp": "30",
    }
    return API + "?" + "&".join(f"{key}={quote(str(value))}" for key, value in params.items())


def process_tree(parents: set[int]) -> set[int]:
    """Every descendant of the given pids, by walking the ppid field of /proc/<pid>/stat."""
    tree = set(parents)
    for _ in range(8):
        added = set()
        for entry in Path("/proc").iterdir():
            if not entry.name.isdigit():
                continue
            try:
                ppid = int((entry / "stat").read_text().split()[3])
            except (OSError, IndexError, ValueError):
                continue
            if ppid in tree and int(entry.name) not in tree:
                added.add(int(entry.name))
        if not added:
            break
        tree |= added
    return tree


def resources(marker: str) -> tuple[float, int]:
    """CPU seconds and RSS bytes of the browser launched for this profile.

    The browser is a process tree, not a process: the content processes are where the
    memory goes, and measuring only the parent understates one profile by an order of
    magnitude.
    """
    roots = set()
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            cmdline = (entry / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="ignore")
        except OSError:
            continue
        if marker in cmdline:
            roots.add(int(entry.name))
    cpu = 0.0
    rss = 0
    for pid in process_tree(roots):
        try:
            stat = Path(f"/proc/{pid}/stat").read_text().split()
            cpu += (int(stat[13]) + int(stat[14])) / os.sysconf("SC_CLK_TCK")
            rss += int(Path(f"/proc/{pid}/statm").read_text().split()[1]) * 4096
        except (OSError, IndexError, ValueError):
            continue
    return cpu, rss


class Sampler(threading.Thread):
    """Peak RSS and CPU of one profile's browser, sampled while the profile works."""

    def __init__(self, marker: str) -> None:
        super().__init__(daemon=True)
        self.marker = marker
        self.halt = threading.Event()
        self.peak_rss = 0
        self.cpu_start = 0.0
        self.cpu_end = 0.0

    def run(self) -> None:
        self.cpu_start, _ = resources(self.marker)
        while not self.halt.is_set():
            _, rss = resources(self.marker)
            self.peak_rss = max(self.peak_rss, rss)
            time.sleep(1.0)
        # Sampled here, before the context manager closes the browser: after it, the
        # processes are gone and the cost reads as zero.
        self.cpu_end, _ = resources(self.marker)

    def stop(self) -> None:
        self.halt.set()
        self.join()


def launch_browser() -> type:
    """The browser class, imported where it is used."""
    global Camoufox
    if Camoufox is None:
        from camoufox.sync_api import Camoufox as browser_class

        Camoufox = browser_class
    return Camoufox


def worker(index: int, args: argparse.Namespace, results: list) -> None:
    """One persistent profile: warm the session, then issue the catalog calls."""
    browser_class = launch_browser()
    out: dict = {"profile": index, "requests": [], "error": None}
    data_dir = f"{args.profiles_dir}/profile-{index}"
    Path(data_dir).mkdir(parents=True, exist_ok=True)
    sampler = Sampler(f"profile-{index}")
    sampler.start()
    started = time.time()
    try:
        with browser_class(
            headless=args.headless,
            persistent_context=True,
            user_data_dir=data_dir,
            proxy={"server": args.proxy} if args.proxy else None,
            locale="ru-RU",
            i_know_what_im_doing=True,
        ) as ctx:
            page = ctx.pages[0] if ctx.pages else ctx.new_page()
            page.goto(CATALOG, wait_until="domcontentloaded", timeout=args.timeout_ms)
            page.wait_for_timeout(args.warmup_ms)
            out["fingerprint"] = page.evaluate(
                """() => ({ua: navigator.userAgent, platform: navigator.platform,
                           tz: Intl.DateTimeFormat().resolvedOptions().timeZone,
                           webdriver: navigator.webdriver,
                           plugins: navigator.plugins.length})"""
            )
            try:
                out["egress"] = page.evaluate(
                    "async () => { const r = await fetch('https://api.ipify.org'); return await r.text(); }"
                )
            except Exception as e:  # noqa: BLE001 - the address is a note, not the measurement
                out["egress_error"] = repr(e)[:200]

            consecutive = 0
            for n in range(1, args.count + 1):
                try:
                    answer = page.evaluate(FETCH_JS, [api_url(n), HEADERS])
                except Exception as e:  # noqa: BLE001 - a transport failure is a datum here
                    answer = {"status": 0, "products": 0, "ms": 0, "bytes": 0, "error": repr(e)[:200]}
                answer["n"] = n
                out["requests"].append(answer)
                if answer["status"] in REFUSAL:
                    out.setdefault("first_refusal", n)
                    consecutive += 1
                    if consecutive >= CONSECUTIVE_REFUSALS:
                        out["stopped"] = f"{CONSECUTIVE_REFUSALS} consecutive refusals at request {n}"
                        break
                elif answer["status"] == 200:
                    consecutive = 0
                time.sleep(args.delay)
            sampler.stop()
            out["cpu_s"] = round(sampler.cpu_end - sampler.cpu_start, 1)
    except Exception as e:  # noqa: BLE001 - a failed launch is a datum, not a crash
        out["error"] = repr(e)[:400]
    finally:
        sampler.stop()
        out["elapsed_s"] = round(time.time() - started, 1)
        out["peak_rss_mb"] = round(sampler.peak_rss / 2**20, 1)
        out.setdefault("cpu_s", 0.0)
        results.append(out)


def summarise(results: list, args: argparse.Namespace) -> dict:
    """The run's numbers: useful throughput, the refusal share, and what it cost."""
    answers = [answer for work in results for answer in work["requests"]]
    ok = [answer for answer in answers if answer["status"] == 200]
    refused = [answer for answer in answers if answer["status"] in REFUSAL]
    errored = [answer for answer in answers if answer["status"] == 0]
    elapsed = max((work.get("elapsed_s", 0) for work in results), default=0)
    products = sum(answer.get("products", 0) for answer in ok)
    latencies = sorted(answer["ms"] for answer in ok)
    return {
        "profiles": args.profiles,
        "proxy": args.proxy or "direct",
        "headless": str(args.headless),
        "delay_s": args.delay,
        "requests": len(answers),
        "ok": len(ok),
        "refused": len(refused),
        "errored": len(errored),
        "statuses": sorted({answer["status"] for answer in answers}),
        "products": products,
        "products_per_min": round(products / elapsed * 60, 1) if elapsed else None,
        "requests_per_min": round(len(answers) / elapsed * 60, 1) if elapsed else None,
        "refusal_ratio": round(len(refused) / len(answers), 4) if answers else None,
        "elapsed_s": elapsed,
        "p50_latency_ms": round(latencies[len(latencies) // 2]) if latencies else None,
        "p95_latency_ms": round(latencies[int(len(latencies) * 0.95) - 1]) if latencies else None,
        "peak_rss_mb_sum": round(sum(work.get("peak_rss_mb", 0) for work in results), 1),
        "cpu_s_sum": round(sum(work.get("cpu_s", 0) for work in results), 1),
        "first_refusals": {work["profile"]: work.get("first_refusal") for work in results},
        "egress": sorted({work["egress"] for work in results if work.get("egress")}),
        "fingerprints": [work.get("fingerprint") for work in results],
        "workers": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument("--profiles", type=int, default=1, help="concurrent persistent profiles")
    parser.add_argument("--count", type=int, default=20, help="catalog requests per profile")
    parser.add_argument("--delay", type=float, default=3.0, help="pause between requests, seconds")
    parser.add_argument("--warmup-ms", type=int, default=15000, help="time the session gets to warm up")
    parser.add_argument("--timeout-ms", type=int, default=90000, help="navigation timeout")
    parser.add_argument("--proxy", default=os.environ.get("CAMOUFOX_PROXY", ""), help="Playwright proxy server URL")
    parser.add_argument("--headless", default="true", help="true, false, virtual or virtual-gpu")
    parser.add_argument("--profiles-dir", default="/tmp/camoufox-bench", help="where the user_data_dirs live")
    parser.add_argument("--out", default=None, help="write the JSON here as well as to stdout")
    args = parser.parse_args()
    if args.headless in ("true", "false"):
        args.headless = args.headless == "true"

    results: list = []
    threads = [threading.Thread(target=worker, args=(i, args, results)) for i in range(1, args.profiles + 1)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    text = json.dumps(summarise(results, args), ensure_ascii=False, indent=2)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()

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

A worker stops at the FIRST refusal, and at the first 429 for its own reason. The point
of the measurement is useful throughput, not how many refusals a fixed quota can be made
to produce: the session's counter is spent by the time WB answers 498, so every further
request asks a closed door and adds load without adding knowledge. A 429 is a different
answer — the observed rate is too high — but continuing at the same pace measures how
many 429s the quota can produce, which is the same forbidden load. The two are counted
apart (`first_refusal` / `first_throttle`), mirroring the crawler's own split of `antibot`
and `rate_limited`: a spent session and an exceeded rate must not be added together. With
`--shared-address` either answer also stops the sibling profiles, because the address is
what WB counted against.

The run's profiles belong to one endpoint
-----------------------------------------
`user_data_dir` keeps cookies and storage between runs, so a directory created while the
profiles egressed directly, reused through a proxy, is a session that moved house: history
the site counted against one address arrives at another. The ADR makes one profile one
address one fingerprint, so the directory is named after the proxy endpoint and is refused
under a different one (`EGRESS` marker). There is no override: a new endpoint needs a new
`--profiles-dir`, because moving the directory would move the cookies and storage the old
address was counted against.

The marker binds to the endpoint, not to the external IP observed through it
------------------------------------------------------------------------------
`redacted_proxy()` names the endpoint (`socks5://host:port`), while the external address is
measured separately and only recorded per profile (`egress`). Those are not the same fact:
one endpoint may hand out a different external IP between runs, or balance several. The
marker therefore guards against reusing a directory across *endpoints*, not against an
endpoint whose egress changed. The harness assumes a static endpoint — the production
contract is one profile, one address, one fingerprint — and the recorded `egress` is the
evidence with which a violation can be noticed after the fact, not a check that prevents
it.

The hypothesis this suggests, and does not prove
------------------------------------------------
The first refusal landed between the 11th and 18th request of a session and moved earlier
as profiles shared one address, which is consistent with a **per-session request count**
rather than a rate. It is a reading, not a demonstrated mechanism: every run in the report
used one delay (3.0 s), so rate was never a variable. `first_refusal` per profile is
reported so the reading can be checked against data, not repeated from the report.

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
import re
import threading
import time
from pathlib import Path
from urllib.parse import quote, urlsplit

# The browser is imported where it is launched, not at module load: the gate that runs
# the pure parts of this file (`python3 -m unittest discover -s scripts/tests`) runs on a
# bare Python, and a Camoufox install is not something `static` should need.
Camoufox = None

API = "https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search"
CATALOG = "https://www.wildberries.ru/catalog/9467/list.aspx?search=" + quote("menu_v3_9467 карты памяти")
QUERY = "menu_v3_9467 карты памяти"

# The anti-bot answer, kept apart from a transport failure.
REFUSAL = {498, 403, 401}

# The rate-limit answer, kept apart from the refusal: 498 says the session's counter is
# spent, 429 says the observed rate is too high. The crawler gives them different reasons
# (`antibot` / `rate_limited`) and different back-offs; the benchmark gives them different
# counters, so one cannot hide inside the other.
THROTTLED = {429}

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


def read_stat(pid: int) -> list[str]:
    """The numeric fields of /proc/<pid>/stat, with `comm` removed.

    `comm` is the second field, it is wrapped in parentheses, and it may itself contain
    spaces and parentheses ("Web Content", "Isolated Web Co"). Splitting the whole line
    therefore shifts every later index by however many spaces the name happened to
    have, and a name with one space is enough to read `ppid` out of the wrong column.
    The fixed part of the line is what follows the LAST ')'.

    After the removal the list starts at field 3 (state), so field N is at index N - 3:
    ppid is index 1, utime 11, stime 12.
    """
    raw = Path(f"/proc/{pid}/stat").read_text()
    return raw[raw.rfind(")") + 2 :].split()


def process_tree(parents: set[int]) -> set[int]:
    """Every descendant of the given pids, by walking the ppid field of /proc/<pid>/stat."""
    tree = set(parents)
    for _ in range(8):
        added = set()
        for entry in Path("/proc").iterdir():
            if not entry.name.isdigit():
                continue
            try:
                ppid = int(read_stat(int(entry.name))[1])
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
            stat = read_stat(pid)
            cpu += (int(stat[11]) + int(stat[12])) / os.sysconf("SC_CLK_TCK")
            rss += int(Path(f"/proc/{pid}/statm").read_text().split()[1]) * 4096
        except (OSError, IndexError, ValueError):
            continue
    return cpu, rss


def stop_reason(status: int) -> str | None:
    """Why an answer ends the profile's run, if it does.

    A refusal ends it after the FIRST one. The session's counter is already spent, so
    every further request asks the same closed door: it adds a refusal to the tally and
    traffic to the site, and moves the run no closer to knowing what the profile could
    have done. A run that keeps going past a block measures where the counter ran out,
    not how much work the profile got done.

    A 429 ends it too, under its own name. It is not the session counter running out; it
    is the site saying the rate is too high. Keeping the run going at the same pace would
    turn the measurement into a count of how many 429s the quota produces, which is the
    load the issue forbids — and folding it into the refusal count would answer "is the
    session spent?" with a fact about pace.
    """
    if status in REFUSAL:
        return "refused"
    if status in THROTTLED:
        return "throttled"
    return None


def redacted_proxy(proxy: str) -> str:
    """The endpoint as it may be written down: scheme, host and port, never credentials.

    An HTTP proxy that needs Basic auth carries its password in `--proxy` /
    `CAMOUFOX_PROXY`, and this file writes its proxy string both into the artifact and
    into the profiles-directory marker. Those are places a password does not belong, so
    the userinfo is dropped rather than sanitised. Two endpoints that differ only by
    credentials are one address, which is the identity the marker is about.
    """
    if not proxy:
        return "direct"
    parts = urlsplit(proxy)
    host = parts.hostname or ""
    port = f":{parts.port}" if parts.port else ""
    return f"{parts.scheme}://{host}{port}" if parts.scheme else f"{host}{port}"


def address_tag(proxy: str) -> str:
    """A directory-safe name for the proxy endpoint the profiles belong to."""
    endpoint = redacted_proxy(proxy)
    if endpoint == "direct":
        return "direct"
    return "proxy-" + re.sub(r"[^0-9A-Za-z]+", "-", endpoint).strip("-")


def claim_profiles_dir(profiles_dir: str, proxy: str) -> Path:
    """Bind a profiles directory to one proxy endpoint, and refuse reuse under another.

    `user_data_dir` keeps cookies and storage between runs, so a directory created while
    the profiles egressed directly and then reused through a proxy is a session that
    moved house: it carries history the site counted against another address. The ADR
    makes one profile one address one fingerprint, and the directory is the only part of
    that which survives between runs, so it is where the rule is checked.

    The marker stores the endpoint, not the external address observed through it: the
    endpoint is known before the browser launches, while the egress is not, and one
    endpoint can hand out several addresses. The contract is therefore a static endpoint,
    and `egress` in the artifact is the evidence, not the guard.

    There is deliberately no flag that re-labels an existing directory. Re-labelling would
    leave every `profile-N` — cookies and storage — in place, which is the move this check
    exists to prevent; a new endpoint therefore needs a new directory.
    """
    root = Path(profiles_dir)
    root.mkdir(parents=True, exist_ok=True)
    marker = root / "EGRESS"
    address = redacted_proxy(proxy)
    if marker.exists():
        previous = marker.read_text(encoding="utf-8").strip()
        if previous != address:
            raise SystemExit(
                f"{root} was used by {previous}, not {address}: "
                "a profile that changes address is a new identity "
                "(ADR 2026-10-23-a-changed-address-is-a-new-identity). "
                "Point --profiles-dir at a new directory for this endpoint."
            )
    marker.write_text(address + "\n", encoding="utf-8")
    return root


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
        # `start()` can fail, and `join()` on a thread that never started raises.
        # Stopping is cleanup on every path, including that one.
        if self.ident is not None:
            self.join()


def launch_browser() -> type:
    """The browser class, imported where it is used."""
    global Camoufox
    if Camoufox is None:
        from camoufox.sync_api import Camoufox as browser_class

        Camoufox = browser_class
    return Camoufox


def worker(index: int, args: argparse.Namespace, results: list, stop_all: threading.Event) -> None:
    """One persistent profile: warm the session, then issue the catalog calls.

    A refusal stops this profile at once, and — when every profile shares one address —
    stops the siblings too. The address is the thing WB counts against, so once it has
    said no, letting the others keep asking is the runaway load the issue forbids: the
    only thing left to measure would be how fast refusals arrive.
    """
    out: dict = {"profile": index, "requests": [], "error": None}
    sampler = None
    started = time.time()
    try:
        # Inside the protected block: an import that fails, a directory the process may
        # not create, or a browser that will not start is a measurement, not a thread
        # that disappears without leaving a record in the artifact.
        browser_class = launch_browser()
        data_dir = f"{args.profiles_dir}/profile-{index}"
        Path(data_dir).mkdir(parents=True, exist_ok=True)
        sampler = Sampler(f"profile-{index}")
        sampler.start()
        with browser_class(
            headless=args.headless,
            persistent_context=True,
            user_data_dir=data_dir,
            proxy={"server": args.proxy} if args.proxy else None,
            locale="ru-RU",
            i_know_what_im_doing=True,
        ) as ctx:
            try:
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

                for n in range(1, args.count + 1):
                    if stop_all.is_set():
                        out["stopped"] = "the shared address was refused by another profile"
                        break
                    try:
                        answer = page.evaluate(FETCH_JS, [api_url(n), HEADERS])
                    except Exception as e:  # noqa: BLE001 - a transport failure is a datum here
                        answer = {"status": 0, "products": 0, "ms": 0, "bytes": 0, "error": repr(e)[:200]}
                    answer["n"] = n
                    out["requests"].append(answer)
                    reason = stop_reason(answer["status"])
                    if reason:
                        field = {"refused": "first_refusal", "throttled": "first_throttle"}[reason]
                        out.setdefault(field, n)
                        out["stopped"] = f"{reason} at request {n}; the profile stops there"
                        if args.shared_address:
                            stop_all.set()
                        break
                    time.sleep(args.delay)
            finally:
                # Before the context manager closes the browser: afterwards the processes
                # are gone and the cost reads as zero.
                sampler.stop()
    except Exception as e:  # noqa: BLE001 - a failed launch is a datum, not a crash
        out["error"] = repr(e)[:400]
    finally:
        if sampler is None:
            out["cpu_s"] = 0.0
            out["peak_rss_mb"] = 0.0
        else:
            sampler.stop()
            out["cpu_s"] = round(sampler.cpu_end - sampler.cpu_start, 1)
            out["peak_rss_mb"] = round(sampler.peak_rss / 2**20, 1)
        out["elapsed_s"] = round(time.time() - started, 1)
        results.append(out)


def summarise(results: list, args: argparse.Namespace) -> dict:
    """The run's numbers: useful throughput, the refusal share, and what it cost."""
    answers = [answer for work in results for answer in work["requests"]]
    ok = [answer for answer in answers if answer["status"] == 200]
    refused = [answer for answer in answers if answer["status"] in REFUSAL]
    throttled = [answer for answer in answers if answer["status"] in THROTTLED]
    errored = [answer for answer in answers if answer["status"] == 0]
    elapsed = max((work.get("elapsed_s", 0) for work in results), default=0)
    products = sum(answer.get("products", 0) for answer in ok)
    latencies = sorted(answer["ms"] for answer in ok)
    # A profile that never got a page (navigation timeout, failed launch) did no work.
    # Reporting the requested count as though it had run overstates the level: three
    # profiles of which one timed out are not the same run as three that worked.
    measured = [work for work in results if work["requests"]]
    return {
        "profiles_requested": args.profiles,
        "profiles_measured": len(measured),
        "profiles_refused": sum(1 for work in results if work.get("first_refusal")),
        "shared_address": bool(args.shared_address),
        "proxy": redacted_proxy(args.proxy),
        "headless": str(args.headless),
        "delay_s": args.delay,
        "requests": len(answers),
        "ok": len(ok),
        "refused": len(refused),
        "throttled": len(throttled),
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
        "first_throttles": {work["profile"]: work.get("first_throttle") for work in results},
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
    parser.add_argument(
        "--shared-address",
        action="store_true",
        help="every profile exits through the same address, so one refusal stops them all",
    )
    parser.add_argument("--headless", default="true", help="true, false, virtual or virtual-gpu")
    parser.add_argument(
        "--profiles-dir",
        default=None,
        help="where the user_data_dirs live; default is one directory per endpoint",
    )
    parser.add_argument("--out", default=None, help="write the JSON here as well as to stdout")
    args = parser.parse_args()
    if args.headless in ("true", "false"):
        args.headless = args.headless == "true"
    if args.profiles_dir is None:
        args.profiles_dir = f"/tmp/camoufox-bench/{address_tag(args.proxy)}"
    args.profiles_dir = str(claim_profiles_dir(args.profiles_dir, args.proxy))

    results: list = []
    stop_all = threading.Event()
    threads = [threading.Thread(target=worker, args=(i, args, results, stop_all)) for i in range(1, args.profiles + 1)]
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

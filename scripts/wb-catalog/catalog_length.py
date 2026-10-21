#!/usr/bin/env python3
"""
Pure catalog-length logic — no browser, no network, no clock.

Why this module exists
----------------------
`known_pages_limit` is the per-target page cap the crawler takes. It used to be `5`
for every one of the 132 targets — a round number, not a measurement — and the crawl
stored the first screen of every catalog while looking like a successful run.

Measuring a catalog means asking WB two different questions, and both answers have
to be turned into a number *before* they can be written into the YAML:

1. How many pages does the catalog have?
2. Where does the pagination end — at the end of the catalog, or at the page number
   WB refuses to serve?

The second question decides whether the crawl will end naturally (WB answers with an
empty product list and the target is complete) or at the cap (the target is a slice
and the truncation counter must fire). Conflating the two produces a limit that
either truncates silently or asks WB for a page it refuses to answer — and the
second case is not a harmless mistake: the crawler gets an error envelope instead of
a page, retries it, and fails the target.

Everything here is a pure function of what WB answered, so it can be tested without a
browser. The browser parts live in `measure_catalog_length.py`; writing the numbers
into the YAML lives in `apply_measured_limits.py`.
"""

from __future__ import annotations

from typing import Optional

# WB serves ~100 products per page. The observed page size varies slightly (99 or
# 100), which is why the estimate is computed from the *observed* page size and then
# verified against the API instead of being trusted.
WB_PAGE_SIZE = 100

# Where the pagination ended.
BOUNDARY_CATALOG_END = "catalog_end"  # WB answered with an empty `products` array
BOUNDARY_API_CAP = "api_cap"  # WB refused the page number
BOUNDARY_UNKNOWN = "unknown"

# How the measurement ended is a property of the report, and it lives with the
# report: `catalog_report.py`.


def read_total(data: object) -> Optional[int]:
    """The number of products WB says the catalog holds, or None.

    WB puts it in `total`, and some responses carry it in `metadata.total`. A
    response without a total is not a catalog of length zero — it is a response
    that does not answer the question, and the caller has to fall back to reading
    the pages one by one.
    """
    if not isinstance(data, dict):
        return None

    candidates = [data.get("total")]
    metadata = data.get("metadata")
    if isinstance(metadata, dict):
        candidates.append(metadata.get("total"))

    for candidate in candidates:
        if isinstance(candidate, bool):
            continue
        if isinstance(candidate, int) and candidate >= 0:
            return candidate
        if isinstance(candidate, str) and candidate.isdigit():
            return int(candidate)
    return None


def estimate_pages(total: int, page_size: int = WB_PAGE_SIZE) -> int:
    """How many pages a catalog of `total` products occupies at the observed page size.

    The estimate is only a starting point for the verification probe: it says which
    page to ask about first, it is never written into the YAML as a measurement.
    """
    if total <= 0:
        return 0
    size = page_size if page_size > 0 else WB_PAGE_SIZE
    return -(-total // size)  # ceil without floats


def known_pages_limit(pages_available: int, boundary: str) -> int:
    """The page cap to write into the target, given what the pagination actually did.

    Two cases, and they are not the same number:

    - The catalog ended by itself. The crawl has to read one page *past* the last
      product page: that page comes back empty, the pipeline ends with
      `Stop.EmptyPage`, and the target is recorded as complete. Without that extra
      page the crawl would stop at the cap and the target would be recorded as
      truncated — a false alarm about data that was in fact read to the end.
    - The API cap cut the pagination. There is no empty page to read: the next page
      number is refused with an error envelope, and asking for it would fail the
      target. The cap is the last readable page, and the crawl stops there — with
      the truncation counter firing, because the catalog really was longer.
    """
    if boundary == BOUNDARY_CATALOG_END:
        return pages_available + 1
    if boundary == BOUNDARY_API_CAP:
        return pages_available
    return pages_available


def clamp_to_api_cap(limit: int, page_cap: int) -> int:
    """Never ask WB for a page number the API refuses.

    The API answers a page number above its cap with an error envelope instead of
    an empty product list, so a limit above the cap does not read one page too
    many — it fails the target. The cap is the highest limit that can ever work.
    """
    return min(limit, page_cap)


def is_page_refusal(data: object) -> bool:
    """Whether WB refused the *page number* of the request.

    The refusal looks like `{"error": "binding request: page param malformed",
    "code": 500}` — an envelope without a `products` array, but a specific one: the
    page number is out of range, not the request malformed. Any other envelope
    (or an anti-bot page) is not a refusal of the page number, and treating it as
    one would quietly shrink a catalog that was never measured.
    """
    if not isinstance(data, dict):
        return False
    if "products" in data:
        return False
    error = data.get("error")
    message = f"{error} {data.get('message', '')} {data.get('msg', '')}".lower()
    return "page param" in message


def read_subject_id(products: object) -> Optional[int]:
    """The WB subject id the products of a page carry.

    The products of one catalog carry a single `subjectId` — the subject the category
    belongs to. For the catalog 9467 («Карты памяти») every product of every page
    carries `subjectId` 1260 and `subjectParentId` 6237.

    It is measured, never guessed: a page whose products disagree has no single
    answer, and a page without products has none either. Both are `None` — an
    unmeasured subject id stays unmeasured.
    """
    if not isinstance(products, list) or not products:
        return None
    subject_ids = set()
    for product in products:
        if not isinstance(product, dict):
            return None
        subject_id = product.get("subjectId")
        if not isinstance(subject_id, int) or isinstance(subject_id, bool):
            return None
        subject_ids.add(subject_id)
    if len(subject_ids) != 1:
        return None
    return subject_ids.pop()


def plan_limit(
    total: Optional[int],
    observed_page_size: int,
    boundary: str,
    pages_available: int,
    page_cap: Optional[int] = None,
    subject_id: Optional[int] = None,
) -> dict:
    """The measured result of one catalog, as the report records it.

    `estimate` is kept next to the measured value on purpose: when the estimate and
    the measurement disagree, the disagreement is the interesting part — it means WB's
    `total` does not describe what the pagination serves, and the measurement is the
    one that counts.

    `subject_id` is the subject the catalog's products carry. It is part of the
    measurement — the products were looked at anyway — and it is recorded only when
    it was observed.
    """
    estimate = estimate_pages(total, observed_page_size) if total is not None else None
    limit = known_pages_limit(pages_available, boundary)
    if page_cap is not None:
        limit = clamp_to_api_cap(limit, page_cap)
    plan = {
        "wb_total": total,
        "observed_page_size": observed_page_size,
        "estimated_pages": estimate,
        "pages_available": pages_available,
        "boundary": boundary,
        "known_pages_limit": limit,
    }
    if subject_id is not None:
        plan["wb_subject_id"] = subject_id
    return plan

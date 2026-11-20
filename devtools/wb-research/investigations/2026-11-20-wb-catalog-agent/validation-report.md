# Validation report — 2026-11-20-wb-catalog-agent

- Candidate: `storefront-catalog-search`
- Page: `https://www.wildberries.ru/catalog/131954/list.aspx?search=%3Credacted%3Aquery-param%3E`
- Request: `GET https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search`
- Query parameters: `ab_testing`, `appType`, `curr`, `dest`, `hide_vflags`, `inheritFilters`, `lang`, `locale`, `query`, `resultset`, `sort`, `spp`, `suppressSpellcheck`
- Headers named by the plan: `deviceid`, `x-requested-with`, `x-spa-version`, `x-userid`
- Evidence: [`evidence/validation.json`](evidence/validation.json)
- Generated: 2026-11-20T23:03:31+00:00
- Baseline: `baseline` — reproduced

The headers' values are not in `experiments.yaml` and never reach this report: the plan names the headers, and their values are read from a local file outside the repository.

## Summary

| Experiment | Kind | Runs | Outcome | State | Comparison |
| --- | --- | --- | --- | --- | --- |
| `baseline` | replay | 2 | completed | reproduced | same |
| `sort-priceup` | replay | 2 | completed | validated | changed, changed |
| `page-2` | replay | 2 | completed | validated | changed, changed |
| `page-0` | replay | 1 | completed | observed | changed |
| `after-reload` | reload | 2 | completed | validated | same, same |
| `without-cookies` | replay | 2 | blocked | inconclusive | changed |

## Observations

### `baseline` — reproduced

- run 1: 200 text/plain; charset=UTF-8, 148447 bytes, 100 products, total 113601, keys [metadata, products, total], order `fcce9832c315` — **completed**
- run 2: 200 text/plain; charset=UTF-8, 148447 bytes, 100 products, total 113601, keys [metadata, products, total], order `fcce9832c315` — **completed**

### `sort-priceup` — validated

- run 1: 200 text/plain; charset=UTF-8, 129871 bytes, 100 products, total 113435, keys [metadata, products, total], order `35b9f4f2a3eb`; vs the baseline: changed (order, total) — **completed**
- run 2: 200 text/plain; charset=UTF-8, 129871 bytes, 100 products, total 113435, keys [metadata, products, total], order `35b9f4f2a3eb`; vs the baseline: changed (order, total) — **completed**

### `page-2` — validated

- run 1: 200 text/plain; charset=UTF-8, 145987 bytes, 100 products, total 113601, keys [metadata, products, total], order `d28ecd9d4412`; vs the baseline: changed (order) — **completed**
- run 2: 200 text/plain; charset=UTF-8, 145987 bytes, 100 products, total 113601, keys [metadata, products, total], order `d28ecd9d4412`; vs the baseline: changed (order) — **completed**

### `page-0` — observed

- run 1: 200 application/json, 224 bytes, keys [filters, name, normQuery, query, rmi, rs, search_result, shardKey]; vs the baseline: changed (contentType, schema, count, order, total) — **completed**

### `after-reload` — validated

- run 1: 200 text/plain; charset=UTF-8, 148447 bytes, 100 products, total 113601, keys [metadata, products, total], order `fcce9832c315`; vs the baseline: same (nothing) — **completed**
- run 2: 200 text/plain; charset=UTF-8, 148447 bytes, 100 products, total 113601, keys [metadata, products, total], order `fcce9832c315`; vs the baseline: same (nothing) — **completed**

### `without-cookies` — inconclusive

the site refused the request — a refusal is not a validation

- run 1: 498 text/html, 1132 bytes; vs the baseline: changed (status, contentType, json, schema, count, order, total) — **blocked**
- run 2: not run — the site refused the request

## What was not established

- `without-cookies` — inconclusive: the site refused the request — a refusal is not a validation

## Safety

- Read-only: every observation is a GET replay from the page's own context, with the browser's cookies.
- No bypass: no CAPTCHA, no rate limit and no access control is worked around — a refusal is reported as one.
- One request at a time, with the pause the plan names.
- The fixture carries names, statuses, media types, sizes, the shape of the answer and the product ids; no response body, no cookie and no header value is committed.
- This tool is not part of the mandatory CI: it needs a real browser and a live site.

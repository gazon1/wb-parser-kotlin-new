# The catalog's observability

What to watch, and what each number means when it moves.

The catalog is a read-only service in front of the crawler's database. There is no queue in
it and nothing to retry: every request either reads or does not. That makes it easy to
watch — a change in the numbers is a change in the catalog — and it makes it easy to get
wrong: a slow catalog is a slow storefront, and a slow storefront is the symptom everyone
reports, while the cause is a pool of five connections.

## The numbers

| Metric | What it is | Where it comes from |
|---|---|---|
| `catalog.requests` | How many requests the catalog served, by endpoint and status | `CatalogMetricsInterceptor` |
| `catalog.query.duration` | How long the catalog's own queries took, by endpoint | `CatalogMetricsInterceptor` |
| `hikaricp.connections.active` | Connections the catalog's pool is using right now | Hikari, wired to the same registry |
| `hikaricp.connections.pending` | Requests waiting for a connection | Hikari |
| `hikaricp.connections.max` | The pool's size — five | the configuration |
| `hikaricp.connections.acquire` | How long a request waited for a connection | Hikari |
| `hikaricp.connections.usage` | How long a connection was held | Hikari |
| `http.server.requests` | Every request the application served, including the admin API | Spring Boot |

Two pools appear under `hikaricp.*`: the catalog's and the writer's. The writer's pool is
the one that must not starve — the crawler loses data when it does. The catalog's pool is
the one that may be squeezed, because a slow page is a smaller problem than a lost crawl.

## Where the numbers come from

The application exposes the actuator's `metrics` and `health` endpoints:

```
curl -s localhost:8080/actuator/metrics/hikaricp.connections.active
curl -s localhost:8080/actuator/metrics/catalog.requests
```

The metrics endpoint answers JSON, and a scraper that wants Prometheus format needs the
Prometheus registry, which this application does not carry: it would be a dependency that
no line of code uses, and the dependency gate is right about that. Adding it is a
decision for whoever owns the monitoring stack — a runbook that describes metrics nobody
can scrape is a runbook for one person.

## The thresholds

They are not guesses: the first ones come from `scripts/load-catalog.sh`, which measures
the catalog against a filled table, and the rest from what the storefront does with the
answer.

### What the measurement said

Measured against 200 000 observations, a pool of five connections, a page size of 24, and
pages spread over the whole catalog (so the offsets are deep, as they are in a real
session):

| Requests at a time | Throughput | p50 | p95 | p99 |
|---|---|---|---|---|
| 1 | 1.7 req/s | 557 ms | 670 ms | 748 ms |
| 4 | 6.6 req/s | 562 ms | 751 ms | 882 ms |
| 8 | 6.5 req/s | 1 198 ms | 1 591 ms | 1 674 ms |
| 16 | 8.3 req/s | 1 830 ms | 2 206 ms | 2 427 ms |
| 32 | 9.0 req/s | 3 461 ms | 3 785 ms | 3 908 ms |

These numbers are worse than the ones the list of *observations* produced (136 ms at one
request, 21 req/s at eight) — and the difference is the read model. The list is now built
from the current observation of each product, and knowing the current price of every
product is a pass over the observations. The covering index of V7 makes that pass an
index-only scan; it does not remove it. The decision the numbers belong to — a
materialized read model, refreshed instead of computed per request — is recorded in
`docs/decisions/2026-10-20-the-catalog-lists-products-not-observations.md`, together with
what it would cost (staleness, and a refresh that has to happen in every profile that can
serve the catalog).

Two things fall out of it:

- **The throughput stops at about 21 requests a second** from four requests at a time
  onwards. That is the pool: five connections, and each request holds one for two queries
  — the page and the count. Past the point where the pool is full, only the waiting time
  grows.
- **The storefront's timeout is five seconds.** At thirty-two requests at a time the p99
  is already under three seconds; somewhere above that it crosses the timeout, and the
  storefront shows an error page. A budget of more than about twenty requests a second
  needs a bigger pool — and connections taken from the writer's budget, which is the
  trade the plan's phase 0 has to make with the real database in front of it.

Inside a request, the time is the database's, and it is now the read model that takes it:
the model is built first, and the page and the count are read out of it. The count is
*not* cached — a number that can be wrong is not worth a request — and the deep offset is
still the thing that is expensive on top of it. Keyset pagination is the real answer to
the offset, and it changes the contract; until the contract changes, the cost stays.

### The thresholds themselves

| Signal | Threshold | What it means |
|---|---|---|
| `catalog.query.duration` p99 | above 1 s | The catalog is slower than a page render should be. The storefront's own timeout is 5 s, so this is the first number to look at before users notice. |
| `catalog.query.duration` p99 | above 8 s | The session's `statement_timeout` will start refusing queries. The catalog answers `500` and the storefront shows an error page. |
| `hikaricp.connections.pending` | above 0 for more than a minute | The pool is too small for the traffic. Either raise it, or find the query that is holding a connection. |
| `hikaricp.connections.acquire` p95 | above 100 ms | The same thing, seen from the waiting side. A request that waits a tenth of a second for a connection is a request that is already late. |
| `hikaricp.connections.usage` p95 | above 2 s | One query is slow enough to hold a connection for most of the timeout. |
| `catalog.requests` with status `429` | above 0 | The rate limit is firing. Either a runaway, or the limit is too low for the storefront. |
| `catalog.requests` with status `5xx` | above 0 | The catalog is broken. The storefront is showing error pages. |
| The readiness probe | `DOWN` | The catalog cannot be served. The storefront's `/api/health` reports it, and the deploy gate stops. |

## The request identifier

Every catalog request carries an identifier: the caller may send one in `X-Request-Id`,
and the crawler answers with the one it used. Every log line the request produces carries
it too.

That is what turns "the storefront showed an error at 12:04" into a single line:

```
grep '\[2f8c1d4e-7a91-4f0b-9c33-1d2e5f6a7b8c\]' crawler.log
```

The identifier is in the storefront's error message as well — the client appends
`(request <id>)` to the message it raises — so the identifier to search for is in the
report itself.

An identifier that cannot be trusted — one carrying a line break, or too long — is
replaced with a generated one rather than refused. A read should not fail over a
diagnostic header.

## The rate limit

The catalog refuses more than 600 requests a minute per key and answers `429` with a
`Retry-After`. The limit is a second line of defence behind the key: the key says who may
read, the limit says how much.

A `429` in the metrics is worth reading twice. A storefront that hits it constantly has a
limit that is too low — six hundred a minute is ten a second, which is more than a
storefront needs and less than a runaway needs. A `429` that appears once in a while,
from an address nobody recognises, is the limit doing its job.

## The pool's size

Five connections is the starting point, not a measured number. The budget of the
database as a whole — the writer's pool, the catalog's pool and everything else, at most
0.7 of `max_connections` — is worked out against the real production database, and the
load test confirms or corrects it:

```
scripts/load-catalog.sh --rows 200000 --concurrency 8 --requests 400
```

A run with pending connections and a p99 near the timeout is a pool that is too small. A
run with an idle pool is a pool larger than the work needs — and a pool that is too large
takes connections away from the writer, which is the component that loses data when the
database runs out.

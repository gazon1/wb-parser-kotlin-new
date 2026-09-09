---
title: "Replace installCrawler with Spring @Scheduled bean — wire scheduler lifecycle"
date: 2026-09-09
tags: [scheduler, spring, architecture]
---

## Context

`installCrawler()` in `installCrawler.kt` was a `TaskScheduler.installCrawler()` extension function that was **never called** anywhere in the codebase. The cron scheduler was defined but unreachable. Additionally, it used `runBlocking { }` inside the scheduled task — a blocking call inside what should be a suspend context.

The `crawler.worker.enabled` YAML flag existed but had no Kotlin code reading it — it was a no-op boolean.

## Decision

Replace `installCrawler` with two Spring-native components:

**`ScheduledCrawler`** — a `@Component` that:
- Runs on a cron schedule via `@Scheduled(cron = "\${crawler.schedule.cron:...}")`
- Is activated only when `crawler.worker.enabled = true` via `@ConditionalOnProperty`
- Delegates to `CrawlRunner` (injected by Spring)
- Has no `runBlocking` — uses `suspend fun run()` directly

**`CrawlerConfig`** — a `@Configuration` that:
- Exposes `CrawlRunner` as a `@Bean`, wiring all dependencies from Spring context
- Creates `KtorDownloader` as a `@Bean`
- Reads `Spider.concurrency`, `Spider.maxDepth`, `Spider.maxPagesPerCatalog` from `CrawlProperties`
- Reads `Clock` from a `@Bean` method

**`CrawlProperties.Worker`** — new data class:
```kotlin
data class Worker(val enabled: Boolean = false)
```
Binds to `crawler.worker.enabled` in YAML, enabling `@ConditionalOnProperty`.

**`application.yml`** — replaces `crawler.schedule.interval-ms` (ignored) with `crawler.schedule.cron` (used by `@Scheduled`).

## Rationale

- **Scrapy-style**: Scrapy uses a `Spider` class with a `start_requests()` method called by the scheduler. `ScheduledCrawler` follows the same pattern — a dedicated class that the scheduler invokes.
- **Spring idiom**: `@Scheduled` + `@ConditionalOnProperty` is the standard way to attach background work to Spring's lifecycle.
- **`runBlocking` removed**: The previous `runBlocking { runner.run() }` anti-pattern is gone — `ScheduledCrawler.run()` is `suspend` and called directly by Spring's scheduler.
- **Testable**: `CrawlRunner` is a plain constructor-injected class, easily replaced with a test double in `@SpringBootTest`.

## Consequences

- **Positive**: Scheduler now actually runs when `worker` profile is active and `crawler.worker.enabled = true`
- **Positive**: `crawler.schedule.cron` from YAML controls the schedule
- **Positive**: `Spider.concurrency` now reaches `CrawlRunner` via `CrawlerConfig`
- **Breaking**: `installCrawler` extension function is deleted — any external callers would break (there were none)
- **Note**: `crawler.lock.timeout-minutes` is still not read — future work if multi-instance deployment is needed (ShedLock)

## Alternatives Considered

- **Keep `TaskScheduler.scheduleAtFixedRate` + fix `runBlocking`**: Still uses imperative Spring API rather than annotation-based; `scheduleAtFixedRate` is appropriate for dynamic registration but adds complexity
- **ShedLock**: Commented out in the original `installCrawler`; would need `shedlock-spring` dependency and `@SchedulerLock` annotation — deferred until real multi-instance deployment materialises

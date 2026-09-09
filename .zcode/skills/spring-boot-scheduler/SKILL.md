# Spring Boot Scheduler

## When to use this skill

Use when:
- Adding a background job that runs on a cron schedule in a Spring Boot application
- Replacing a `TaskScheduler.scheduleAtFixedRate { runBlocking { ... } }` pattern
- Wiring a `@ConfigurationProperties` value into a scheduled component
- Enabling/disabling a scheduler via Spring profile or property flag

## `@Scheduled` pattern

```kotlin
@Component
@ConditionalOnProperty(name = "crawler.worker.enabled", havingValue = "true")
class ScheduledCrawler(
    private val crawler: CrawlRunner,  // injected by Spring
) {
    @Scheduled(cron = "\${crawler.schedule.cron:0 0 */4 * * *}")
    suspend fun run() {
        when (val result = crawler.run()) {
            is CrawlRunner.RunResult.Success -> { ... }
            is CrawlRunner.RunResult.AlreadyRunning -> { ... }
            is CrawlRunner.RunResult.Failure -> { ... }
        }
    }
}
```

**Key points:**
- `cron` uses Spring placeholder syntax: `"\${property:default}"`
- `suspend fun run()` — no `runBlocking` needed; Spring calls it directly
- `@ConditionalOnProperty` — the Spring way to gate by YAML flag

## `@Scheduled` vs `TaskScheduler.scheduleAtFixedRate`

| Scenario | Use |
|---|---|
| Fixed-rate job, known at startup | `@Scheduled(fixedRate = 3600_000)` |
| Cron expression, configurable | `@Scheduled(cron = "\${schedule.cron}")` |
| Dynamic rate decided at runtime | `TaskScheduler.scheduleAtFixedRate` |
| Per-target dynamic scheduling | `TaskScheduler` directly |

**Rule**: Use `@Scheduled` for anything configurable via cron expression. Use `TaskScheduler` only when the schedule must be determined at runtime per entity.

## `runBlocking` anti-pattern

```kotlin
// BAD — blocking inside what should be a suspend function
val task = Runnable {
    runBlocking {
        crawler.run()  // runBlocking blocks a thread for the duration of a suspend function
    }
}
scheduler.scheduleAtFixedRate(task, Duration.ofHours(4))
```

**Why it's bad**: `runBlocking` blocks the thread from the scheduler's thread pool for the entire crawl duration. If the crawl takes 30 minutes, 30 minutes of scheduler thread are blocked. If the pool has only a few threads, this causes thread starvation.

**Fix**: Make the scheduled method `suspend` and let Spring's scheduler call it directly:

```kotlin
// GOOD — suspend function, no blocking
@Scheduled(cron = "0 0 */4 * * *")
suspend fun run() {
    crawler.run()
}
```

Spring's `TaskExecutor` for `@Scheduled` tasks handles `suspend` correctly.

## Distributed locking — ShedLock

For multi-instance deployments (multiple pods running the same app), use ShedLock to ensure only one instance runs the job at a time:

```kotlin
// In installCrawler (old pattern, now replaced by @Scheduled bean)
// @SchedulerLock is still the right annotation when multi-instance is needed
@Scheduled(cron = "\${crawler.schedule.cron}")
@SchedulerLock(name = "crawl-job", lockAtLeastFor = "10m", lockAtMostFor = "30m")
suspend fun run() { ... }
```

**Requirements:**
1. Add `shedlock-spring` dependency
2. Add `@EnableSchedulerLock` to `@Configuration`
3. Configure a `LockProvider` (JDBC, Redis, etc.)

**Current status**: ShedLock is not wired in the current codebase. `crawler.lock.timeout-minutes` in YAML is declared but not read. Future work if multi-instance deployment materialises.

## Wiring config to scheduled component

```
application.yml
    │
    ▼
@ConfigurationProperties (CrawlProperties)
    │
    ▼
@Configuration @Bean CrawlRunner(...) ← uses CrawlProperties values
    │
    ▼
@Component ScheduledCrawler(crawler)  ← injected
```

The config chain should use constructor injection at every step — no `@Value` inside the scheduled component, no global singletons.

## Testing scheduled components

```kotlin
@SpringBootTest
@ConditionalOnProperty(name = "crawler.worker.enabled", havingValue = "true")
class ScheduledCrawlerTest {
    @MockBean lateinit var crawler: CrawlRunner
    @Autowired lateinit var scheduler: TaskScheduler

    @Test
    fun callsCrawlRunnerOnSchedule() {
        whenever(crawler.run()).thenReturn(CrawlRunner.RunResult.Success(10, 5))
        // Trigger the scheduled task manually or via Clock mocking
    }
}
```

## Related Skills

- `config-to-runtime-plumbing` — general pattern for YAML → constructor wiring
- `kotlin-test-boundary` — `app/` module uses `@SpringBootTest` for scheduler integration tests

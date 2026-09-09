# Config-to-Runtime Plumbing

## When to use this skill

Use when:
- A configuration value from `application.yml` needs to reach a runtime consumer
- Adding a new `application.yml` property that affects pipeline behaviour
- Tracing a "why isn't my config being read?" bug
- Wiring a Spring `@ConfigurationProperties` bean into a non-Spring class (e.g. `CrawlRunner`)

## Standard chain

```
application.yml
    │
    ▼
@ConfigurationProperties data class  (CrawlProperties, Schedule, Spider, ...)
    │
    ▼
@Bean constructor argument or @Configuration class field
    │
    ▼
Consumer class constructor  (CrawlRunner, PipelineRunner, installCrawler, ...)
    │
    ▼
Runtime call site  (Pipeline.run(concurrency = x), ...)
```

**Every link in the chain must be explicit.** If any step is missing, the config is dead code.

## Anti-patterns

### Anti-pattern: `Spider.concurrency` declared but never read

From PR 17 (before fix):

```
CrawlProperties.Spider.concurrency  ← declared (default=3)
    ↓  [never read]
CrawlRunner  ← no concurrency parameter
    ↓  [never read]
PipelineRunner.run()  ← no concurrency parameter
    ↓  [always default=1]
Pipeline.run(concurrency = 1)
```

Symptoms: changing `crawler.spider.concurrency` in YAML has zero effect.

### Anti-pattern: `@Value` with magic strings

```kotlin
// BAD — magic string, no type safety, no default co-located with property
@Value("\${crawler.spider.concurrency:3}")
private val concurrency: Int = 3
```

```kotlin
// GOOD — @ConfigurationProperties gives type-safe binding and co-located defaults
@ConfigurationProperties(prefix = "crawler.spider")
data class Spider(
    val concurrency: Int = 3,
)
```

### Anti-pattern: hardcoded constant instead of config

```kotlin
// BAD — magic number hidden in implementation
class CrawlRunner(...) {
    private val maxDepth: Int = 2  // from where??
}
```

```kotlin
// GOOD — explicit parameter with documented origin
class CrawlRunner(
    private val maxDepth: Int = 2,  // from CrawlProperties.Spider.maxDepth
)
```

### Anti-pattern: global singleton that bypasses constructor

```kotlin
// BAD — GlobalObject.state is invisible to callers and untestable
object GlobalConfig {
    var concurrency: Int = 1
}
class PipelineRunner(pipeline: Pipeline) {
    fun run(tasks: List<Crawling>) = pipeline.run(concurrency = GlobalConfig.concurrency)
}
```

```kotlin
// GOOD — constructor injection makes dependency explicit and testable
class PipelineRunner(
    private val pipeline: Pipeline,
    private val concurrency: Int = 1,
) {
    fun run(tasks: List<Crawling>) = pipeline.run(tasks, concurrency = concurrency)
}
```

## Decision tree: `@ConfigurationProperties` vs `@Value`

| Scenario | Use |
|---|---|
| Type-safe property with multiple fields, co-located defaults | `@ConfigurationProperties` data class |
| Single scalar value, no future extension | `@Value` is acceptable |
| Property used outside Spring context (e.g. pure domain class) | `@ConfigurationProperties` + explicit constructor param |
| Property needs validation / conversion | `@ConfigurationProperties` with `@Validated` |

## Testing the chain

```kotlin
// Unit test: inject a test value directly into the constructor
class PipelineRunnerTest : FunSpec({
    test("respects custom concurrency") {
        val pipeline = Pipeline(...)
        val runner = PipelineRunner(pipeline, concurrency = 4)
        // runner.run(...) should pass concurrency=4 to pipeline
    }
})

// Integration test: override property via @TestPropertySource
@SpringBootTest
@.TestPropertySource(properties = ["crawler.spider.concurrency=8"])
class CrawlRunnerIntegrationTest {
    @Autowired lateinit var runner: CrawlRunner

    @Test
    fun usesConfiguredConcurrency() {
        // verify concurrency=8 reaches pipeline
    }
}
```

## Related Skills

- `pipelines` — the `Spider.concurrency` gap before PR 17 is a case study
- `spring-boot-scheduler` — `@Scheduled` beans also need config wiring via `@ConfigurationProperties`

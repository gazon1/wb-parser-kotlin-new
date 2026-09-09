---
title: "Wire Spider.concurrency into Pipeline.run() — close 4-layer config gap"
date: 2026-09-09
tags: [pipeline, configuration, architecture]
---

## Context

`CrawlProperties.Spider.concurrency` was declared with a sensible default of `3`, but the value never flowed through to `Pipeline.run()`. The pipeline defaulted to `concurrency = 1` (sequential) in every execution path, making the configuration effectively dead code.

The wiring gap spanned 4 layers:

```
CrawlProperties.Spider.concurrency  (declared, default=3)
    ↓  [never read]
CrawlRunner                          (no concurrency param before PR 17)
    ↓  [never read]
PipelineRunner.run()                 (no concurrency param before PR 17)
    ↓  [always used default=1]
Pipeline.run(concurrency = 1)       (param existed but unreachable)
```

## Decision

Add a pass-through `concurrency: Int = 1` parameter through the entire call chain:

- `PipelineRunner.run(tasks, concurrency = 1)` — accepts and forwards
- `CrawlRunner` constructor gains `concurrency: Int = 1` between `clock` and `onCrawlStart`
- `installCrawler()` gains `concurrency: Int = 1` parameter
- `Pipeline.run(concurrency: Int = 1)` was already present — simply now reachable

The default remains `1` for backward compatibility with existing callers that don't specify it.

## Rationale

- **Zero behaviour change for existing code**: default `1` matches the previous hardcoded value
- **Non-breaking**: all existing call sites (`pipeline.run(listOf(task))`) continue to work
- **Minimal diff**: pure pass-through, no new abstractions
- **Testable**: `PipelineConcurrencyTest` verifies both `concurrency = 1` and `concurrency = 3` paths

## Consequences

- `Spider.concurrency` from `application.yml` now actually controls download concurrency
- Default remains sequential (`1`) — users must explicitly set `crawler.spider.concurrency` in YAML to get parallelism
- Future work: surface `Spider.concurrency` in a Spring `@Bean` configuration (covered in PR 18)

## Alternatives Considered

- **Use a global singleton**: rejected — constructor injection is the project standard
- **Use `@Value("${crawler.spider.concurrency}")` directly in `PipelineRunner`**: rejected — bypasses `CrawlProperties` structure and makes testing harder
- **Default to `3` in `PipelineRunner`**: rejected — would change behaviour for existing code that calls `run()` without the param

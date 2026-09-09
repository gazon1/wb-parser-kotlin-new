# Arrow Either Anti-patterns

## When to use this skill

Use when:
- Fixing `Either.map { side-effect }` — using `map` for side effects instead of value transformation
- Choosing between `fold`, `map`, `onRight`, `tap` in Arrow code
- Encountering `mapNotNull` on `Either` — usually wrong
- Seeing `Either.Right(value)` constructed directly instead of `right()` or `either { }`

## Arrow Either pattern reference

### `map` — transform the **Right value**

```kotlin
// CORRECT: transform Right value
val result: Either<Error, Int> = either { validate(x) }
    .map { it * 2 }  // Either<Error, Int> → Either<Error, Int>
```

### `onRight` — perform side effect, preserve Either

```kotlin
// CORRECT: side effect on Right, return Either unchanged
pipeline.run(tasks)
    .onRight { (_, sides) -> interpreterRegistry.interpretAll(sides) }
    // Returns Either.Left or Either.Right as-is
```

### `fold` — when you need to handle **both** cases explicitly

```kotlin
// CORRECT: handle both Left and Right
result.fold(
    ifLeft = { error -> log.error("Failed: $error") },
    ifRight = { crawled -> log.info("Crawled ${crawled.pages} pages") },
)
```

## Anti-patterns

### Anti-pattern: `Either.map { side-effect }` (when no type change needed)

```kotlin
// BAD — map is for transformation, not side effects (when no type change needed)
return pipeline.run(tasks).map { (_, sides) ->
    interpreterRegistry.interpretAll(sides)  // side effect!
    // No type change — the map returns Unit instead of the Either's Right value!
}
```

**Problem**: If the lambda's last expression is the side effect (returning `Unit`), the `map` discards the original Right value and returns `Unit`. This is almost always a bug.

**Fix (Arrow 1.1+)**: Use `onRight` for pure side effects without type change:

```kotlin
// GOOD — onRight performs the side effect, returns Either unchanged
return pipeline.run(tasks)
    .onRight { (_, sides) -> interpreterRegistry.interpretAll(sides) }
```

### When `map { side-effect; value }` is NOT an anti-pattern

If you need BOTH a side effect AND to transform the Right value into a different type, `map { side-effect; transformedValue }` is correct:

```kotlin
// CORRECT — map does both: side effect AND type change (Pair → Crawled)
return pipeline.run(tasks).map { (crawled, sides) ->
    interpreterRegistry.interpretAll(sides)  // side effect
    crawled  // type change: Pair<Crawled, List<Side>> → Crawled
}
```

Arrow's `onRight` cannot do both — it only performs a side effect without type change. Use `onRight` when you only need the side effect; use `map` when you also need to transform the value.

**Rule of thumb**: If the lambda's last expression is `Unit` (the side effect result), use `onRight`. If the last expression is a transformed value, use `map`.

### Anti-pattern: `Either.Left` / `Either.Right` constructor in non-test code

```kotlin
// BAD — constructor used directly
return Either.Left(DomainError.Unexpected("message"))
return Either.Right(value)
```

```kotlin
// GOOD — use arrow.core extensions
import arrow.core.left
import arrow.core.right

return DomainError.Unexpected("message").left()
return value.right()
```

For `either { }` builder (preferred for validation chains):
```kotlin
// GOOD — either { } + raise() + shift() for Left branches
return either {
    val validated = validate(input).bind()
    transform(validated).bind()
}
// Returns Either<Error, Transformed>
```

### Anti-pattern: `mapNotNull` on Either

```kotlin
// BAD — mapNotNull on Either loses type safety
val result: Either<Error, Item?> = either { ... }
    .mapNotNull { it.toItem() }  // What if it's Left? mapNotNull discards it!
```

### Anti-pattern: Catching exceptions in `map`

```kotlin
// BAD — exception swallowed silently
return download(url).map { body ->
    parse(body)  // if parse() throws, Either.map catches it as a success!
}
```

```kotlin
// GOOD — use either { } to capture exceptions explicitly
return either {
    val body = download(url).bind()
    parse(body).bind()
}
// Exceptions inside bind() are lifted to Either.Left
```

## Decision tree: `fold` vs `map` vs `onRight`

| Scenario | Use |
|---|---|
| Transform Right value, Left passes through | `.map { ... }` |
| Side effect on Right, return Either unchanged | `.onRight { ... }` (Arrow 1.1+) |
| Side effect on Left, return Either unchanged | `.onLeft { ... }` (Arrow 1.1+) |
| Different return type based on Left/Right | `.fold({ ... }, { ... })` |
| Both: transform Right AND handle Left | `.fold({ left -> ... }, { right -> ... })` |
| Pipeline of validations, short-circuit on first failure | `either { }` builder |

## Sources

- Arrow docs — `either` computation expression: https://arrow-kt.io/docs/apidocs/arrow-core/arrow.core/-either/
- Arrow docs — `onRight` / `onLeft`: https://arrow-kt.io/docs/apidocs/arrow-core/arrow.core/on-right/

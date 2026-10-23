---
title: "The schema metadata left the domain, and the purity gate lost its exemption"
date: 2026-10-23
tags: [architecture, database, domain, gates]
---

## Context

`ExposedTables.kt` — the Exposed table definitions, the mapping of the database
schema — lived in `wbparser/src/main/kotlin/ru/wbparser/domain/pipeline/`. The
purity gate had to exempt it by name:

```
# ExposedTables.kt is exempt: it holds compileOnly schema metadata.
```

The exemption was the visible symptom. The domain is supposed to be a set of Kotlin
types that know nothing about the database, and the gate says so in its very first
line. But one file inside it imported `org.jetbrains.exposed`, and the rule that
protects the domain had to be carved out for it.

The file's own comment said why it was there: "These live in `domain/` so the DSL
is close to the domain model. Exposed table objects are pure metadata — no runtime
framework coupling — which is why they are safe to include as `compileOnly`
dependencies." The reasoning was sound and the conclusion was wrong: the tables are
not the domain model's DSL, they are the *database's* schema, and the only code
that used them was the repository layer in `infra/db/repositories/`.

The file was also the only thing that made the exemption necessary: the rest of the
domain imported no infrastructure at all.

## Alternatives considered

1. **Leave it and keep the exemption.** Rejected: an exemption is a hole in a rule,
   and a rule with a hole in it teaches the reader that the rule has holes.
2. **Introduce a port, so the domain names its own types instead of Exposed's.**
   Rejected: the domain never named the tables. The tables were *defined* in the
   domain and *used* in the infrastructure; the port would have been machinery for
   a problem nobody had.
3. **Move the metadata next to its only users (chosen).**

## Decision

The schema metadata lives in `wbparser/src/main/kotlin/ru/wbparser/infra/db/`, next
to the queries that use it. The domain does not mention Exposed at all, and the
purity gate has no exemption left — it now forbids `org.jetbrains.exposed` in the
domain along with the frameworks it already forbade.

The domain model is unaffected: `SavedItem`, `Crawling` and `Target` are Kotlin
types, and they were always the ones the domain meant.

## Rationale

The move was possible because the dependency ran the other way: the domain defined
the tables, and the infrastructure used them. Nobody in the domain used the tables.
A definition with no users in its own package is a definition that is in the wrong
package, and the purity gate was right to be annoyed.

The gate got stronger, not weaker. It used to say "the domain must not import
infrastructure, except for the one file that does". It now says what it always
meant, and the exemption is gone because there is nothing to exempt.

## Consequences

- **Never** exempt a file from the purity gate by name. An exemption is a hole in a
  rule; the answer to "this file needs it" is to move the file, not to widen the
  rule.
- **Always** put the schema metadata next to the code that queries it.
- **Always** keep the domain free of `org.jetbrains.exposed` — the gate now checks
  it.

---
name: domain-glossary
description: Decide whether a term belongs in docs/CONTEXT.md and add it correctly.
---

# Domain Glossary

## When to use

A term appears in two or more features with a specific meaning that differs from common English. Before adding a new domain term or alias, use this skill to decide if it belongs in `docs/CONTEXT.md`.

## Decision tree

```
TERM TO CONSIDER
    │
    ├─── Is it used in only ONE feature?
    │         YES → ❌ Do NOT add to CONTEXT.md. Local to that feature.
    │         NO  → ✅ Continue
    │
    ├─── Does it mean the same as common English?
    │         YES → ❌ Do NOT add to CONTEXT.md. No special meaning.
    │         NO  → ✅ Continue
    │
    ├─── Is it referenced in an ADR?
    │         YES → ✅ Add to CONTEXT.md with "Used in: ADR-name"
    │         NO  → Continue
    │
    └─── Could a reader confuse it with a synonym or alias?
             YES → ✅ Add to CONTEXT.md with "Also known as: alias1, alias2"
             NO  → ⚠️  Consider adding anyway if term is architecturally significant
```

## Definition format

```markdown
## Term Name

**Also known as:** alias1, alias2    # omit if no aliases

Brief definition (1-3 sentences). Explain what it is, not how it's implemented.

**Used in:** `FeatureName` (`path/to/file.kt`), `AnotherFeature`
```

## How to add a term

1. Edit `docs/CONTEXT.md`
2. Find the right alphabetical position (terms are ordered A-Z by canonical name)
3. Add the definition using the format above
4. If the term has an alias, add "Also known as" with the old name

## Examples

### Example 1: New domain term (no alias)

**Term:** `Profile`
**Decision:** YES — used in multi-profile isolation across features
**Definition:**
```markdown
## Profile

**Profile** is the multi-profile isolation boundary. Each profile has its own
Room database, DataStore, and SecureStorage.

**Used in:** `core/di`, `ProfileRepository`, `ProfileAwareCurrentUser`
```

### Example 2: Term with alias

**Term:** `TagGroup`
**Alias to deprecate:** `TagCategory`
**Decision:** YES — TagCategory is informal, TagGroup is the entity name
**Definition:**
```markdown
## TagGroup

**Also known as:** TagCategory (informal, avoid)

**TagGroup** is the grouping entity for tags. Tags belong to a group.

**Used in:** `feature/tags/`, `TagGroupRepository`, `TagGroupsViewModel`
```

## Common pitfalls

1. **Adding implementation details** — CONTEXT.md defines *what* a term means, not *how* it works. Keep definitions at the domain level.
2. **Aliases without canonical names** — always pick one name as canonical, mark others as "Also known as".
3. **Cross-feature confusion** — if two features use the same term differently, that IS a domain term: add it with both usages documented.
4. **Over-defining** — not every class name needs to be here. If it only appears in one feature's internal implementation, it does not belong in CONTEXT.md.

---
name: progress-journal
description: Update PROGRESS.md during and after an epic — retro entries, PR status, blockers.
---

# Progress Journal

## When to use

During an epic with multiple PRs, update `PROGRESS.md` after each PR merge. Update retro findings immediately after the retro meeting.

## Prerequisites

- `PROGRESS.md` exists at repo root
- Epic is tracked in PROGRESS.md (in-progress status)

## Step-by-step

### Step 1 — PR opened

Add the PR row with status `🔄 in review`:

```markdown
| PR-N | Description | Status | Merged |
|---|---|---|---|
| PR-N | Feature description | 🔄 in review | — |
```

### Step 2 — PR merged

Update the status and date:

```markdown
| PR-N | Description | Status | Merged |
|---|---|---|---|
| PR-N | Feature description | ✅ merged | 2026-09-26 |
```

### Step 3 — Post-PR retro

After each PR merge, add a retro section:

```markdown
#### PR-N retro (YYYY-MM-DD)

**What was done:**
- Brief summary

**What went well:**
- Bullet 1
- Bullet 2

**What didn't go well:**
- Bullet 1

**Critical fixes:**
- Any bugs/blockers fixed immediately

**ADR-worthy findings:**
- Item 1 → filed as `docs/decisions/YYYY-MM-DD-finding-slug.md`
```

### Step 4 — Epic completed

Change status to `completed`:

```markdown
**Status:** completed
```

Add final retro section.

## Retro section content

Each retro answers:
1. **What was done** — brief summary
2. **What went well** — bullets
3. **What didn't go well** — bullets
4. **Critical fixes** — any bugs/blockers fixed immediately after the PR
5. **ADR-worthy findings** — items logged to ADR instead of fixed

## Examples

### Example: PR-1.1 retro entry

```markdown
#### PR-1.1 retro (2026-09-26)

**What was done:**
- 5 docs-infrastructure ADRs created

**What went well:**
- Clear separation of lifecycle stages for ADR/skill/prose

**What didn't go well:**
- ADR files created in wrong directory (main checkout vs worktree)
- Worktree had pre-existing build errors not on main branch

**Critical fixes:**
- Restored missing UserMessage.kt
- Fixed TagGroupsUiState.Empty("") → Empty

**ADR-worthy findings:**
- DIGEST oversized (1681 lines) → filed as post-PR-1.1 retro
```

## Common pitfalls

1. **Editing retro after merge** — retro entries are append-only after PR merge. Do not edit an already-merged retro.
2. **Missing bidirectional links** — retro ADRs reference PROGRESS.md entry; PROGRESS.md entry references the retro ADR.
3. **Forgetting blockers** — always note blockers and deferred items with the ADR that records them.
4. **Non-atomic retro** — each PR gets its own retro section, even if the PR is small. This creates a complete audit trail.

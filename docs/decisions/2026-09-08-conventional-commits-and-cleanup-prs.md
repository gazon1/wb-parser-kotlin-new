---
title: "Conventional Commits + PR-numbered cleanup series"
date: 2026-09-08
tags: [workflow, conventions]
---

## Context

The project started with two large commits (`e1259e8`, `21a511e`) that were hard to review atomically. Subsequent commits introduced a "PR-series" convention: each subject line contains a `PR N` marker (e.g. `chore: PR 2 dead code purge — ...`). The commit prefix follows Conventional Commits (`feat:`, `fix:`, `refactor:`, `chore:`, `test:`, `docs:`).

## Decision

Formalise the commit convention:

### Format

```
<type>: [PR <N>] <short summary>
```

### Allowed `<type>` values

| Type | When to use |
|---|---|
| `feat:` | New functionality (new Stage, new Interpreter, new HTTP endpoint) |
| `fix:` | Bug fix (backoff bug, NPE, silent error swallowing) |
| `refactor:` | Internal change with no behaviour change |
| `chore:` | Build, deps, CI, infrastructure (no production code change) |
| `test:` | Adding or fixing tests only |
| `docs:` | Documentation only |

### PR number

`PR <N>` in the subject marks a logical cleanup/feature series. Multiple commits with the same `PR <N>` belong to the same logical unit. `N` is a simple integer starting from 1, sequential — no need for GitHub PR numbers (there is no remote).

Examples from current history:
- `chore: PR 2 dead code purge — remove ~700 lines of unused code`
- `refactor: PR 3 pure-domain cleanup — idGen and nullable delayMs`
- `fix: A3 Retry backoff consistency, A4 save error logging, ...` (cluster prefix `A*` comes from the tech-debt plan; `PR N` is preferred)

### Cluster notation

Items from the prioritised tech-debt plan (`.zcode/plans/plan-sess_*.md`) use `A*`, `B*`, `C*`, `F*` cluster prefixes in commit subjects. These are separate from `PR N` series and identify the plan item that drove the change.

## Rationale

- Conventional Commits enables automated changelog generation (e.g. `git-cliff`, `release-please`) if needed in the future.
- `PR N` provides a lightweight grouping without GitHub PRs — useful for tracking a cleanup series through multiple commits.
- The combination makes commit history a readable narrative of the project's evolution.

## Consequences

- **Always** prefix commits with a type: `feat:`, `fix:`, `refactor:`, `chore:`, `test:`, `docs:`.
- **Always** include `PR <N>` in subject for non-trivial changes that belong to a logical series.
- **Never** use `fix:` for a pure refactor — `fix:` implies a bug was fixed; use `refactor:` for restructuring without behaviour change.
- Cluster prefixes (`A1`, `A2`, …) from the tech-debt plan may appear in subject after the `PR N` marker.
- `git log --oneline` is the project changelog — keep subjects informative.

## Links

- `.zcode/plans/plan-sess_871f29cf-f859-4a72-9002-5bbafd737630.md` — tech-debt plan with A/B/C/F clusters

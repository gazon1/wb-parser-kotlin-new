---
title: "The operator CLI's catalog models are generated from the contract, the admin models are not"
date: 2026-11-17
tags: [contract, python]
---

## Context

`scripts/parser/wbparser.py` validated catalog responses against Pydantic models
written by hand next to the admin ones. The catalog contract is
`contract/catalog-v1.openapi.yaml`, and the Kotlin DTOs and the storefront's
TypeScript are already compared with it by tests. The Python copy had no such
check: a renamed field would have surfaced as a validation error at run time, on
the machine of whoever ran the command, and nothing said which side had changed.

## Idea

- Keep hand-writing the catalog models. It is the state the file was in, and the
  cost is paid every time the contract moves.
- Generate the whole client from a server-published spec (springdoc, a committed
  `docs/openapi.json`). The repository does not publish one, and the admin
  endpoints are not part of the catalog contract, so half the client would still
  be hand-written.
- Generate only the contract's `schemas`, and keep the admin/health shapes
  hand-written.

## Decision

The catalog models are generated into `scripts/parser/catalog_models.py` by
`just parser::models`, which pins `datamodel-code-generator==0.97.0`. The
generated file is committed, and its header names the input and the generator.

The generation is narrow on purpose:

- `--openapi-scopes schemas` — the catalog's shapes are the components. The
  `/health`, `/ready` and `/contract` bodies are operations and stay outside the
  generated models, as do the admin endpoints that are not in this document.
- `--snake-case-field` keeps the CLI's field names (`total_count`,
  `cashback_rubles`) while the validation alias stays the contract's camelCase.
- Cashback is `Decimal`, not the generator's default `float`: the server sends
  `BigDecimal`, and a percentage or ruble value must not be rounded by the client
  that only displays it. `--type-overrides` names the type per field, because the
  contract's `number` carries no `multipleOf` to hang it on.

The CLI imports the generated `PagedProductCard`, `ProductCard` and `SiteStats`
under its existing names; the admin models in `wbparser.py` are unchanged.

Two things follow from the models being stricter than the hand-written ones, and
both are made explicit rather than left to a runtime surprise:

- The catalog's JSON is decoded with `parse_float=Decimal`. The stdlib decoder
  turns every fraction into a `float` first, and `Decimal(float)` is already
  rounded — a `Decimal` field downstream cannot undo that. The admin and
  operational responses keep the ordinary decoder, because their contract has no
  decimals.
- `--json` writes money as unquoted JSON numbers through `simplejson`, because
  Pydantic's JSON mode and the stdlib encoder both have to stringify a `Decimal`.
  The output keeps the shape the API itself sends, so a `jq` filter that reads
  `cashbackRubles` as a number keeps working.

## Consequences of the drift risk

A committed generated file and a committed contract can disagree, and no test
that loads the file can see it. Two gates cover it, because they fail for
different reasons:

- The pipeline re-runs `scripts/parser/generate-models.sh` and fails on
  `git diff --exit-code`. That is the literal check: whatever the flags, the
  generator version or a hand-edit did to the file, regenerating it must produce
  no diff. It needs PyPI, so — like the action-pin check — it runs in the
  pipeline, and `scripts/ci/check-ci-workflows.py` fails unless one step of the
  `static` job runs the generator **and** diffs the generated file: the diff is
  the check, so keeping the generator and dropping the diff is the same defect
  as deleting the step, and both are findings.
- The recipe also appends the contract's SHA-256 as `# source-sha256: …`, and
  `scripts/ci/check-catalog-models.py` compares it in the static registry. That
  one needs no generator and no network, so it answers the same question the
  moment a contract edit lands, without waiting for a pipeline that can reach
  PyPI.

The generator command lives in the script, not in the recipe, so `just
parser::models` and the CI step cannot drift apart.

## Rationale

The contract is already the one document both sides are held to, so the Python
client should read it rather than paraphrase it. The schemas scope keeps the
generated file to what the contract actually governs — generating the operational
bodies would type half a client whose other half still has no contract.

OpenAPI 3.1 constructs in this document survive the generator: `type: [T, "null"]`
becomes `T | None`, `$ref` resolves to the referenced model, `allOf` becomes
inheritance with a flat JSON shape, and `format: date-time` becomes an aware
datetime. There are no enums in the schemas; the sort order and health `status`
enums live in operations, outside the generated models.

## Consequences

- **Always** regenerate when the contract changes, with
  `scripts/parser/generate-models.sh` (`just parser::models`); the recipe and the
  pipeline both call that script, so the command exists once, and the file is
  committed and reviewed with the change that caused it.
- **Always** keep the generator version pinned in that script. A different version
  rewrites the file for reasons that are not a change to the contract.
- **Never** hand-edit `scripts/parser/catalog_models.py`; the next generation
  overwrites it, and the CI diff check would fail on an edit that kept the marker.
- **Never** widen the generation to `paths` without deciding what to do with the
  operational bodies it brings in.
- **Always** decode catalog responses with `parse_float=Decimal` and encode
  `--json` with a decimal-aware writer; a `float` on either side rounds the value
  the contract calls a number.
- **Never** let the recorded `# source-sha256` marker drift from the command that
  writes it; it is the only offline evidence that the models match the contract.

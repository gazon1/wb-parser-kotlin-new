package ru.wbparser.infra.json

import kotlinx.serialization.json.Json

/**
 * The decoder WB's answers are read with.
 *
 * One configuration, one place: the catalog parser and the HTTP client read the same
 * answer, and they must read it the same way — otherwise the question "what came off the
 * wire" has two answers.
 *
 * ## What is tolerated, and what is not
 *
 * **Unknown fields are tolerated** ([ignoreUnknownKeys]). WB adds fields to its answers
 * without asking, and a field this crawler does not know about is not a reason to fail a
 * page: the products are still in the answer.
 *
 * **Non-standard syntax is not tolerated.** There is no `isLenient` here, and that is a
 * decision, not an omission:
 *
 * - Lenient mode buys three things: unquoted keys, single-quoted strings, and quoted
 *   numbers. Quoted numbers are accepted by the strict decoder anyway — the strict
 *   decoder reads `{"id":"123"}` into a `Long` field without complaint, which was
 *   measured, not assumed. Single-quoted strings do not even work under leniency: the
 *   document parses, but the field is not found, because the key itself is quoted wrong.
 *   So leniency buys exactly one thing here — unquoted keys.
 * - WB's catalog endpoint answers with an ordinary JSON document written by a
 *   server-side serializer. Unquoted keys are not something such a serializer emits, so
 *   leniency protects against nothing that happens.
 * - What leniency does do is let an answer that is *not* JSON parse anyway. That erases
 *   the answer to the question the failure has to answer: which field stopped fitting.
 *
 * So an answer that does not fit becomes a failure that names the field — not a page read
 * from a document nobody wrote.
 */
internal val wbJson: Json =
    Json {
        ignoreUnknownKeys = true
    }

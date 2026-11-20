package ru.wbparser.infra.db

/**
 * The unit the writer writes the cashback in.
 *
 * `scraped_items` holds the cashback as a percentage: `cashback_percent` is the
 * percentage WB sends, and `cashback` is the money that follows it. A writer says so —
 * to the database, before it writes (`SET LOCAL crawler.cashback_unit = ...`), and in the
 * run it records, so that the database itself can say which writer produced a run.
 *
 * It is one string in one place because the declaration and the record have to agree: a
 * writer that declared one unit and recorded another would leave the cutover gate with
 * runs it cannot explain.
 *
 * @see ru.wbparser.infra.db.repositories.SavedItemQueries for the declaration.
 * @see ru.wbparser.infra.db.repositories.recordTargetRun for the record.
 */
internal const val CASHBACK_UNIT: String = "percent"

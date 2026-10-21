package ru.wbparser.app.api.catalog

import java.sql.PreparedStatement
import java.sql.Types

/**
 * How a value reaches a statement.
 *
 * ## Why this is a decision of its own
 *
 * The catalog's SQL holds no text from a request. Every value a request names — the
 * target, the search, the prices, the cashback, the page size, the offset — reaches the
 * database as a parameter, and the order of the parameters is the order of the
 * placeholders in the statement.
 *
 * Two things follow from that, and both of them are quiet when they are wrong: a value
 * that is concatenated into the SQL is an injection, and a value bound to the wrong
 * placeholder is a filter that asks a different question than the one the caller asked.
 *
 * ## Why a null is bound as a null and not as a string
 *
 * A `null` value is bound with `setNull`, so the database sees a null and not the four
 * characters "null". A filter that was meant to compare against nothing compares against
 * nothing.
 */
internal class CatalogStatementBinding(
    private val statement: PreparedStatement,
) {
    /**
     * Bind the filter values, and then the paging values.
     *
     * The values come first, in the order the filters built them, and the paging values
     * come last — the `LIMIT` and the `OFFSET` are always the last two placeholders of the
     * statement, and the values that reach them are always the last two that are bound.
     */
    fun bind(
        values: List<Any?>,
        vararg trailing: Int,
    ) {
        values.forEachIndexed { index, value ->
            if (value == null) {
                statement.setNull(index + 1, Types.NULL)
            } else {
                statement.setObject(index + 1, value)
            }
        }
        trailing.forEachIndexed { index, value ->
            statement.setInt(values.size + index + 1, value)
        }
    }
}

package ru.wbparser.app.api.catalog

import java.math.BigDecimal
import java.util.UUID

/**
 * The parameters of a request, read through typed accessors.
 *
 * ## Why this is a type and not a map
 *
 * The parameters used to arrive as a `Map<String, String?>`, and every field was read out
 * of it by a string key with a parser beside it: `parameters["minPrice"]` and a separate
 * `parsePriceRubles(raw, "minPrice")`. That arrangement has two defects that no compiler
 * can see.
 *
 * The first is a typo. A key that does not exist reads as `null`, and `null` means "the
 * caller did not ask for this" — so a misspelled parameter is silently ignored and the
 * filter it belongs to never applies. A filter that does not apply is a filter that looks
 * like it does.
 *
 * The second is that the *type* of a parameter lived in two places: the key said which
 * parameter it was, and the parser said what it was parsed into, and nothing tied the two
 * together. Reading a price as a decimal and reading a page as an integer were separate
 * decisions, made in different functions, that a change to one would not touch.
 *
 * ## What this type does instead
 *
 * Every read is typed and named, and the read is the parse: `decimal("minPrice")` is a
 * decimal or it is a `400`, and there is no separate step that could get the two out of
 * step. A misspelled name still reads as absent — but the names are now written once per
 * endpoint, in one place, next to the field they belong to.
 *
 * The raw map is still what the servlet hands over; it stops at the edge of the API, in
 * [of], and everything past that point reads parameters through this type.
 */
class QueryParameters private constructor(
    private val values: Map<String, String?>,
) {
    /**
     * A text parameter, or `null` when the caller did not send one.
     *
     * A blank value is treated as an absent one: `?q=` asks for the first product whose
     * title matches the empty string, which is every product — and a search box that
     * somebody cleared should not turn the catalogue into a full table scan.
     */
    fun text(name: String): String? = values[name]?.takeIf { it.isNotBlank() }

    /**
     * An integer parameter, or `null` when the caller did not send one.
     *
     * @throws CatalogBadRequestException when the value is not an integer.
     */
    fun integer(name: String): Int? =
        text(name)?.let {
            try {
                it.toInt()
            } catch (e: NumberFormatException) {
                throw CatalogBadRequestException("$name is not an integer")
            }
        }

    /**
     * A decimal parameter, or `null` when the caller did not send one.
     *
     * @throws CatalogBadRequestException when the value is not a number.
     */
    fun decimal(name: String): BigDecimal? =
        text(name)?.let {
            try {
                BigDecimal(it)
            } catch (e: NumberFormatException) {
                throw CatalogBadRequestException("$name is not a number")
            }
        }

    /**
     * A boolean parameter.
     *
     * An absent parameter means `false`, because the only boolean in the contract is a
     * filter that is either on or off.
     *
     * @throws CatalogBadRequestException when the value is neither `true` nor `false`.
     */
    fun flag(name: String): Boolean =
        when (val raw = text(name)) {
            null -> false
            else -> {
                if (raw.equals("true", ignoreCase = true)) {
                    true
                } else if (raw.equals("false", ignoreCase = true)) {
                    false
                } else {
                    throw CatalogBadRequestException("$name is not a boolean")
                }
            }
        }

    /**
     * A UUID parameter, or `null` when the caller did not send one.
     *
     * The check happens before the query, not in the database: PostgreSQL rejects a
     * non-UUID string in a `uuid` comparison with error `22P02`, which would turn a typo
     * in a URL into a server error instead of a `400`.
     *
     * @throws CatalogBadRequestException when the value is not a UUID.
     */
    fun uuid(name: String): UUID? =
        text(name)?.let {
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                throw CatalogBadRequestException("$name is not a UUID", e)
            }
        }

    companion object {
        /**
         * The parameters of a request, as the servlet handed them over.
         *
         * This is the one place the raw map is read. Past this point the parameters are
         * typed values, and nothing downstream knows what a query string is.
         */
        fun of(values: Map<String, String?>): QueryParameters = QueryParameters(values)
    }
}

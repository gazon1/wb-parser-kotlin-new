package ru.wbparser.app.api.catalog

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

//
// Turning a row into a DTO.
//
// ## Why the mapping lives in its own file
//
// The repository's SQL and the repository's mapping are two different kinds of code, and
// keeping them apart keeps both of them readable: the file that says which columns are
// read does not also say how a null becomes a null.
//
// ## Why a null is a value and not an absence
//
// A column that is null in the database is a `null` in the response — the property is
// present and its value is null. That is what the contract says, and it is what the
// storefront's types expect: `priceKopecks: number | null` is not the same as
// `priceKopecks?: number`, because only the first one can be told apart from "the API
// did not know".
//

/**
 * Read every row of a result set.
 */
internal fun <T> ResultSet.collect(mapper: (ResultSet) -> T): List<T> {
    val rows = mutableListOf<T>()
    while (next()) {
        rows += mapper(this)
    }
    return rows
}

/**
 * One row of the card projection.
 */
internal fun toCard(row: ResultSet): ProductCard {
    val id = row.getObject("id", UUID::class.java)
    val rawTitle = row.getString("title")?.trim().orEmpty()
    return ProductCard(
        id = id,
        targetId = row.getObject("target_id", UUID::class.java),
        targetName = row.getString("target_name") ?: "",
        // A row with no title still has to render as a link, and an empty anchor is
        // invisible. The id is the least-bad fallback and is always present.
        title = rawTitle.ifEmpty { "Товар ${id.toString().take(8)}" },
        priceKopecks = row.getLongOrNull("price_kopecks"),
        salePriceKopecks = row.getLongOrNull("sale_price_kopecks"),
        brand = row.getString("brand"),
        seller = row.getString("seller"),
        cashbackPercent = row.getBigDecimal("cashback_percent"),
        cashbackRubles = row.getBigDecimal("cashback"),
        inStock = row.getBooleanOrNull("in_stock"),
        imageUrl = row.getString("image_url"),
        productUrl = row.getString("product_url") ?: "",
        scrapedAt = row.getInstant("scraped_at") ?: Instant.EPOCH,
    )
}

/**
 * One row of the target list.
 */
internal fun toTargetSummary(row: ResultSet): TargetSummary =
    TargetSummary(
        id = row.getObject("id", UUID::class.java),
        name = row.getString("name") ?: "",
        rootCategoryName = row.getString("root_category_name"),
        isActive = row.getBoolean("is_active"),
        itemCount = row.getInt("item_count"),
        newestScrapedAt = row.getInstant("newest_scraped_at"),
    )

/**
 * One row of the price history.
 */
internal fun toPricePoint(row: ResultSet): PricePoint =
    PricePoint(
        scrapedAt = row.getInstant("scraped_at") ?: Instant.EPOCH,
        priceKopecks = row.getLongOrNull("price_kopecks"),
        cashbackPercent = row.getBigDecimal("cashback_percent"),
        cashbackRubles = row.getBigDecimal("cashback"),
    )

/**
 * A `BIGINT`, or `null` when the column is null.
 */
internal fun ResultSet.getLongOrNull(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

/**
 * A `BOOLEAN`, or `null` when the column is null.
 */
internal fun ResultSet.getBooleanOrNull(column: String): Boolean? {
    val value = getBoolean(column)
    return if (wasNull()) null else value
}

/**
 * A `TIMESTAMPTZ` as an instant.
 *
 * Read as an `OffsetDateTime` rather than as a `Timestamp`: a `timestamptz` is a point in
 * time, and the driver renders it in the JVM's default time zone when it is asked for a
 * `Timestamp`. The instant is the same either way, but asking for the offset keeps the
 * conversion in one place.
 */
internal fun ResultSet.getInstant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

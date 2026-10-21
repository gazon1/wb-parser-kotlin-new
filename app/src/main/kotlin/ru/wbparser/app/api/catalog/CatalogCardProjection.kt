package ru.wbparser.app.api.catalog

/**
 * The card's columns: how a row of a query becomes a card.
 *
 * ## Why the projection is by name
 *
 * Every source — the read model, the top-deals subquery — has to expose the card's
 * columns under the card's own names, and then the projection is the same for all of
 * them. A projection that spelled out `i.id` for one source and `best.id` for another
 * would be two places to forget a column, and a forgotten column is not a compile error,
 * it is a response that quietly has one field less.
 */
internal object CatalogCardProjection {
    /**
     * The card's columns, in the order the DTO declares them.
     *
     * One list, and every source exposes the card's columns under these names, so that the
     * projection is the same everywhere. Listing them twice would be a second place to forget
     * a column — and a forgotten column is not a compile error, it is a response that quietly
     * has one field less.
     */
    val CARD_FIELDS: List<String> =
        listOf(
            "id",
            "target_id",
            "target_name",
            "title",
            "price_kopecks",
            "sale_price_kopecks",
            "brand",
            "seller",
            "cashback_percent",
            "cashback",
            "in_stock",
            "image_url",
            "product_url",
            "scraped_at",
        )

    /**
     * The card projection for a query whose rows come from [source].
     */
    fun columns(source: String): String =
        CARD_FIELDS.joinToString(",\n         ") { field ->
            "$source.$field AS $field"
        }
}

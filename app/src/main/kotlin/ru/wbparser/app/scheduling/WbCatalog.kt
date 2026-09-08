package ru.wbparser.app.scheduling

/**
 * The Wildberries catalog spider — a plain data class.
 * URL builders are extension functions on WbCatalog.
 */
data class WbCatalog(
    val name: String = "wb-catalog-spider",
    val description: String = "Wildberries catalog crawler",
    val startUrlPattern: String = "https://www.wildberries.ru/catalog/{catalogId}/search.aspx",
    val productUrlPattern: String = "https://www.wildberries.ru/catalog/{productId}/detail.aspx",
    val maxDepth: Int = 2,
    val maxPagesPerCatalog: Int = 10,
    val useBrowserForAuth: Boolean = true,
    val concurrency: Int = 3,
) {
    fun shouldCrawlSubCategories(): Boolean = maxDepth > 1

    companion object {
        val DEFAULT = WbCatalog()
    }
}

/**
 * Builds the start URL for a given catalog ID.
 */
fun WbCatalog.startUrlFor(catalogId: Long): String =
    startUrlPattern.replace("{catalogId}", catalogId.toString())

/**
 * Builds the product detail URL for a given product ID.
 */
fun WbCatalog.productUrlFor(productId: Long): String =
    productUrlPattern.replace("{productId}", productId.toString())

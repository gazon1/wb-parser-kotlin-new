package ru.wbparser.app.api.catalog

/**
 * A page of a result set: which page, how many rows in it, and where it starts.
 *
 * ## Why this is a type of its own
 *
 * The two list endpoints — `/items` and `/top-deals` — take the same two paging parameters
 * and had the same clamping code written twice. Two copies of a clamp are two places to
 * change when the limit changes, and one of them would be forgotten.
 *
 * The type carries the clamping with it, so a `Paging` cannot exist in a state the API
 * would refuse to serve: the constructor is not public, and [of] is the only way to make
 * one.
 */
data class Paging(
    val page: Int,
    val pageSize: Int,
) {
    /**
     * The offset of the requested page, for the `OFFSET` clause.
     *
     * The multiplication cannot overflow: the offset is clamped to [MAX_OFFSET] before it
     * is computed, and the page size to [MAX_PAGE_SIZE].
     */
    val offset: Int get() = page * pageSize

    companion object {
        /**
         * The largest offset the API will serve, in rows.
         *
         * ## Why the offset and not the page index
         *
         * The database pays for the rows *before* the page: it walks them one by one. The
         * measurement says what that costs — at two hundred thousand observations, a page
         * whose offset is a hundred thousand rows takes about a hundred milliseconds, and
         * the cost grows with the offset rather than with the page.
         *
         * So the bound belongs on the offset. A page index on its own says nothing about
         * the work: with a page size of sixty, a million pages means sixty million rows to
         * walk, which the session's eight-second timeout would cut off — and a request the
         * database gives up on is a `500`, not an answer.
         *
         * A million rows is about a second of work at the measured rate, which leaves the
         * timeout room to spare and keeps the bound inside what the storefront can use.
         *
         * ## Why it is read from the contract
         *
         * The number lives in `contract/catalog-v1.openapi.yaml`, under
         * `x-catalog-paging.maxOffset`, because the storefront has to clamp to the same
         * one — and a number that lives in two places is a number that will differ in two
         * places. The contract is the one document both sides already read, and the
         * crawler's own test compares it with this constant.
         */
        val MAX_OFFSET: Int = CatalogContract.maxOffset()

        /**
         * The largest page the API will serve.
         *
         * Derived from [MAX_OFFSET] and the smallest possible page size, so that the
         * number is never larger than the work it allows.
         */
        val MAX_PAGE: Int = MAX_OFFSET

        /**
         * The largest page size the API will serve.
         *
         * A page size without an upper bound is a cheap way to make a server allocate until
         * it dies, and the storefront never asks for more than this.
         */
        const val MAX_PAGE_SIZE: Int = 60

        /** The page size used when a request does not ask for one. */
        const val DEFAULT_PAGE_SIZE: Int = 24

        /**
         * The paging of a request, with both values clamped into the range the API serves.
         *
         * A value outside the range is clamped rather than refused: a hostile page number is
         * a mistake in a URL, and the first page is a better answer than an error. The
         * contract says so, and the storefront clamps as well, so the two answers agree.
         *
         * The page is clamped against the *page size of this request*, because that is what
         * decides how many rows the offset means. The two clamps are one decision: a page
         * of sixty rows and a page of one row cannot be bounded by the same index.
         */
        fun of(
            page: Int?,
            pageSize: Int?,
        ): Paging {
            val size = clampPageSize(pageSize)
            return Paging(clampPage(page, size), size)
        }

        /**
         * The largest page index that keeps the offset within [MAX_OFFSET] rows.
         */
        fun maxPageFor(pageSize: Int): Int = (MAX_OFFSET / pageSize.coerceAtLeast(1)).coerceAtLeast(1)

        private fun clampPage(
            page: Int?,
            pageSize: Int,
        ): Int {
            val maxPage = maxPageFor(pageSize)
            return when {
                page == null -> 0
                page < 0 -> 0
                page > maxPage -> maxPage
                else -> page
            }
        }

        private fun clampPageSize(pageSize: Int?): Int =
            when {
                pageSize == null -> DEFAULT_PAGE_SIZE
                pageSize < 1 -> 1
                pageSize > MAX_PAGE_SIZE -> MAX_PAGE_SIZE
                else -> pageSize
            }
    }
}

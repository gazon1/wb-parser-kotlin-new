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
     * The multiplication cannot overflow: both factors are clamped to a range whose
     * product fits in an `Int` — the page index by [MAX_PAGE], the page size by
     * [MAX_PAGE_SIZE].
     */
    val offset: Int get() = page * pageSize

    companion object {
        /**
         * The largest page the API will serve.
         *
         * There is no upper bound in the contract, but a page index beyond `Int.MAX / 60`
         * would overflow the offset arithmetic, and an offset that large is a mistake rather
         * than a request.
         */
        const val MAX_PAGE: Int = 1_000_000

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
         */
        fun of(
            page: Int?,
            pageSize: Int?,
        ): Paging = Paging(clampPage(page), clampPageSize(pageSize))

        private fun clampPage(page: Int?): Int =
            when {
                page == null -> 0
                page < 0 -> 0
                page > MAX_PAGE -> MAX_PAGE
                else -> page
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

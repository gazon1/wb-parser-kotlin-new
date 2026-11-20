package ru.wbparser.app.api.catalog

/**
 * The grain at which "the current observation of a product" is decided.
 *
 * ## Why the grain is named and not implied
 *
 * A product is observed per catalog: the same product is seen in several targets, and each
 * observation is a row of its own. "The current observation" therefore has two readings, and
 * they are not the same row:
 *
 * - [PRODUCT] — the newest observation of the product, wherever it was made. This is the
 *   grain of the catalog as a whole: one card per product, whichever target saw it last.
 * - [TARGET] — the newest observation of the product *inside its own target*. This is the
 *   grain of a target's page: one card per product of that target, and the cashback the
 *   product has in that catalog.
 *
 * A read that does not say which of the two it means has to guess, and the guess is what
 * made the list and the detail disagree: the list showed a row of one grain, and the detail
 * looked for the other — so the list handed out an id the detail answered 404 for.
 *
 * ## Why both grains answer the same id
 *
 * The newest observation of a product is also the newest observation of that product
 * inside its own target: a newer observation in the same target would have been the newer
 * one overall. So a row the product-grain list shows is always a row the target-grain
 * detail accepts — which is what makes the two grains usable together.
 */
enum class CurrentScope {
    /** The newest observation of the product, in any target. */
    PRODUCT,

    /** The newest observation of the product, inside its own target. */
    TARGET,
}

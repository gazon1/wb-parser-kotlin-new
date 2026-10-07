package ru.wbparser.domain.value

/**
 * Price stored as integer kopecks to avoid floating-point issues.
 * 1 ruble = 100 kopecks.
 */
@JvmInline
value class Price private constructor(
    val kopecks: Long,
) {
    init {
        require(kopecks >= 0) { "Price cannot be negative: $kopecks kopecks" }
    }

    val inRubles: Double
        get() = kopecks / 100.0

    fun cashbackPercent(cashback: Cashback): Double {
        if (kopecks == 0L) return 0.0
        return (cashback.inRubles / inRubles) * 100.0
    }

    companion object {
        val ZERO: Price = Price(0)

        fun kopecks(kopecks: Long): Price = Price(kopecks)

        fun rubles(rubles: Double): Price = Price((rubles * 100).toLong())
    }
}

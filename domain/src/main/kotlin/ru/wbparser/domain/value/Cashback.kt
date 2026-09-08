package ru.wbparser.domain.value

@JvmInline
value class Cashback private constructor(val kopecks: Long) {
    val inRubles: Double get() = kopecks / 100.0

    companion object {
        val ZERO: Cashback = Cashback(0)

        fun fromRubles(rubles: Double): Cashback = Cashback((rubles * 100).toLong())
        fun percent(percent: Double, ofPrice: Price): Cashback =
            Cashback((ofPrice.inRubles * percent / 100.0 * 100).toLong())
    }
}

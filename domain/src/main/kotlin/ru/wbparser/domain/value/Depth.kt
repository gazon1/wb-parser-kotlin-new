package ru.wbparser.domain.value

@JvmInline
value class Depth(val value: Int) {
    init {
        require(value >= -1) { "Depth must be >= -1 (unlimited)" }
        require(value <= MAX_REASONABLE || value == UNLIMITED) {
            "Depth $value exceeds maximum reasonable value $MAX_REASONABLE"
        }
    }

    val isUnlimited: Boolean get() = value == UNLIMITED

    fun canCrawl(currentDepth: Int): Boolean =
        isUnlimited || currentDepth < value

    companion object {
        const val UNLIMITED = -1
        const val MAX_REASONABLE = 100

        fun unlimited(): Depth = Depth(UNLIMITED)
    }
}

const val UNLIMITED_DEPTH = -1

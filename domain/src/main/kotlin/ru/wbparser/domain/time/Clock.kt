package ru.wbparser.domain.time

import java.time.Instant

/**
 * Abstracts time source for deterministic testing.
 * Domain code must never call Instant.now() directly — use this interface.
 */
interface Clock {
    fun now(): Instant
}

/**
 * Real system clock — for production use only.
 * Never inject this in tests; use FixedClock instead.
 */
object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

/**
 * Frozen clock for deterministic testing.
 * All calls to now() return the same fixed instant.
 */
data class FixedClock(private val fixed: Instant) : Clock {
    constructor(epochMillis: Long) : this(Instant.ofEpochMilli(epochMillis))

    override fun now(): Instant = fixed
}

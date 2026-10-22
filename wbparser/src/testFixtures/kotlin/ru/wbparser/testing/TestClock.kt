package ru.wbparser.testing

import ru.wbparser.domain.time.FixedClock
import java.time.Instant

/**
 * A [FixedClock] initialised to a known point in time.
 * Use in tests to get deterministic timestamps.
 */
fun fixedClockOf(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    second: Int = 0,
): FixedClock {
    val iso = "%04d-%02d-%02dT%02d:%02d:%02dZ".format(year, month, day, hour, minute, second)
    return FixedClock(Instant.parse(iso))
}

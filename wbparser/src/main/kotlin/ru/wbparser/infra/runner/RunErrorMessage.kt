package ru.wbparser.infra.runner

/**
 * How much of a run's error message the job row keeps.
 *
 * A run that failed on every target writes one line per target into one column. With
 * 132 targets and a message that carries a URL and a reason each, the column grows to
 * tens of kilobytes — a row nobody reads to the end, in a table that is read whole by
 * the admin API and copied whole by every backup.
 *
 * The limit is on the *stored* message, and the cut says so: a truncated message that
 * looks whole is worse than a long one, because the reader cannot tell that the tail is
 * missing.
 */
internal const val MAX_RUN_ERROR_MESSAGE_CHARS = 2_000

/**
 * The message as the job row stores it: at most [MAX_RUN_ERROR_MESSAGE_CHARS] characters
 * long, and — when it was longer — a note saying that it was.
 *
 * The cut is made on a code-point boundary: a message cut in the middle of a surrogate
 * pair would leave a broken character in the column, and the row is read by people.
 */
internal fun boundRunErrorMessage(message: String): String {
    if (message.length <= MAX_RUN_ERROR_MESSAGE_CHARS) return message

    val codePoints = message.codePointCount(0, message.length)
    val keptOffset = message.offsetByCodePoints(0, minOf(MAX_RUN_ERROR_MESSAGE_CHARS, codePoints))
    val keptCodePoints = message.codePointCount(0, keptOffset)
    return message.substring(0, keptOffset) +
        "… [truncated: $codePoints characters in total, $keptCodePoints kept]"
}

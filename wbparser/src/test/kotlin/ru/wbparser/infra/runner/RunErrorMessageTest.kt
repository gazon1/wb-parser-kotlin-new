package ru.wbparser.infra.runner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

/**
 * The message a failed run leaves behind has a length.
 *
 * A run that failed on every target writes one line per target into one cell: with 132
 * targets the column grows to tens of kilobytes, and the admin API reads the row whole.
 *
 * The cut has to be visible. A message that was shortened and still looks whole is worse
 * than a long message — the reader cannot tell that the tail is missing.
 */
class RunErrorMessageTest :
    FunSpec({

        test("a message that fits is stored as it is") {
            val message = "Target 9467: the answer does not fit the catalog"

            boundRunErrorMessage(message) shouldBe message
        }

        test("a message that does not fit is cut, and says that it was cut") {
            val message = "Target 9467: failed. ".repeat(400)

            val bounded = boundRunErrorMessage(message)

            val marker =
                "… [truncated: ${message.length} characters in total, " +
                    "$MAX_RUN_ERROR_MESSAGE_CHARS kept]"
            bounded.length shouldBe (MAX_RUN_ERROR_MESSAGE_CHARS + marker.length)
            bounded shouldStartWith "Target 9467: failed."
            // The reader has to be able to tell that the tail is missing — otherwise the
            // message reads as a complete account of a run that failed 132 times.
            bounded shouldContain "truncated"
            bounded shouldContain "${message.length} characters in total"
        }

        test("the cut does not break a character in half") {
            // A surrogate pair is two UTF-16 units and one character; a cut in the middle
            // of it leaves a broken character in the column.
            val message = "😀".repeat(MAX_RUN_ERROR_MESSAGE_CHARS + 10)

            val bounded = boundRunErrorMessage(message)

            // Every high surrogate is still followed by its low one: nothing was cut in
            // the middle of a character.
            bounded.hasUnpairedSurrogate() shouldBe false
            bounded shouldContain "truncated"
        }

        test("the cut keeps the beginning and drops the end") {
            val message = "a".repeat(MAX_RUN_ERROR_MESSAGE_CHARS + 10)

            val bounded = boundRunErrorMessage(message)

            bounded shouldStartWith "a".repeat(MAX_RUN_ERROR_MESSAGE_CHARS)
            bounded shouldContain "${message.length} characters in total"
        }
    })

/**
 * Whether the string carries a surrogate without its partner.
 *
 * A lone surrogate is not a character: it cannot be encoded, and a column that holds one
 * holds something no reader can display.
 */
private fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char.isHighSurrogate()) {
            val next = getOrNull(index + 1)
            if (next == null || !next.isLowSurrogate()) return true
            index += 2
        } else {
            if (char.isLowSurrogate()) return true
            index++
        }
    }
    return false
}

package ru.wbparser.infra.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonDecodingException

/**
 * Describes why a JSON answer failed to decode, in terms an operator can act on.
 *
 * A decode failure used to be reported as "failed to parse" and nothing else: the answer was
 * dropped, the crawl carried on, and the question that mattered — *which field did WB
 * change* — had no answer anywhere. `JsonDecodingException` (kotlinx.serialization 1.12)
 * carries the path of the element that failed, the offset in the input and an excerpt of the
 * input itself, so the failure can name the field.
 *
 * A missing required field is a different exception (`MissingFieldException`, a plain
 * `SerializationException`), and its message carries the path too — so both kinds of
 * "the answer does not fit the model" end up naming the field.
 *
 * This lives in one place because there is more than one place that reads WB's JSON: the
 * catalog page and the subject dictionary. Two places that each report a failure in their own
 * words is two places that report them differently.
 */
@OptIn(ExperimentalSerializationApi::class)
fun describeJsonFailure(failure: Throwable): String =
    when (failure) {
        is JsonDecodingException ->
            "path=${failure.path ?: UNKNOWN_PATH}, offset=${failure.offset}, " +
                "reason=${failure.shortMessage}" +
                (failure.input?.let { ", input=${it.take(INPUT_EXCERPT_LIMIT)}" } ?: "")
        is SerializationException -> "reason=${failure.message ?: failure::class.simpleName}"
        else -> "reason=${failure::class.simpleName}: ${failure.message}"
    }

/** The path of the failing element, when the decoder could not name it. */
private const val UNKNOWN_PATH = "<unknown>"

/** How much of the answer is quoted in the log when a decode fails. */
private const val INPUT_EXCERPT_LIMIT = 300

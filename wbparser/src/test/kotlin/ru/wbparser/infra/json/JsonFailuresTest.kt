package ru.wbparser.infra.json

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecodingException
import ru.wbparser.domain.parsing.WbCatalogEnvelope

/**
 * The failure has to name the field — otherwise the answer to "what did WB change" is a
 * guess.
 */
@OptIn(ExperimentalSerializationApi::class)
class JsonFailuresTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        test("a field of the wrong type is named, with its path and offset") {
            val failure =
                runCatching {
                    json.decodeFromString<WbCatalogEnvelope>("""{"products":[{"id":"не число","name":"Товар"}]}""")
                }.exceptionOrNull()

            val decoding = failure.shouldBeInstanceOf<JsonDecodingException>()

            describeJsonFailure(decoding) shouldContain "$.products[0].id"
        }

        test("a missing required field is reported with the path of the entry that lacks it") {
            val failure =
                runCatching {
                    json.decodeFromString<WbCatalogEnvelope>("""{"products":[{"id":1}]}""")
                }.exceptionOrNull()

            describeJsonFailure(failure!!) shouldContain "$.products[0]"
        }

        test("a failure that is not about JSON still says what it was") {
            describeJsonFailure(IllegalStateException("the socket died")) shouldContain "IllegalStateException"
        }
    })

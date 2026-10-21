package ru.wbparser.app.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.scheduling.Target
import java.time.Instant
import java.util.UUID

/**
 * The admin API serves the target as it is — there is no response DTO — so whether the
 * measurement metadata of a limit reaches the operator is decided by whether Jackson
 * serializes those properties.
 *
 * It is not a formality. A target whose limit nobody checked looks exactly like a
 * target whose limit was checked, unless the date of the measurement travels with the
 * number: an operator looking at `/api/admin/targets` has to be able to tell the two
 * apart, and the only way to tell them apart is to see the metadata.
 *
 * The mapper is the one Spring Boot builds: Jackson with the Java time module and
 * timestamps written as text. The application module has no `jackson-module-kotlin`,
 * so the Kotlin data class is serialized as the bean it is.
 */
class AdminTargetJsonTest :
    FunSpec({

        val mapper =
            ObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        fun target(
            name: String = "Карты памяти",
            knownPagesLimit: Int? = 60,
            measuredAt: Instant? = Instant.parse("2026-10-20T16:46:20Z"),
            boundary: String? = "api_cap",
            source: String? = "measurement",
            subjectId: Long? = 1260L,
        ): Target =
            Target(
                id = UUID.randomUUID(),
                name = name,
                url = "https://www.wildberries.ru/catalog/9467",
                cronExpression = null,
                maxDepth = 1,
                isActive = true,
                knownPagesLimit = knownPagesLimit,
                knownPagesLimitMeasuredAt = measuredAt,
                knownPagesLimitBoundary = boundary,
                knownPagesLimitSource = source,
                wbSubjectId = subjectId,
            )

        test("the measurement metadata of the limit reaches the operator") {
            val json = mapper.readTree(mapper.writeValueAsString(target()))

            json.get("knownPagesLimit").asInt() shouldBe 60
            json.get("knownPagesLimitMeasuredAt").asText() shouldBe "2026-10-20T16:46:20Z"
            json.get("knownPagesLimitBoundary").asText() shouldBe "api_cap"
            json.get("knownPagesLimitSource").asText() shouldBe "measurement"
            json.get("wbSubjectId").asLong() shouldBe 1260L
        }

        test("a target that was never measured says so instead of hiding it") {
            // The field is there and it is null: "nobody checked my limit" is an
            // answer, and an absent field is not one.
            val json =
                mapper.readTree(
                    mapper.writeValueAsString(
                        target(
                            knownPagesLimit = null,
                            measuredAt = null,
                            boundary = null,
                            source = null,
                            subjectId = null,
                        ),
                    ),
                )

            json.has("knownPagesLimitMeasuredAt") shouldBe true
            json.get("knownPagesLimitMeasuredAt").isNull shouldBe true
            json.get("knownPagesLimitBoundary").isNull shouldBe true
            json.get("knownPagesLimitSource").isNull shouldBe true
            json.get("wbSubjectId").isNull shouldBe true
        }
    })

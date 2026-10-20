@file:Suppress("UNCHECKED_CAST")

package ru.wbparser.app.api.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.app.security.CatalogApiKeyFilter
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties

/**
 * The contract and the DTOs are the same thing, said twice.
 *
 * ## What this test is for
 *
 * The OpenAPI document and the Kotlin data classes describe the same shapes. Nothing in
 * the compiler connects them: change a field name in the DTO and the document still
 * describes the old one, and the storefront — which validates its responses against the
 * document — rejects every answer.
 *
 * So the connection is made here, and it is made in both directions:
 *
 * - every schema in the document has a Kotlin counterpart, and every Kotlin DTO has a
 *   schema;
 * - the property names are the same set;
 * - the JSON types are the same, and so are the formats;
 * - `required` in the document is exactly the set of Kotlin properties that are not
 *   nullable — a property that the document says is always present and the DTO says may
 *   be null is a `null` in a response that the contract forbids;
 * - a property the document says may be null really is nullable in Kotlin, and the other
 *   way round;
 * - the serialised JSON has the property names the document names — this is the check
 *   that catches a Kotlin property whose getter Jackson names differently.
 *
 * The last one is not hypothetical. A Kotlin `val isActive: Boolean` compiles to the
 * getter `isActive()`, and Jackson's bean naming turns that into the property `active`:
 * a different name, in the document and in the response, without a word of complaint from
 * the compiler.
 */
class CatalogOpenApiTest :
    FunSpec({
        val document = CatalogContract.parsed
        val schemas = CatalogContract.schemas

        test("the document is an OpenAPI 3.1 document") {
            document["openapi"].shouldBe("3.1.0")
        }

        test("every schema in the document has a Kotlin counterpart") {
            schemas.keys shouldBe SCHEMAS.keys
        }

        schemas.forEach { (name, schema) ->
            test("the schema '$name' agrees with its Kotlin counterpart") {
                val dto = SCHEMAS.getValue(name)
                val resolved = resolveSchema(schema)
                val properties = resolved.properties
                val kotlinProperties = dto.memberProperties.associateBy { it.name }

                // ── The names ───────────────────────────────────────────────────────
                properties.keys shouldBe kotlinProperties.keys

                // ── The types, the nullability and the formats ─────────────────────
                properties.forEach { (propertyName, propertySchema) ->
                    val property = kotlinProperties.getValue(propertyName)
                    val shape = shapeOf(property.returnType)
                    val openApiTypes = openApiTypes(propertySchema as Map<String, Any?>)

                    val nullableInKotlin = property.returnType.isMarkedNullable
                    (openApiTypes.contains("null")) shouldBe nullableInKotlin
                    (openApiTypes - "null") shouldBe setOf(shape.type)

                    // Only the formats the Kotlin type fixes are compared in both
                    // directions. `uuid` and `date-time` are determined by the type — a UUID
                    // is always written as a uuid, an Instant always as a date-time — so a
                    // disagreement there is a real drift. A format like `uri` is
                    // documentation: the value is a string, and the API does not check that
                    // it parses as a URI, so claiming the type fixes it would be false.
                    val format = propertySchema["format"] as? String
                    if (shape.format != null) {
                        format shouldBe shape.format
                    } else if (format in KOTLIN_FIXED_FORMATS) {
                        format shouldBe null
                    }
                }

                // ── required is the set of the properties that cannot be null ──────
                val requiredInKotlin =
                    dto.memberProperties
                        .filterNot { it.returnType.isMarkedNullable }
                        .map { it.name }
                        .toSet()
                resolved.required.toSet() shouldBe requiredInKotlin
            }
        }

        test("the serialised JSON carries the property names the document names") {
            schemas.forEach { (name, schema) ->
                val resolved = resolveSchema(schema)
                val samples = samplesFor(name)

                val complete = mapper.valueToTree<JsonNode>(samples.first())
                complete.fieldNames().asSequence().toSet() shouldBe resolved.properties.keys

                // A property the DTO says may be null really may be null, and the document
                // says so too. The sample with nulls checks the second half of that.
                val withNulls = mapper.valueToTree<JsonNode>(samples[1])
                resolved.properties.keys.forEach { propertyName ->
                    val nullableInKotlin =
                        SCHEMAS
                            .getValue(name)
                            .memberProperties
                            .first { it.name == propertyName }
                            .returnType.isMarkedNullable
                    val openApiTypes =
                        openApiTypes(resolved.properties.getValue(propertyName) as Map<String, Any?>)
                    if (nullableInKotlin) {
                        withNulls.get(propertyName).isNull shouldBe true
                        openApiTypes.contains("null") shouldBe true
                    } else {
                        withNulls.get(propertyName).isNull shouldBe false
                    }
                }
            }
        }

        test("the JSON types of the serialised response match the document") {
            schemas.forEach { (name, schema) ->
                val resolved = resolveSchema(schema)
                val complete = mapper.valueToTree<JsonNode>(samplesFor(name).first())

                resolved.properties.forEach { (propertyName, propertySchema) ->
                    val declared = openApiTypes(propertySchema as Map<String, Any?>) - "null"
                    val actual = jsonTypeOf(complete.get(propertyName))
                    if (declared == setOf("array")) {
                        actual shouldBe "array"
                    } else {
                        declared shouldBe setOf(actual)
                    }
                }
            }
        }

        test("the sort order of the contract is the sort order of the code") {
            val sortParameter =
                parametersOf("/items", "get")
                    .first { (it["name"] as String) == "sort" }
            val declared = (sortParameter["schema"] as Map<String, Any?>)["enum"] as List<*>
            declared.map { it as String } shouldBe CatalogSort.entries.map { it.name }
        }

        test("the paging bounds of the contract are the bounds of the code") {
            val parameters = parametersOf("/items", "get")
            val pageSize = parameters.first { (it["name"] as String) == "pageSize" }["schema"] as Map<String, Any?>
            (pageSize["maximum"] as Int) shouldBe CatalogQueryParams.MAX_PAGE_SIZE
            (pageSize["minimum"] as Int) shouldBe 1
            (pageSize["default"] as Int) shouldBe CatalogQueryParams.DEFAULT_PAGE_SIZE
        }

        test("the endpoints of the contract are the endpoints of the controller") {
            val declared =
                document["paths"]
                    .let { it as Map<String, Any?> }
                    .keys
                    .map { it.removePrefix("/api/catalog/v1") }
                    .toSet()
            declared shouldBe
                setOf(
                    "/targets",
                    "/items",
                    "/items/{id}",
                    "/top-deals",
                    "/stats",
                    "/contract",
                    "/health",
                    "/ready",
                )
        }

        test("the contract mentions the key the filter reads") {
            val components = document["components"] as Map<String, Any?>
            val schemes = components["securitySchemes"] as Map<String, Any?>
            val catalogKey = schemes["CatalogKey"] as Map<String, Any?>
            (catalogKey["type"] as String) shouldBe "apiKey"
            (catalogKey["in"] as String) shouldBe "header"
            (catalogKey["name"] as String) shouldBe CatalogApiKeyFilter.HEADER_NAME
        }
    })

// ── The schemas and their counterparts ────────────────────────────────────────

private val SCHEMAS: Map<String, KClass<*>> =
    mapOf(
        "ProductCard" to ProductCard::class,
        "ProductDetail" to ProductDetail::class,
        "PricePoint" to PricePoint::class,
        "TargetSummary" to TargetSummary::class,
        "PagedProductCard" to Paged::class,
        "SiteStats" to SiteStats::class,
        "Error" to ErrorResponse::class,
    )

// ── Reading the document ──────────────────────────────────────────────────────

/**
 * A schema, with its `allOf` flattened.
 *
 * The document describes a product detail as "a card, and a history" — an `allOf` of a
 * reference and an object. A Kotlin class is flat, so the comparison has to be flat too.
 */
private data class Resolved(
    val properties: Map<String, Any?>,
    val required: List<String>,
)

private fun resolveSchema(schema: Map<String, Any?>): Resolved {
    val allOf = schema["allOf"] as? List<*>
    if (allOf.isNullOrEmpty()) {
        return Resolved(
            properties = schema["properties"] as? Map<String, Any?> ?: emptyMap(),
            required = schema["required"] as? List<String> ?: emptyList(),
        )
    }
    val properties = mutableMapOf<String, Any?>()
    val required = mutableListOf<String>()
    allOf.forEach { element ->
        val resolved = resolveSchema(resolveReference(element as Map<String, Any?>))
        properties.putAll(resolved.properties)
        required += resolved.required
    }
    return Resolved(properties, required)
}

/**
 * A `$ref` node, replaced by the schema it points at.
 */
private fun resolveReference(node: Map<String, Any?>): Map<String, Any?> {
    val reference = node["\$ref"] as? String ?: return node
    val name = reference.substringAfterLast('/')
    return CatalogContract.schemas[name] ?: error("the document references the unknown schema '$reference'")
}

/**
 * The JSON types a schema declares, with "null" among them when it may be null.
 */
private fun openApiTypes(schema: Map<String, Any?>): Set<String> =
    when (val type = schema["type"]) {
        is String -> setOf(type)
        is List<*> -> type.filterIsInstance<String>().toSet()
        else -> emptySet()
    }

/**
 * The query parameters of an operation.
 */
private fun parametersOf(
    path: String,
    method: String,
): List<Map<String, Any?>> {
    val paths = CatalogContract.parsed["paths"] as Map<String, Any?>
    val item = paths[path] as Map<String, Any?>
    val operation = item[method] as Map<String, Any?>
    return (operation["parameters"] as? List<*>).orEmpty().map { it as Map<String, Any?> }
}

// ── The Kotlin side ───────────────────────────────────────────────────────────

// The formats a Kotlin type determines on its own.
private val KOTLIN_FIXED_FORMATS = setOf("uuid", "date-time")

/**
 * The JSON shape of a Kotlin type.
 *
 * The table is written out rather than derived, because the derivation would have to
 * know the mapping anyway — and a table that somebody has to keep in step with the code
 * is a table that says what the code says.
 */

private data class Shape(
    val type: String,
    val format: String? = null,
)

private fun shapeOf(type: KType): Shape {
    val classifier = type.classifier as? KClass<*> ?: return Shape("object")
    return when (classifier) {
        String::class -> Shape("string")
        UUID::class -> Shape("string", "uuid")
        Instant::class -> Shape("string", "date-time")
        Int::class, Long::class, Short::class, Byte::class -> Shape("integer")
        BigDecimal::class, Double::class, Float::class -> Shape("number")
        Boolean::class -> Shape("boolean")
        List::class -> Shape("array")
        Map::class -> Shape("object")
        else -> Shape("object")
    }
}

/**
 * The JSON type of a serialised node.
 */
private fun jsonTypeOf(node: JsonNode): String =
    when {
        node.isNull -> "null"
        node.isTextual -> "string"
        node.isIntegralNumber -> "integer"
        node.isNumber -> "number"
        node.isBoolean -> "boolean"
        node.isArray -> "array"
        node.isObject -> "object"
        else -> "unknown"
    }

// ── Samples ───────────────────────────────────────────────────────────────────

/**
 * The mapper the application uses.
 *
 * The same configuration Spring Boot builds from `spring.jackson.*`: the JavaTime module,
 * and timestamps written as strings rather than as numbers. A mapper that wrote the
 * timestamps as numbers would make every `date-time` in the document wrong.
 */
private val mapper: ObjectMapper =
    JsonMapper
        .builder()
        .addModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()

private const val SAMPLE_UUID = "6f1c3f4e-2b5a-4c7d-8e9f-0a1b2c3d4e5f"
private const val SAMPLE_INSTANT = "2026-01-02T03:04:05Z"

/**
 * Two samples of every DTO: one with every value present, and one with every nullable
 * value null.
 */
private fun samplesFor(name: String): List<Any> {
    val card =
        ProductCard(
            id = UUID.fromString(SAMPLE_UUID),
            targetId = UUID.fromString(SAMPLE_UUID),
            targetName = "Категория",
            title = "Товар",
            priceKopecks = 19900,
            salePriceKopecks = 15920,
            brand = "Бренд",
            seller = "Продавец",
            cashbackPercent = BigDecimal("14.50"),
            cashbackRubles = BigDecimal("28.86"),
            inStock = true,
            imageUrl = "https://example.invalid/1.jpg",
            productUrl = "https://example.invalid/p/1",
            scrapedAt = Instant.parse(SAMPLE_INSTANT),
        )
    val emptyCard =
        card.copy(
            priceKopecks = null,
            salePriceKopecks = null,
            brand = null,
            seller = null,
            cashbackPercent = null,
            cashbackRubles = null,
            inStock = null,
            imageUrl = null,
        )
    val point =
        PricePoint(
            scrapedAt = Instant.parse(SAMPLE_INSTANT),
            priceKopecks = 19900,
            cashbackPercent = BigDecimal("14.50"),
            cashbackRubles = BigDecimal("28.86"),
        )
    val emptyPoint = point.copy(priceKopecks = null, cashbackPercent = null, cashbackRubles = null)
    val target =
        TargetSummary(
            id = UUID.fromString(SAMPLE_UUID),
            name = "Категория",
            rootCategoryName = "Корень",
            isActive = true,
            itemCount = 3,
            newestScrapedAt = Instant.parse(SAMPLE_INSTANT),
        )
    val emptyTarget = target.copy(rootCategoryName = null, newestScrapedAt = null)

    return when (name) {
        "ProductCard" -> listOf(card, emptyCard)
        "ProductDetail" ->
            listOf(
                ProductDetail(
                    id = card.id,
                    targetId = card.targetId,
                    targetName = card.targetName,
                    title = card.title,
                    priceKopecks = card.priceKopecks,
                    salePriceKopecks = card.salePriceKopecks,
                    brand = card.brand,
                    seller = card.seller,
                    cashbackPercent = card.cashbackPercent,
                    cashbackRubles = card.cashbackRubles,
                    inStock = card.inStock,
                    imageUrl = card.imageUrl,
                    productUrl = card.productUrl,
                    scrapedAt = card.scrapedAt,
                    history = listOf(point),
                ),
                ProductDetail(
                    id = card.id,
                    targetId = card.targetId,
                    targetName = card.targetName,
                    title = card.title,
                    priceKopecks = null,
                    salePriceKopecks = null,
                    brand = null,
                    seller = null,
                    cashbackPercent = null,
                    cashbackRubles = null,
                    inStock = null,
                    imageUrl = null,
                    productUrl = card.productUrl,
                    scrapedAt = card.scrapedAt,
                    history = listOf(emptyPoint),
                ),
            )
        "PricePoint" -> listOf(point, emptyPoint)
        "TargetSummary" -> listOf(target, emptyTarget)
        "PagedProductCard" ->
            listOf(
                Paged(
                    items = listOf(card),
                    totalCount = 1,
                    page = 0,
                    pageSize = 24,
                    totalPages = 1,
                    hasNextPage = false,
                    hasPrevPage = false,
                ),
                Paged(
                    items = listOf(emptyCard),
                    totalCount = 0,
                    page = 0,
                    pageSize = 24,
                    totalPages = 0,
                    hasNextPage = false,
                    hasPrevPage = false,
                ),
            )
        "SiteStats" ->
            listOf(
                SiteStats(totalItems = 3, totalTargets = 1, newestScrapedAt = Instant.parse(SAMPLE_INSTANT), withCashback = 2),
                SiteStats(totalItems = 0, totalTargets = 0, newestScrapedAt = null, withCashback = 0),
            )
        "Error" -> listOf(ErrorResponse("something went wrong"), ErrorResponse("something went wrong"))
        else -> error("no sample for the schema '$name'")
    }
}

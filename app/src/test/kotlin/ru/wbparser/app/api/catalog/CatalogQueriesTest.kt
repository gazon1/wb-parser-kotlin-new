package ru.wbparser.app.api.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * The facade over the catalog's decisions.
 *
 * ## Why the surface of the facade is a test
 *
 * The split moved the SQL into the classes that hold the decisions, and the facade
 * delegates to them. The routes and the integration suites use the facade, and they did
 * not have to change — that was the point of the split.
 *
 * A facade that loses a method, renames one, or changes what it returns breaks the
 * routes and the tests at the same time, and the compiler says so. A facade that
 * *widens* its surface breaks nothing, and that is the drift this test is for: the
 * catalog's read API has six methods, and the two places that use it agree on that
 * number.
 *
 * The test reads the methods as the JVM sees them, so it says what a caller sees.
 */
class CatalogQueriesTest :
    FunSpec({
        /**
         * The facade's methods, by name.
         */
        fun methods() = CatalogQueries::class.java.declaredMethods.associateBy { it.name }

        test("the facade answers with the six methods the routes use") {
            methods().keys shouldBe
                setOf(
                    "listItems",
                    "listTargets",
                    "getItem",
                    "listTopDeals",
                    "siteStats",
                    "missingColumns",
                )
        }

        test("the methods take the parameters they always took") {
            val methods = methods()

            methods.getValue("listItems").parameterTypes.toList() shouldBe listOf(CatalogQueryParams::class.java)
            methods.getValue("listTargets").parameterTypes.toList() shouldBe emptyList()
            methods.getValue("getItem").parameterTypes.toList() shouldBe listOf(UUID::class.java)
            methods.getValue("listTopDeals").parameterTypes.toList() shouldBe listOf(TopDealsQueryParams::class.java)
            methods.getValue("siteStats").parameterTypes.toList() shouldBe emptyList()
            methods.getValue("missingColumns").parameterTypes.toList() shouldBe emptyList()
        }

        test("the methods return what they always returned") {
            val methods = methods()

            methods.getValue("listItems").returnType shouldBe Paged::class.java
            methods.getValue("listTargets").returnType shouldBe List::class.java
            methods.getValue("getItem").returnType shouldBe ProductDetail::class.java
            methods.getValue("listTopDeals").returnType shouldBe Paged::class.java
            methods.getValue("siteStats").returnType shouldBe SiteStats::class.java
            methods.getValue("missingColumns").returnType shouldBe List::class.java
        }
    })

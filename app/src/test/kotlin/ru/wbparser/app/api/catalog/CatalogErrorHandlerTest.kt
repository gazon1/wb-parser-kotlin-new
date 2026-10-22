package ru.wbparser.app.api.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.MethodParameter
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Every error the catalog answers has one shape.
 *
 * The storefront has to understand one error body, `{"error": "..."}`, and it has to get
 * it for every failure — a bad parameter, a missing product, an unknown path and the
 * failure nobody planned for. Each of those is a decision, and each of them was
 * untested: the advice was exercised only where a test happened to pass through it.
 */
class CatalogErrorHandlerTest :
    FunSpec({

        val handler = CatalogErrorHandler()

        /**
         * A parameter to bind against — Spring's exception insists on one.
         */
        fun parameter(): MethodParameter =
            MethodParameter(
                Fixture::class.java.getDeclaredMethod("echo", String::class.java),
                0,
            )

        test("a parameter the API refuses to interpret is a bad request") {
            val response = handler.onBadRequest(CatalogBadRequestException("page must be a number"))

            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            response.body?.error shouldBe "page must be a number"
        }

        test("a bad request without a reason still says something") {
            val response = handler.onBadRequest(CatalogBadRequestException(""))

            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            response.body?.error shouldBe "bad request"
        }

        test("a product that does not exist is not found") {
            val response = handler.onNotFound(CatalogNotFoundException("product 42 is not in the database"))

            response.statusCode shouldBe HttpStatus.NOT_FOUND
            response.body?.error shouldBe "product 42 is not in the database"
        }

        test("a missing product without a reason still says something") {
            val response = handler.onNotFound(CatalogNotFoundException(""))

            response.statusCode shouldBe HttpStatus.NOT_FOUND
            response.body?.error shouldBe "not found"
        }

        test("a value that cannot be bound is a bad request, not Spring's own body") {
            val exception =
                MethodArgumentTypeMismatchException(
                    "abc",
                    String::class.java,
                    "page",
                    parameter(),
                    IllegalArgumentException("not a number"),
                )

            val response = handler.onTypeMismatch(exception)

            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            // Spring would answer with its own body, which is not the shape the contract
            // describes — a consumer of this API would have to parse two formats.
            // The name of the parameter, not of the exception class: "page is not a
            // valid value" tells the caller what to fix.
            response.body?.error shouldBe "page is not a valid value"
        }

        test("a path under the catalog prefix that is not a route is not found") {
            val response = handler.onNoResource(NoResourceFoundException(HttpMethod.GET, "/api/catalog/nope"))

            response.statusCode shouldBe HttpStatus.NOT_FOUND
            response.body?.error shouldBe "no such endpoint"
        }

        test("an unexpected failure says only that it failed") {
            val response = handler.onUnexpected(IllegalStateException("jdbc:postgresql://host/db?password=secret"))

            response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            // The details go to the log, where the operator reads them: a response body
            // is read by whoever made the request, and an exception message can carry a
            // connection string.
            response.body?.error shouldBe "internal error"
        }
    })

/**
 * A method to bind a parameter against.
 *
 * Spring's exception insists on a [MethodParameter], and a real one is the only honest
 * way to build it.
 */
private class Fixture {
    @Suppress("unused")
    fun echo(value: String): String = value
}

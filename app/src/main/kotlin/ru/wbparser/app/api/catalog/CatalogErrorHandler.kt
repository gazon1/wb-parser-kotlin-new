package ru.wbparser.app.api.catalog

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * A product the database does not have.
 */
class CatalogNotFoundException(
    message: String,
) : RuntimeException(message)

/**
 * The catalog's error responses.
 *
 * Every error is `{"error": "..."}` — the shape the contract describes, and the only error
 * body the storefront has to understand.
 *
 * The advice is bound to the catalog controller on purpose. The admin API and the
 * actuator have their own error shapes, and a global advice would quietly replace them.
 */
@RestControllerAdvice(assignableTypes = [CatalogRoutes::class])
class CatalogErrorHandler {
    /**
     * A parameter the API refuses to interpret.
     */
    @ExceptionHandler(CatalogBadRequestException::class)
    fun onBadRequest(exception: CatalogBadRequestException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(error = exception.message ?: "bad request"))

    /**
     * A product that does not exist.
     */
    @ExceptionHandler(CatalogNotFoundException::class)
    fun onNotFound(exception: CatalogNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(error = exception.message ?: "not found"))

    /**
     * A parameter that could not be bound to the type the controller asked for.
     *
     * Spring would answer these with its own body, which is not the shape the contract
     * describes. `?page=abc` is a bad request, and it should look like one.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onTypeMismatch(exception: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(error = "${exception.name} is not a valid value"))

    /**
     * A path under the catalog prefix that is not one of its routes.
     *
     * Answered with the contract's error shape rather than Spring's HTML page, so that a
     * consumer of the API never has to parse two formats.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun onNoResource(exception: NoResourceFoundException): ResponseEntity<ErrorResponse> {
        logger.debug("catalog: no route for {}", exception.resourcePath)
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(error = "no such endpoint"))
    }

    /**
     * Anything else.
     *
     * The body says only that it failed. The details go to the log, where the operator can
     * read them — a response body is read by whoever made the request, and an exception
     * message can carry a SQL fragment, a connection string or a file name.
     */
    @ExceptionHandler(Exception::class)
    fun onUnexpected(exception: Exception): ResponseEntity<ErrorResponse> {
        logger.error("catalog: unhandled failure", exception)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(error = "internal error"))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CatalogErrorHandler::class.java)
    }
}

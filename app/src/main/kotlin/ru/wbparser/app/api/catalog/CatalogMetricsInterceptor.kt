package ru.wbparser.app.api.catalog

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import java.util.concurrent.TimeUnit

/**
 * Counts and times the catalog's requests.
 *
 * ## Why the numbers matter
 *
 * The connection budget of the database is worked out from two things: how many
 * connections the pools hold, and how much traffic the catalog actually serves. Both
 * numbers come from the metrics, and without them the pool size would be a guess that
 * nobody could check later.
 *
 * The endpoint label is the route pattern, not the raw path: a path that carries a
 * product id would create one time series per product, and a metric registry that grows
 * with the data is a metric registry that will eventually take the process down.
 */
@Component
class CatalogMetricsInterceptor(
    private val meterRegistry: MeterRegistry,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        request.setAttribute(START_NANOS, System.nanoTime())
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        exception: Exception?,
    ) {
        val endpoint = endpointOf(request)
        meterRegistry
            .counter(
                "catalog.requests",
                "endpoint",
                endpoint,
                "status",
                response.status.toString(),
            ).increment()
        val startedAt = request.getAttribute(START_NANOS) as? Long ?: return
        meterRegistry
            .timer("catalog.query.duration", "endpoint", endpoint)
            .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
    }

    /**
     * The route pattern of the request, with the version prefix removed.
     */
    private fun endpointOf(request: HttpServletRequest): String {
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
        return (pattern ?: request.requestURI).removePrefix("/api/catalog/v1")
    }

    companion object {
        private const val START_NANOS = "ru.wbparser.catalog.startNanos"
    }
}

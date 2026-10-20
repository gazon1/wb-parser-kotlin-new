package ru.wbparser.app.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import ru.wbparser.app.api.catalog.CatalogCache
import ru.wbparser.app.api.catalog.CatalogMetricsInterceptor
import ru.wbparser.app.api.catalog.CatalogQueries
import ru.wbparser.app.api.catalog.CatalogReadiness

/**
 * Wires the catalog's beans.
 *
 * The catalog's collaborators are constructed here rather than inside the controller, so
 * that the controller depends on the repository and the readiness check, and not on the
 * pool. That is what makes it possible to test the controller without a database.
 */
@Configuration
class CatalogConfig {
    /**
     * The catalog's repository. It talks to the catalog pool and to nothing else.
     */
    @Bean
    fun catalogQueries(catalogDataSource: CatalogDataSource): CatalogQueries = CatalogQueries(catalogDataSource)

    /**
     * The cache of the two expensive aggregates.
     */
    @Bean
    fun catalogCache(): CatalogCache = CatalogCache()

    /**
     * The readiness check.
     */
    @Bean
    fun catalogReadiness(
        catalogDataSource: CatalogDataSource,
        catalogQueries: CatalogQueries,
    ): CatalogReadiness = CatalogReadiness(catalogDataSource, catalogQueries)
}

/**
 * Registers the catalog's request metrics.
 *
 * Scoped to the catalog's paths: the admin API's traffic is not the storefront's, and
 * mixing the two would make the catalog's numbers unreadable.
 */
@Configuration
class CatalogWebConfig(
    private val catalogMetricsInterceptor: CatalogMetricsInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(catalogMetricsInterceptor).addPathPatterns("/api/catalog/**")
    }
}

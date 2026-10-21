package ru.wbparser.app.config

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Builds the catalog's own connection pool.
 *
 * The pool lives next to the writer's pool and knows nothing about it. That separation is
 * the point: the catalog must not be able to reach the writer's credentials, and the writer
 * must not have to think about how many storefront requests are waiting for a connection.
 *
 * See [CatalogDataSource] for why the pool is not exposed as a `DataSource` bean.
 */
@Configuration
class CatalogDataSourceConfig {
    /**
     * The catalog pool.
     *
     * The Hikari metrics are wired to the application's meter registry, so the pool shows
     * up in `/actuator/prometheus` under `hikaricp.*` next to the writer's pool. The
     * connection budget of the database is worked out from those two sets of numbers, and
     * a pool whose size was guessed cannot be checked.
     */
    @Bean(destroyMethod = "close")
    fun catalogDataSource(
        properties: CatalogProperties,
        meterRegistry: MeterRegistry,
    ): CatalogDataSource =
        CatalogDataSource.create(
            properties = properties,
            meterRegistry = meterRegistry,
        )
}

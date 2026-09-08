package ru.wbparser.app.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.connect

/**
 * Provides the [DatabaseHandle] as a Spring bean.
 *
 * Credentials come from application.yml via [CrawlProperties.database].
 * This replaces the field-init `connect()` call in [ru.wbparser.infra.runner.CrawlRunner],
 * making the dependency explicit and testable.
 */
@Configuration
class DatabaseConfig {

    @Bean
    fun databaseHandle(properties: CrawlProperties): DatabaseHandle {
        return connect(
            url = properties.database.url,
            user = properties.database.username,
            password = properties.database.password,
            poolSize = properties.database.poolSize,
        )
    }
}

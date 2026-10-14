package ru.wbparser.app.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.connect
import javax.sql.DataSource

/**
 * Provides the [DatabaseHandle] as a Spring bean.
 *
 * Uses Spring Boot's auto-configured [DataSource] (from `spring.datasource.*`) as the
 * underlying connection pool. [DatabaseHandle.db] (Exposed [Database]) is then used by
 * [ru.wbparser.infra.pipeline.PipelineRunner], while [DatabaseHandle.ds] is used directly
 * by [ru.wbparser.infra.runner.CrawlRunner].
 *
 * Previously this class created a second HikariCP pool from [CrawlProperties.database],
 * pointing at the same database as the auto-configured one — two pools for one database.
 * One pool is enough.
 */
@Configuration
class DatabaseConfig {
    @Bean
    fun databaseHandle(ds: DataSource): DatabaseHandle = connect(ds)
}

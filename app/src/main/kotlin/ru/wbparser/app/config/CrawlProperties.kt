package ru.wbparser.app.config

/**
 * Crawl configuration properties.
 * Spring's @ConfigurationPropertiesScan handles binding from application.yml/yaml.
 */
data class CrawlProperties(
    val schedule: Schedule = Schedule(),
    val spider: Spider = Spider(),
    val database: Database = Database(),
    val worker: Worker = Worker(),
)

data class Schedule(
    val cron: String = "0 0 */4 * * *",
)

data class Spider(
    val maxDepth: Int = 2,
    val maxPagesPerCatalog: Int = 10,
    val concurrency: Int = 3,
    val useBrowserForAuth: Boolean = true,
)

data class Database(
    val url: String = "jdbc:postgresql://localhost:5432/wbparser",
    val username: String = "postgres",
    val password: String = "postgres",
    val poolSize: Int = 10,
)

data class Worker(
    val enabled: Boolean = false,
)

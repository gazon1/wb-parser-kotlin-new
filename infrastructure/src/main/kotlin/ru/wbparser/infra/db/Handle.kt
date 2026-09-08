package ru.wbparser.infra.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

/**
 * Database handle wrapping DataSource and Exposed Database.
 */
data class DatabaseHandle(
    val ds: DataSource,
    val db: Database,
)

/**
 * Connect to PostgreSQL and return a DatabaseHandle.
 */
fun connect(
    url: String = "jdbc:postgresql://localhost:5432/wbparser",
    user: String = "postgres",
    password: String = "postgres",
    poolSize: Int = 10,
): DatabaseHandle {
    val config = HikariConfig().apply {
        jdbcUrl = url
        username = user
        this.password = password
        maximumPoolSize = poolSize
        minimumIdle = 2
        connectionTimeout = 30_000
        idleTimeout = 600_000
    }

    val ds: DataSource = HikariDataSource(config)
    val db = Database.connect(ds)
    return DatabaseHandle(ds, db)
}

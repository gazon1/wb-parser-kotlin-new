package ru.wbparser.infra.db

import org.jetbrains.exposed.sql.Database
import java.util.Collections
import java.util.WeakHashMap
import javax.sql.DataSource

/**
 * Database handle wrapping DataSource and Exposed Database.
 */
data class DatabaseHandle(
    val ds: DataSource,
    val db: Database,
)

/**
 * Wrap an existing [DataSource] in a [DatabaseHandle].
 *
 * Use this when the [DataSource] is already configured (e.g. Spring Boot auto-configures
 * one from `spring.datasource.*`). Exposed's [Database] is a thin wrapper that adds
 * DSL capabilities — it does not open its own connections.
 *
 * ## Who owns the pool
 *
 * The caller does. This module does not build connection pools: the crawler's pool is
 * Spring Boot's, configured in `application.yml`, and the catalog's own read-only pool
 * is built by the application that owns it. A second place that decides the pool's size
 * is a second number for one guarantee, and the two disagree the moment one of them is
 * changed.
 */
fun connect(ds: DataSource): DatabaseHandle {
    val db = Database.connect(ds)
    return DatabaseHandle(ds, db)
}

/**
 * Registers this [DataSource] with Exposed exactly once and returns the registered [Database].
 *
 * `Database.connect(ds)` is not the harmless no-op the name suggests: each call registers the
 * datasource in Exposed's global `datasources` map. Calling it per query — which the
 * repositories used to do — therefore mutates shared global state on every read and write.
 * That is harmless while a single thread owns the crawl and a data race the moment targets
 * run concurrently.
 *
 * The map is weak-keyed so a DataSource that goes out of scope (a test container, a rebuilt
 * Spring context) is not pinned here for the life of the JVM. The double-checked
 * `getOrPut` keeps registration exactly-once without holding the lock on the read path.
 */
private val exposedDatabases: MutableMap<DataSource, Database> =
    Collections.synchronizedMap(WeakHashMap())

internal fun DataSource.exposedOnce(): Database {
    exposedDatabases[this]?.let { return it }
    synchronized(exposedDatabases) {
        return exposedDatabases.getOrPut(this) { Database.connect(this) }
    }
}

package ru.wbparser.app.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The catalog API's own settings, bound from the `catalog.*` block of application.yml.
 *
 * Constructor binding: every group has defaults, so a partial block yields defaults rather
 * than a startup failure. The one exception is [apiKey] — it has no usable default, and an
 * empty one is refused when the security chain is built.
 */
@ConfigurationProperties(prefix = "catalog")
data class CatalogProperties(
    val datasource: Datasource = Datasource(),
    val pool: Pool = Pool(),
    val apiKey: String = "",
    val demoSeed: DemoSeed = DemoSeed(),
    val rateLimit: RateLimit = RateLimit(),
)

/**
 * Where the catalog pool connects, and as whom.
 *
 * The user is **not** the writer's user. The catalog reads through the `wb_catalog_ro`
 * role, whose only rights are `CONNECT`, `USAGE` and `SELECT`. That is the boundary: the
 * claim "the catalog cannot write" is enforced by PostgreSQL's privilege system, not by a
 * session setting that any code with the same credentials could turn off.
 *
 * The password defaults to empty on purpose. It is not a default credential — it is the
 * absence of one, and a deployment that forgets to set it gets a connection failure that
 * the readiness check reports, not a catalog that anyone can write to.
 */
data class Datasource(
    val url: String = "jdbc:postgresql://localhost:5432/wbparser",
    val username: String = "wb_catalog_ro",
    val password: String = "",
)

/**
 * The catalog pool's shape.
 *
 * Five connections is the starting point, not a measured number: the budget of the
 * database as a whole (`writer + catalog + everything else ≤ 0.7 × max_connections`) is
 * worked out against the real production database, and the load test confirms or corrects
 * this figure.
 */
data class Pool(
    val maxSize: Int = 5,
    val minIdle: Int = 1,
    val connectionTimeoutMs: Long = 3_000,
)

/**
 * The demo seed's settings.
 *
 * Every field here is a barrier, not a preference. The seed fills `scraped_items` with
 * synthetic data, and the only thing standing between that and a production database is
 * that all of these have to line up at once — see `DemoSeedRunner` for what each one
 * refuses.
 */
data class DemoSeed(
    /** The seed does not run while this is false, whatever else is true. */
    val enabled: Boolean = false,
    /** The credentials the seed connects with — separate from the writer's. */
    val dbUser: String = "",
    /** The password of [dbUser]. */
    val dbPassword: String = "",
    /** The role [dbUser] must resolve to, matched exactly. */
    val expectedRole: String = "",
    /** The identity the database itself must carry. */
    val dbId: String = "",
)

/**
 * How many catalog requests one key may make in a minute.
 *
 * The limit is a second line of defence behind the key: the key says *who* may read, and
 * this says *how much*. It is generous on purpose — six hundred a minute is ten a second,
 * far above what a storefront needs and far below what a runaway needs to hurt.
 *
 * The limit can be switched off, and it is off in the tests that measure the catalog's
 * speed: a rate limiter that answers `429` halfway through a measurement would make the
 * measurement a statement about the limiter.
 */
data class RateLimit(
    /** The limit is not applied while this is false. */
    val enabled: Boolean = true,
    /** The requests one key may make in the window. */
    val requestsPerMinute: Int = 600,
)

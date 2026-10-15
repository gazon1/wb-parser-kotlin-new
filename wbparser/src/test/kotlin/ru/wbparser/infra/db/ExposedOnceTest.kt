package ru.wbparser.infra.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

/**
 * Contract for [exposedOnce] — register a DataSource with Exposed exactly once.
 *
 * ## Why this needs pinning
 *
 * `Database.connect(ds)` is not idempotent in the way the old call sites assumed. Each call
 * registers the datasource in Exposed's global `datasources` map, so the repositories used to
 * mutate shared global state on *every read and every write*. That is survivable while a
 * single thread owns the crawl and a data race the moment targets run concurrently.
 *
 * No database is needed: `Database.connect` only registers, it does not open a connection, so
 * the datasource below never talks to a server.
 */
class ExposedOnceTest :
    FunSpec({

        fun dataSource(name: String): DataSource =
            PGSimpleDataSource().apply {
                setUrl("jdbc:postgresql://127.0.0.1:1/$name")
                user = "nobody"
                password = "nothing"
                setProperty("ApplicationName", "exposed-once-test-$name")
            }

        test("the same DataSource yields the same Database every time") {
            val ds = dataSource("same")

            val first = ds.exposedOnce()
            val second = ds.exposedOnce()
            val third = ds.exposedOnce()

            first shouldBe second
            second shouldBe third
            first.shouldNotBeNull()
        }

        test("distinct DataSources yield distinct Database instances") {
            val a = dataSource("alpha").exposedOnce()
            val b = dataSource("beta").exposedOnce()

            a shouldNotBe b
        }

        test("concurrent first calls agree on one registration") {
            val ds = dataSource("racy")

            // The path this exists for: several crawl workers calling exposedOnce() at once
            // during the first save of a parallel run. Without the double-checked getOrPut,
            // these race into Exposed's global map.
            val results =
                runBlocking {
                    (1..32).map { async { ds.exposedOnce() } }.awaitAll()
                }

            results.distinct().size shouldBe 1
        }
    })

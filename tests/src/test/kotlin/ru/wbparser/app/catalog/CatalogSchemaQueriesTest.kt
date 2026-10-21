package ru.wbparser.app.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import ru.wbparser.app.api.catalog.CatalogSchemaQueries

/**
 * The catalog's answer about the shape of the database: which of the columns it reads
 * are not there.
 *
 * ## What these test is about
 *
 * The check has one job: to say that the columns the catalog reads are there. On a
 * migrated schema it says that nothing is missing, and that is the answer the readiness
 * probe turns into `UP` — the difference between a catalog that serves and a catalog
 * that answers every page with `500`.
 *
 * The check asks about the columns the catalog *reads* and not about every column the
 * database has: a database with a hundred columns the catalog never touches is a
 * database the catalog can serve, and a check that said otherwise would report a problem
 * that does not exist.
 */
class CatalogSchemaQueriesTest :
    FunSpec({
        val fixture = CatalogFixture()

        /**
         * The queries this class is about.
         */
        fun queries(): CatalogSchemaQueries = CatalogSchemaQueries(fixture.catalogDataSource)

        beforeSpec { fixture.start() }
        afterSpec { fixture.stop() }

        test("a migrated schema has no missing column") {
            queries().missingColumns().shouldBeEmpty()
        }
    })

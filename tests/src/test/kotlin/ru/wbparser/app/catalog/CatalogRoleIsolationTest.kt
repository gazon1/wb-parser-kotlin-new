package ru.wbparser.app.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The claim "the catalog cannot write", checked against the database itself.
 *
 * ## Why the criterion is the SQL state and not the exception
 *
 * The interesting part of a refusal is *which* refusal. A `42501 insufficient_privilege`
 * says the database refused because the role has no right — that is the privilege
 * boundary working. A `25006 read_only_sql_transaction` says the session was told not to
 * write, which is a setting any code holding the same credentials could undo.
 *
 * So the tests below connect with the catalog role's own credentials and *without* the
 * session settings the pool applies, and they insist on `42501`. The read-only session
 * is tested separately, as the second line of defence it is.
 *
 * ## Why the scripts themselves are run
 *
 * The role's rights are the output of `db/roles/provision-catalog-readonly-role.sql`. A
 * test that granted the rights itself would be testing its own grant, and the script an
 * operator actually runs would be untested. So the script runs here, against a real
 * PostgreSQL, by a real `psql` — and the verification script's exit code is an assertion.
 */
class CatalogRoleIsolationTest :
    FunSpec({
        val fixture = CatalogFixture()

        beforeSpec { fixture.start() }
        afterSpec { fixture.stop() }
        beforeTest { fixture.clear() }

        test("the verification script accepts the provisioned role") {
            fixture.runRoleScript("/db/roles/verify-catalog-readonly-role.sql") shouldBe 0
        }

        test("the catalog role may read") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target)

            fixture.catalogConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM scraped_items").use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1) shouldBe 1
                    }
                }
            }
        }

        test("the catalog role may not insert") {
            val target = fixture.seedTarget("Категория")
            val state =
                fixture.failureState {
                    fixture.catalogConnection().use { connection ->
                        connection
                            .prepareStatement(
                                """
                                INSERT INTO scraped_items (id, target_id, product_url, content_hash)
                                VALUES (gen_random_uuid(), ?, 'https://example.invalid/p', 'hash-1')
                                """.trimIndent(),
                            ).use {
                                it.setObject(1, target)
                                it.executeUpdate()
                            }
                    }
                }
            state shouldBe "42501"
        }

        test("the catalog role may not update") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target)
            val state =
                fixture.failureState {
                    fixture.catalogConnection().use { connection ->
                        connection.createStatement().use {
                            it.executeUpdate("UPDATE scraped_items SET title = 'changed'")
                        }
                    }
                }
            state shouldBe "42501"
        }

        test("the catalog role may not delete") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target)
            val state =
                fixture.failureState {
                    fixture.catalogConnection().use { connection ->
                        connection.createStatement().use {
                            it.executeUpdate("DELETE FROM scraped_items")
                        }
                    }
                }
            state shouldBe "42501"
        }

        test("the catalog role may not create a table") {
            val state =
                fixture.failureState {
                    fixture.catalogConnection().use { connection ->
                        connection.createStatement().use {
                            it.executeUpdate("CREATE TABLE public.catalog_should_not_exist (id int)")
                        }
                    }
                }
            state shouldBe "42501"
        }

        test("the catalog role may not create a schema") {
            val state =
                fixture.failureState {
                    fixture.catalogConnection().use { connection ->
                        connection.createStatement().use {
                            it.executeUpdate("CREATE SCHEMA catalog_should_not_exist")
                        }
                    }
                }
            state shouldBe "42501"
        }

        test("the catalog role may not create a temporary table") {
            val state =
                fixture.failureState {
                    fixture.catalogConnection().use { connection ->
                        connection.createStatement().use {
                            it.executeUpdate("CREATE TEMP TABLE catalog_should_not_exist (id int)")
                        }
                    }
                }
            state shouldBe "42501"
        }

        test("the catalog pool's session refuses a write as well") {
            val target = fixture.seedTarget("Категория")
            val state =
                fixture.failureState {
                    fixture.catalogDataSource.read { connection ->
                        connection.createStatement().use {
                            it.executeUpdate("UPDATE scraped_items SET title = 'changed'")
                        }
                    }
                }
            // The pool's session is read-only, so the refusal comes one layer earlier
            // than the privilege boundary does. Both refuse; the privilege boundary is
            // the one that holds when the session setting is not there.
            state shouldBe "25006"
        }

        test("the writer can still write") {
            val target = fixture.seedTarget("Категория")
            fixture.writer.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        INSERT INTO scraped_items (id, target_id, product_url, content_hash)
                        VALUES (gen_random_uuid(), ?, 'https://example.invalid/p', 'hash-writer')
                        """.trimIndent(),
                    ).use {
                        it.setObject(1, target)
                        it.executeUpdate()
                    }
            }

            fixture.writer.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM scraped_items").use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1) shouldBe 1
                    }
                }
            }
        }
    })

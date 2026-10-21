package ru.wbparser.app.api.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.sqlite.SQLiteDataSource
import java.nio.file.Files

/**
 * How a value reaches a statement.
 *
 * ## Why a real database and not a stand-in for one
 *
 * The binding is the boundary where a value stops being a Kotlin value and becomes a
 * database value, and the interesting cases — a null, a number where a number is
 * expected — are decided by the driver, not by this code. A stand-in for a statement
 * could only reproduce the decisions it was told about, and would say nothing about
 * whether the database agrees.
 *
 * SQLite needs no server and no container, and it is a real database: the row that comes
 * back is the row the values asked for.
 */
class CatalogStatementBindingTest :
    FunSpec({
        /**
         * A real, in-process database, of this test's own.
         *
         * A file, not `jdbc:sqlite::memory:`: every connection to an in-memory SQLite
         * opens its *own* empty database, and the rows created for one test would be
         * gone by the time the next statement opened a connection.
         */
        fun database(): SQLiteDataSource {
            val file = Files.createTempFile("catalog-binding", ".db")
            file.toFile().deleteOnExit()
            return SQLiteDataSource().apply {
                url = "jdbc:sqlite:${file.toAbsolutePath()}"
            }
        }

        test("the values are bound in the order the placeholders take them") {
            val source = database()

            source.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE bound (a TEXT, b TEXT, c INTEGER)") }
                connection.prepareStatement("INSERT INTO bound (a, b, c) VALUES (?, ?, ?)").use { statement ->
                    CatalogStatementBinding(statement).bind(listOf("first", "second"), 3)
                    statement.executeUpdate()
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT a, b, c FROM bound").use { rows ->
                        rows.next() shouldBe true
                        rows.getString("a") shouldBe "first"
                        rows.getString("b") shouldBe "second"
                        rows.getInt("c") shouldBe 3
                    }
                }
            }
        }

        test("a null is bound as a null, and not as the text 'null'") {
            val source = database()

            source.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE bound (a TEXT)") }
                connection.prepareStatement("INSERT INTO bound (a) VALUES (?)").use { statement ->
                    CatalogStatementBinding(statement).bind(listOf(null))
                    statement.executeUpdate()
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT a FROM bound").use { rows ->
                        rows.next() shouldBe true
                        rows.getString("a").shouldBeNull()
                    }
                }
            }
        }

        test("the paging values come after the values of the filters") {
            val source = database()

            source.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE bound (a TEXT, b INTEGER, c INTEGER)") }
                connection.prepareStatement("INSERT INTO bound (a, b, c) VALUES (?, ?, ?)").use { statement ->
                    CatalogStatementBinding(statement).bind(listOf("filter"), 24, 48)
                    statement.executeUpdate()
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT a, b, c FROM bound").use { rows ->
                        rows.next() shouldBe true
                        rows.getString("a") shouldBe "filter"
                        // The page size, and then the offset: the two numbers the
                        // `LIMIT` and the `OFFSET` take.
                        rows.getInt("b") shouldBe 24
                        rows.getInt("c") shouldBe 48
                    }
                }
            }
        }
    })

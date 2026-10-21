package ru.wbparser.app.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.DefaultApplicationArguments
import org.sqlite.SQLiteDataSource
import ru.wbparser.infra.db.connect
import java.nio.file.Files
import javax.sql.DataSource

/**
 * The subject dictionary is loaded at startup so that a deployment cannot forget the
 * step and collect nameless products.
 *
 * These tests pin the two properties that matter beyond "it loads":
 *
 * 1. A dictionary that is already there is **not** downloaded again — the file is
 *    several megabytes and the boot must not depend on WB's CDN for a deployment
 *    that is already complete.
 * 2. A dictionary that cannot be read is **not fatal**. The crawl still works; the
 *    products just carry a NULL name next to a real subject id.
 */
class WbSubjectBootstrapTest :
    FunSpec({

        test("a dictionary that is already there is left alone") {
            val dataSource = dataSourceWith("CREATE TABLE wb_subjects (id INTEGER NOT NULL, name TEXT NOT NULL)")
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("INSERT INTO wb_subjects (id, name) VALUES (1, 'Карта памяти')")
                }
            }

            val bootstrap = WbSubjectBootstrap(connect(dataSource))

            bootstrap.run(DefaultApplicationArguments())

            countSubjects(dataSource) shouldBe 1L
        }

        test("a dictionary that cannot be read does not stop the boot") {
            // No wb_subjects table at all: the count fails, and the boot continues.
            val dataSource = dataSourceWith()

            val bootstrap = WbSubjectBootstrap(connect(dataSource))

            // The contract: no exception escapes. A deployment without the dictionary
            // must still serve traffic and still crawl.
            bootstrap.run(DefaultApplicationArguments())
        }
    })

/**
 * A real, in-process database.
 *
 * A file, not `jdbc:sqlite::memory:`: every connection to an in-memory SQLite
 * opens its own empty database, so the fixture rows would be gone by the time
 * the next query opened a connection.
 */
private fun dataSourceWith(vararg statements: String): DataSource {
    val file = Files.createTempFile("subject-bootstrap", ".db")
    file.toFile().deleteOnExit()
    val source = SQLiteDataSource()
    source.url = "jdbc:sqlite:${file.toAbsolutePath()}"
    if (statements.isNotEmpty()) {
        source.connection.use { conn ->
            conn.createStatement().use { stmt ->
                statements.forEach { stmt.execute(it) }
            }
        }
    }
    return source
}

private fun countSubjects(dataSource: DataSource): Long =
    dataSource.connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT count(*) FROM wb_subjects").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

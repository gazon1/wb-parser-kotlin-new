package ru.wbparser.app.bootstrap

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.DefaultApplicationArguments
import org.sqlite.SQLiteDataSource
import ru.wbparser.infra.db.connect
import java.net.InetSocketAddress
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

        // The download itself. WB's dictionary is a static file on a CDN, and the boot
        // has to survive everything that file can do to it: a slow answer, an error
        // answer, a body that is not JSON, a body that is gzipped. The dictionary is
        // loaded from a local server here, so the test does not depend on WB.

        test("the dictionary is downloaded and written") {
            val body =
                """
                [
                  {"id": 1, "name": "Карта памяти", "parentId": 9},
                  {"id": 2, "name": "   "},
                  {"id": "x", "name": "не число"},
                  {"name": "без id"},
                  "не объект"
                ]
                """.trimIndent().toByteArray()

            serving(body = body) { url ->
                val dataSource = dataSourceWith("CREATE TABLE wb_subjects (id INTEGER NOT NULL, name TEXT NOT NULL)")
                val bootstrap = WbSubjectBootstrap(connect(dataSource))
                bootstrap.subjectsUrl = url

                bootstrap.run(DefaultApplicationArguments())

                // Only the entry that can be read is written: a blank name, an id that
                // is not a number and an entry without an id are all skipped, because a
                // product with a NULL name next to a real id is exactly what the
                // dictionary exists to prevent.
                countSubjects(dataSource) shouldBe 1L
                subjectName(dataSource, 1L) shouldBe "Карта памяти"
            }
        }

        test("a gzipped dictionary is decoded") {
            val body = """[{"id": 7, "name": "Ноутбуки"}]""".toByteArray()
            val gzipped =
                java.io.ByteArrayOutputStream().use { out ->
                    java.util.zip
                        .GZIPOutputStream(out)
                        .use { it.write(body) }
                    out.toByteArray()
                }

            serving(body = gzipped, contentEncoding = "gzip") { url ->
                val dataSource = dataSourceWith("CREATE TABLE wb_subjects (id INTEGER NOT NULL, name TEXT NOT NULL)")
                val bootstrap = WbSubjectBootstrap(connect(dataSource))
                bootstrap.subjectsUrl = url

                bootstrap.run(DefaultApplicationArguments())

                countSubjects(dataSource) shouldBe 1L
                subjectName(dataSource, 7L) shouldBe "Ноутбуки"
            }
        }

        test("a dictionary that comes back empty is not written") {
            serving(body = "[]".toByteArray()) { url ->
                val dataSource = dataSourceWith("CREATE TABLE wb_subjects (id INTEGER NOT NULL, name TEXT NOT NULL)")
                val bootstrap = WbSubjectBootstrap(connect(dataSource))
                bootstrap.subjectsUrl = url

                bootstrap.run(DefaultApplicationArguments())

                countSubjects(dataSource) shouldBe 0L
            }
        }

        test("a dictionary that cannot be downloaded does not stop the boot") {
            serving(body = "Internal Server Error".toByteArray(), status = 500) { url ->
                val dataSource = dataSourceWith("CREATE TABLE wb_subjects (id INTEGER NOT NULL, name TEXT NOT NULL)")
                val bootstrap = WbSubjectBootstrap(connect(dataSource))
                bootstrap.subjectsUrl = url

                // The contract: no exception escapes. The crawl still works; the
                // products just carry a NULL name next to a real subject id.
                bootstrap.run(DefaultApplicationArguments())

                countSubjects(dataSource) shouldBe 0L
            }
        }

        test("a body that is not a dictionary does not stop the boot") {
            serving(body = "<html>not a dictionary</html>".toByteArray()) { url ->
                val dataSource = dataSourceWith("CREATE TABLE wb_subjects (id INTEGER NOT NULL, name TEXT NOT NULL)")
                val bootstrap = WbSubjectBootstrap(connect(dataSource))
                bootstrap.subjectsUrl = url

                // WB changes the file's shape from time to time. The boot has to
                // survive that, too.
                bootstrap.run(DefaultApplicationArguments())

                countSubjects(dataSource) shouldBe 0L
            }
        }

        test("a dictionary that cannot be written does not stop the boot") {
            serving(body = """[{"id": 1, "name": "Карта памяти"}]""".toByteArray()) { url ->
                // The table is not the one the code writes into: the write fails, and
                // the failure is logged rather than thrown.
                val dataSource =
                    dataSourceWith(
                        "CREATE TABLE wb_subjects (id INTEGER NOT NULL, name TEXT NOT NULL, extra TEXT NOT NULL)",
                    )
                val bootstrap = WbSubjectBootstrap(connect(dataSource))
                bootstrap.subjectsUrl = url

                // The contract: no exception escapes. A deployment whose table is not
                // what the code expects must still serve traffic and still crawl.
                bootstrap.run(DefaultApplicationArguments())
            }
        }
    })

/**
 * A local server that answers the dictionary request, on a real socket.
 *
 * The dictionary is a file on a CDN: what matters about it is that it is HTTP with a
 * body, a status and an encoding — so the server speaks HTTP.
 */
private fun serving(
    body: ByteArray,
    status: Int = 200,
    contentEncoding: String? = null,
    block: (String) -> Unit,
) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        if (contentEncoding != null) exchange.responseHeaders.add("Content-Encoding", contentEncoding)
        if (body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        exchange.close()
    }
    server.start()
    try {
        block("http://127.0.0.1:${server.address.port}/subjects.json")
    } finally {
        server.stop(0)
    }
}

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

private fun subjectName(
    dataSource: DataSource,
    id: Long,
): String? =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT name FROM wb_subjects WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

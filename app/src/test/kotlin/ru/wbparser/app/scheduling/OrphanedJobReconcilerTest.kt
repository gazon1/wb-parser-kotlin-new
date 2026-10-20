package ru.wbparser.app.scheduling

import io.kotest.core.spec.style.FunSpec
import org.springframework.boot.DefaultApplicationArguments
import org.sqlite.SQLiteDataSource
import ru.wbparser.infra.db.connect
import java.nio.file.Files
import javax.sql.DataSource

/**
 * The reconciler marks crawl jobs that were left open by a process that is no
 * longer alive.
 *
 * It runs at startup, next to the application coming up — so a failure here must
 * never stop the boot. A crawler that starts is worth more than a crawler that
 * starts with a tidy job table.
 */
class OrphanedJobReconcilerTest :
    FunSpec({

        test("a database that cannot be reconciled does not stop the boot") {
            // No crawl_jobs table: the reconciliation fails, and the boot continues.
            val dataSource = dataSource()

            val reconciler = OrphanedJobReconciler(connect(dataSource))

            reconciler.run(DefaultApplicationArguments())
        }
    })

/**
 * A real, in-process database with no schema — every statement against it fails.
 */
private fun dataSource(): DataSource {
    val file = Files.createTempFile("orphan-reconciler", ".db")
    file.toFile().deleteOnExit()
    val source = SQLiteDataSource()
    source.url = "jdbc:sqlite:${file.toAbsolutePath()}"
    return source
}

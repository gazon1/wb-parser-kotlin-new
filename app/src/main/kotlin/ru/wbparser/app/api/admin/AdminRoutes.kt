package ru.wbparser.app.api.admin

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.wbparser.domain.scheduling.Target
import ru.wbparser.infra.db.repositories.TargetDraft
import ru.wbparser.infra.db.repositories.deleteTarget
import ru.wbparser.infra.db.repositories.fetchAllTargets
import ru.wbparser.infra.db.repositories.insertTarget
import ru.wbparser.infra.db.repositories.updateTarget
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Admin endpoints — crawl status, errors, jobs, and target management.
 *
 * The target endpoints are the only writable ones: everything else is a read-only
 * view of what the crawler recorded.
 */
@RestController
@RequestMapping("/api/admin")
class AdminRoutes(
    private val datasource: DataSource,
) {
    @GetMapping("/targets")
    fun getTargets(
        @RequestParam(required = false) limit: Int?,
    ): List<Target> = datasource.fetchAllTargets(limit)

    @PostMapping("/targets")
    fun createTarget(
        @RequestBody draft: TargetDraft,
    ): ResponseEntity<Target> {
        val id = datasource.insertTarget(draft)
        val created = datasource.fetchAllTargets().firstOrNull { it.id == id }
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    @PutMapping("/targets/{id}")
    fun updateTarget(
        @PathVariable id: String,
        @RequestBody draft: TargetDraft,
    ): ResponseEntity<Target> {
        val uuid = UUID.fromString(id)
        if (!datasource.updateTarget(uuid, draft)) {
            return ResponseEntity.notFound().build()
        }
        val updated = datasource.fetchAllTargets().firstOrNull { it.id == uuid }
        return ResponseEntity.ok(updated)
    }

    @DeleteMapping("/targets/{id}")
    fun deleteTarget(
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        val uuid = UUID.fromString(id)
        return if (datasource.deleteTarget(uuid)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/errors")
    fun getErrors(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "false") unresolvedOnly: Boolean,
    ): List<RecordedError> = datasource.fetchAdminErrors(limit, unresolvedOnly)

    @GetMapping("/jobs")
    fun getJobs(
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) targetId: String?,
    ): List<Job> = datasource.fetchAdminJobs(limit, targetId)
}

/**
 * A crawl target loaded into the scheduler.
 */
data class RecordedError(
    val id: String,
    val url: String?,
    val message: String,
    val category: String?,
    val isResolved: Boolean,
    val createdAt: Instant,
)

/**
 * A crawl job record.
 */
data class Job(
    val id: String,
    val targetId: String?,
    val status: String,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val pagesCrawled: Int,
    val itemsSaved: Int,
    val errorMessage: String?,
)

/**
 * Fetches admin errors from the database.
 */
fun DataSource.fetchAdminErrors(
    limit: Int,
    unresolvedOnly: Boolean,
): List<RecordedError> {
    val sql =
        if (unresolvedOnly) {
            """
            SELECT id, url, error_message, category, is_resolved, created_at
            FROM crawl_errors WHERE is_resolved = false ORDER BY created_at DESC LIMIT ?
            """.trimIndent()
        } else {
            """
            SELECT id, url, error_message, category, is_resolved, created_at
            FROM crawl_errors ORDER BY created_at DESC LIMIT ?
            """.trimIndent()
        }
    return connection.use { conn ->
        conn.prepareStatement(sql).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toRecordedError()) }
            }
        }
    }
}

private fun ResultSet.toRecordedError(): RecordedError =
    RecordedError(
        id = (getObject("id") as? UUID)?.toString() ?: "",
        url = getString("url"),
        message = getString("error_message") ?: "",
        category = getString("category"),
        isResolved = getBoolean("is_resolved"),
        createdAt = getTimestamp("created_at")?.toInstant() ?: Instant.now(),
    )

/**
 * Fetches admin jobs from the database.
 */
fun DataSource.fetchAdminJobs(
    limit: Int,
    targetId: String?,
): List<Job> {
    val sql =
        if (targetId != null) {
            """
            SELECT id, target_id, status, started_at, completed_at,
                   pages_crawled, items_saved, error_message
            FROM crawl_jobs WHERE target_id = ? ORDER BY started_at DESC LIMIT ?
            """.trimIndent()
        } else {
            """
            SELECT id, target_id, status, started_at, completed_at,
                   pages_crawled, items_saved, error_message
            FROM crawl_jobs ORDER BY started_at DESC LIMIT ?
            """.trimIndent()
        }
    return connection.use { conn ->
        conn.prepareStatement(sql).use { ps ->
            if (targetId != null) {
                ps.setObject(1, UUID.fromString(targetId))
                ps.setInt(2, limit)
            } else {
                ps.setInt(1, limit)
            }
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toAdminJob()) }
            }
        }
    }
}

private fun ResultSet.toAdminJob(): Job =
    Job(
        id = (getObject("id") as? UUID)?.toString() ?: "",
        targetId = (getObject("target_id") as? UUID)?.toString(),
        status = getString("status") ?: "",
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
        pagesCrawled = getInt("pages_crawled"),
        itemsSaved = getInt("items_saved"),
        errorMessage = getString("error_message"),
    )

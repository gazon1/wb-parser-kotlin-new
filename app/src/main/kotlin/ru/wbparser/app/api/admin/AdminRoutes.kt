package ru.wbparser.app.api.admin

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.support.CronExpression
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.domain.scheduling.Target
import ru.wbparser.infra.db.repositories.TargetDraft
import ru.wbparser.infra.db.repositories.TargetRun
import ru.wbparser.infra.db.repositories.claimMoment
import ru.wbparser.infra.db.repositories.deleteTarget
import ru.wbparser.infra.db.repositories.fetchAllTargets
import ru.wbparser.infra.db.repositories.fetchTargetRuns
import ru.wbparser.infra.db.repositories.fetchTargetsByIds
import ru.wbparser.infra.db.repositories.insertTarget
import ru.wbparser.infra.db.repositories.updateTarget
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
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
    private val crawlProperties: CrawlProperties,
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

    /**
     * The runs of the targets: who served them, and how they went.
     *
     * The table has been written on every run since it was created, and until now
     * nothing outside the tests read it — the question it exists for ("which address
     * gave the refusal?") was answerable only from the logs, and the logs do not last.
     *
     * The target id is a [UUID] parameter, so a malformed one is a bad request and not
     * a failure of the server.
     */
    @GetMapping("/target-runs")
    fun getTargetRuns(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false) targetId: UUID?,
    ): List<TargetRun> = datasource.fetchTargetRuns(limit, targetId)

    /**
     * The schedule's answer: the tick, the freshness window, what is due, and what is coming.
     *
     * The rule is not re-implemented here but taken from the side that owns it — the same
     * conditions the claim uses (`claimDueTargets`) and the same freshness window
     * ([CrawlProperties.Schedule]) — so the operator and the scheduler answer the same
     * question from the same place. Before this endpoint the operator's terminal carried a
     * copy of the rule; a copy is two chances to disagree, and the copy already disagreed
     * about the lease.
     */
    @GetMapping("/schedule")
    fun getSchedule(
        @RequestParam(defaultValue = "15") limit: Int,
    ): ScheduleResponse {
        val now = Instant.now()
        val snapshot = datasource.fetchSchedule(now, crawlProperties.schedule.freshnessMinutes, limit)
        return ScheduleResponse(
            cron = crawlProperties.schedule.cron,
            freshnessMinutes = crawlProperties.schedule.freshnessMinutes,
            targets = datasource.fetchAllTargets(),
            due = snapshot.due,
            upcoming = snapshot.upcoming,
            nextRun = nextTick(crawlProperties.schedule.cron, now, snapshot.earliestDue),
            earliestDue = snapshot.earliestDue,
            limit = limit,
        )
    }
}

/**
 * A target the schedule will crawl once its moment comes.
 */
data class Upcoming(
    val at: Instant,
    val target: Target,
)

/**
 * The schedule's answer: the tick, the freshness window, what is due, and what is coming.
 */
data class ScheduleResponse(
    val cron: String,
    val freshnessMinutes: Long,
    val targets: List<Target>,
    val due: List<Target>,
    val upcoming: List<Upcoming>,
    val nextRun: Instant?,
    val earliestDue: Instant?,
    val limit: Int,
) {
    val activeTargets: Int get() = targets.count { it.isActive }
}

/**
 * The due slice and the coming slice of one schedule tick, plus the earliest moment a
 * run could have started. The due slice is what the claim takes; the coming slice is what
 * is next.
 */
data class ScheduleSnapshot(
    val due: List<Target>,
    val upcoming: List<Upcoming>,
    val earliestDue: Instant?,
)

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
    /**
     * What started the run: `schedule` or `manual`, or null for rows written before the
     * trigger was recorded.
     */
    val triggeredBy: String?,
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

private fun ResultSet.readUuid(column: String): UUID =
    when (val value = getObject(column)) {
        is UUID -> value
        is String -> UUID.fromString(value)
        is ByteArray ->
            java.nio.ByteBuffer
                .wrap(value)
                .let { UUID(it.long, it.long) }
        else -> UUID.fromString(getString(column))
    }

/**
 * The schedule's own question: which targets are due now, and which are coming.
 *
 * The due slice carries the claim's conditions — active, nothing pending, no live lease —
 * so a target held by an interrupted run's lease is not due, and the operator sees the
 * same answer the tick takes. The freshness window is the server's own, passed in by the
 * caller from [CrawlProperties].
 */
fun DataSource.fetchSchedule(
    now: Instant,
    freshnessMinutes: Long,
    limit: Int,
): ScheduleSnapshot {
    val freshBefore = now.minus(Duration.ofMinutes(freshnessMinutes))
    val dueIds =
        connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT id
                      FROM crawl_targets
                     WHERE is_active
                       AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                       AND (last_crawled_at IS NULL OR last_crawled_at <= ?)
                       AND (lease_until IS NULL OR lease_until <= ?)
                     ORDER BY last_crawled_at ASC NULLS FIRST, id
                     LIMIT ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, now.atOffset(ZoneOffset.UTC))
                    ps.setObject(2, freshBefore.atOffset(ZoneOffset.UTC))
                    ps.setObject(3, now.atOffset(ZoneOffset.UTC))
                    ps.setInt(4, limit)
                    ps.executeQuery().use { rs ->
                        val ids = mutableListOf<UUID>()
                        while (rs.next()) {
                            ids += rs.readUuid("id")
                        }
                        ids
                    }
                }
        }
    val due = if (dueIds.isEmpty()) emptyList() else fetchTargetsByIds(dueIds)
    val dueIdSet = dueIds.toSet()
    val allTargets = fetchAllTargets()
    val upcoming =
        allTargets
            .filter { it.isActive && it.id !in dueIdSet }
            .mapNotNull { target ->
                val claimAt = claimMoment(target.nextAttemptAt, target.lastCrawledAt, freshnessMinutes, target.leaseUntil, now)
                if (claimAt > now) {
                    claimAt to target
                } else {
                    null
                }
            }.sortedBy { it.first }
            .take(limit)
            .map { Upcoming(at = it.first, target = it.second) }
    val earliestDue: Instant? =
        allTargets
            .filter { it.isActive }
            .map { target -> claimMoment(target.nextAttemptAt, target.lastCrawledAt, freshnessMinutes, target.leaseUntil, now) }
            .minOrNull()
    return ScheduleSnapshot(due = due, upcoming = upcoming, earliestDue = earliestDue)
}

/**
 * The next tick after [now], or after [earliestDue] when that is later: a tick that finds
 * nothing due starts nothing, so the promise of a run has to be the later of the two.
 */
internal fun nextTick(
    cron: String,
    now: Instant,
    earliestDue: Instant?,
): Instant? {
    val generator = CronExpression.parse(cron)
    val next = generator.next(LocalDateTime.ofInstant(now, ZoneOffset.UTC)) ?: return null
    val nextInstant = next.atOffset(ZoneOffset.UTC).toInstant()
    if (earliestDue == null || earliestDue <= nextInstant) {
        return nextInstant
    }
    val after = generator.next(LocalDateTime.ofInstant(earliestDue, ZoneOffset.UTC))
    return if (after == null) {
        null
    } else {
        after.atOffset(ZoneOffset.UTC).toInstant()
    }
}

/**
 * Fetches admin jobs from the database.
 *
 * The order is newest first, and a row whose start was never recorded comes last: the
 * column is nullable, and PostgreSQL puts NULL first for a `DESC` order by default — so a
 * row without a start would answer "the newest run" and the operator would read a job
 * that never started. `NULLS LAST` keeps the newest *started* run first, and the caller
 * that takes `limit = 1` gets the run it means.
 */
fun DataSource.fetchAdminJobs(
    limit: Int,
    targetId: String?,
): List<Job> {
    val sql =
        if (targetId != null) {
            """
            SELECT id, target_id, status, started_at, completed_at,
                   pages_crawled, items_saved, triggered_by, error_message
            FROM crawl_jobs WHERE target_id = ? ORDER BY started_at DESC NULLS LAST LIMIT ?
            """.trimIndent()
        } else {
            """
            SELECT id, target_id, status, started_at, completed_at,
                   pages_crawled, items_saved, triggered_by, error_message
            FROM crawl_jobs ORDER BY started_at DESC NULLS LAST LIMIT ?
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
        triggeredBy = getString("triggered_by"),
        errorMessage = getString("error_message"),
    )

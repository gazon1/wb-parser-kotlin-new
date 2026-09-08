package ru.wbparser.infra.runner

import arrow.core.Either
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.BusinessRules
import ru.wbparser.domain.pipeline.StopReason
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.pipeline.dropIfInvalid
import ru.wbparser.domain.scheduling.Target
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.infra.advisory.LockUnavailable
import ru.wbparser.infra.advisory.PostgresAdvisoryLock
import ru.wbparser.infra.advisory.withLock
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.connect
import ru.wbparser.infra.db.repositories.fetchActiveTargets
import ru.wbparser.infra.pipeline.CrawlStats
import ru.wbparser.infra.pipeline.Crawled
import ru.wbparser.infra.pipeline.Session
import ru.wbparser.infra.pipeline.crawlPipeline
import ru.wbparser.infra.scheduler.Freshness
import ru.wbparser.infra.scheduler.FreshnessPolicy
import ru.wbparser.infra.scheduler.markCrawled
import ru.wbparser.infra.scheduler.targetsDue
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * CrawlRunner: coordinates advisory lock → load targets → run pipeline → job row.
 */
class CrawlRunner(
    private val downloader: suspend (Crawling) -> Either<NetworkError, Fetched>,
    private val parser: (suspend (Fetched) -> Either<StageFailure, ru.wbparser.domain.model.ParsedPage>)?,
    private val onSave: suspend (List<SavedItem>, Long) -> Unit,
    private val onError: suspend (Exception) -> Unit,
) {
    private val db: DatabaseHandle = connect()
    private val advisoryLock = PostgresAdvisoryLock(db.ds)
    private val freshnessPolicy = FreshnessPolicy()
    private var freshnessState = Freshness()

    suspend fun run(): RunResult = withContext(Dispatchers.IO) {
        val lockResult: Either<LockUnavailable, RunResult> = advisoryLock.withLock {
            runUnsafe()
        }
        when (lockResult) {
            is Either.Left -> {
                val lu = lockResult.value
                if (lu.message.contains("timed out")) {
                    RunResult.Failure(lu.message)
                } else {
                    RunResult.AlreadyRunning
                }
            }
            is Either.Right -> lockResult.value
        }
    }

    private suspend fun runUnsafe(): RunResult {
        val jobId = openJob()
        return try {
            val targets = db.ds.fetchActiveTargets()
            val due = freshnessState.targetsDue(targets, freshnessPolicy)

            var totalPages = 0
            var totalItems = 0

            for (target in due) {
                val result = runTarget(target)
                totalPages += result.pages
                totalItems += result.items
                freshnessState = freshnessState.markCrawled(target.id)
            }

            closeJob(jobId, "Completed", pagesCrawled = totalPages, itemsSaved = totalItems)
            RunResult.Success(totalPages, totalItems)
        } catch (e: Exception) {
            onError(e)
            closeJob(jobId, "Failed", errorMessage = e.message)
            RunResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun runTarget(target: Target): TargetResult {
        var pages = 0
        var items = 0

        val startUrl = CrawlUrl.of(target.url).fold(
            ifLeft = {
                CrawlUrl.of("https://wildberries.ru").fold(
                    ifLeft = { throw IllegalStateException("Invalid fallback URL") },
                    ifRight = { it },
                )
            },
            ifRight = { it },
        )

        val startTask = Crawling(
            id = UUID.randomUUID().toString(),
            url = startUrl,
            depth = 0,
            targetId = target.id,
        )

        val session = Session(
            jobId = 0L,
            targetId = target.id,
            tasks = listOf(startTask),
            maxConcurrent = 3,
        )

        val pipeline = crawlPipeline {
            download { task -> downloader(task) }
            if (parser != null) {
                parse { fetched -> parser.invoke(fetched) }
            }
            enrich { item -> item.toSavedItem() }
            validate { item ->
                val rules = BusinessRules()
                item.dropIfInvalid(rules)
            }
            save { savedItems -> onSave(savedItems, target.id) }
            stopWhen { _, pageCount -> null }
            concurrency(3)
            stats(CrawlStats())
        }

        val result = pipeline.run(session)
        result.fold(
            ifRight = { crawled ->
                pages = crawled.pagesCrawled
                items = crawled.itemsSaved
            },
            ifLeft = { err ->
                onError(Exception(err.message))
            },
        )

        return TargetResult(pages, items)
    }

    private fun openJob(): UUID {
        val id = UUID.randomUUID()
        db.ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO crawl_jobs (id, target_id, status, started_at, created_at)
                VALUES (?, '00000000-0000-0000-0000-000000000001', 'Running', ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()))
                ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()))
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun closeJob(
        id: UUID,
        status: String,
        pagesCrawled: Int = 0,
        itemsSaved: Int = 0,
        errorMessage: String? = null,
    ) {
        db.ds.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE crawl_jobs
                SET status = ?, completed_at = ?, pages_crawled = ?, items_saved = ?, error_message = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, status)
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()))
                ps.setInt(3, pagesCrawled)
                ps.setInt(4, itemsSaved)
                ps.setString(5, errorMessage)
                ps.setObject(6, id)
                ps.executeUpdate()
            }
        }
    }

    sealed class RunResult {
        data class Success(val pagesCrawled: Int, val itemsSaved: Int) : RunResult()
        data class Failure(val message: String) : RunResult()
        data object AlreadyRunning : RunResult()
    }

    private data class TargetResult(val pages: Int, val items: Int)
}

private fun ParsedItem.toSavedItem(): SavedItem {
    val now = Instant.now().toString()
    return SavedItem(
        productId = productId.value,
        name = name,
        priceKopecks = priceKopecks,
        salePriceKopecks = salePriceKopecks,
        cashback = cashback,
        brand = brand,
        category = category,
        categoryId = null,
        imageUrl = imageUrl,
        pageUrl = pageUrl.toString(),
        targetId = 0L,
        brandId = brandId,
        subjectId = subjectId,
        supplierId = supplierId,
        inStock = inStock,
        contentHash = "${productId.value}:$name:$priceKopecks",
        createdAt = now,
        updatedAt = now,
    )
}

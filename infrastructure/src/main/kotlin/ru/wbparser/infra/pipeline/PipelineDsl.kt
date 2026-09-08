package ru.wbparser.infra.pipeline

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.BusinessRules
import ru.wbparser.domain.pipeline.Dropped
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.pipeline.StopReason
import ru.wbparser.domain.pipeline.dropIfInvalid
import ru.wbparser.domain.pipeline.shouldRetry

/**
 * DSL marker for crawl pipeline blocks.
 */
@DslMarker
annotation class CrawlDsl

/**
 * Builder for crawl pipelines using DSL style.
 */
@CrawlDsl
class PipelineBuilder {
    private var _download: DownloadStage? = null
    private var _parse: ParseStage? = null
    private var _validate: ValidateStage? = null
    private var _enrich: EnrichStage? = null
    private var _save: SaveStage? = null
    private var _stopWhen: (Crawling, Int) -> StopReason? = { _, _ -> null }
    private var _concurrency: Int = 3
    private var _batchSize: Int = 100
    private var _dedup: ((String) -> Boolean)? = null
    private var _stats: CrawlStats = CrawlStats()
    private var _businessRules: BusinessRules = BusinessRules()
    private var _retryPolicy: RetryPolicy = RetryPolicy()

    fun download(block: suspend (Crawling) -> Either<NetworkError, Fetched>) {
        _download = DownloadStage(block)
    }

    fun parse(block: suspend (Fetched) -> Either<StageFailure, ParsedPage>) {
        _parse = ParseStage(block)
    }

    fun validate(block: suspend (ParsedItem) -> Dropped?) {
        _validate = ValidateStage(block)
    }

    fun enrich(block: suspend (ParsedItem) -> SavedItem) {
        _enrich = EnrichStage(block)
    }

    fun save(block: suspend (List<SavedItem>) -> Unit) {
        _save = SaveStage(block)
    }

    fun stopWhen(block: (Crawling, Int) -> StopReason?) {
        _stopWhen = block
    }

    fun concurrency(n: Int) { _concurrency = n }
    fun batchSize(n: Int) { _batchSize = n }
    fun dedup(check: (String) -> Boolean) { _dedup = check }
    fun stats(s: CrawlStats) { _stats = s }
    fun businessRules(rules: BusinessRules) { _businessRules = rules }
    fun retryPolicy(policy: RetryPolicy) { _retryPolicy = policy }

    fun build(): Pipeline = Pipeline(
        download = _download ?: throw IllegalStateException("download stage is required"),
        parse = _parse ?: throw IllegalStateException("parse stage is required"),
        validate = _validate ?: ValidateStage { null },
        enrich = _enrich ?: EnrichStage { item -> item.toSavedItem() },
        save = _save ?: SaveStage { },
        stopWhen = _stopWhen,
        concurrency = _concurrency,
        batchSize = _batchSize,
        dedup = _dedup,
        stats = _stats,
        businessRules = _businessRules,
        retryPolicy = _retryPolicy,
    )
}

/**
 * Stage signatures — each returns Either so failures are explicit.
 */
fun interface DownloadStage {
    suspend operator fun invoke(task: Crawling): Either<NetworkError, Fetched>
}

fun interface ParseStage {
    suspend operator fun invoke(fetched: Fetched): Either<StageFailure, ParsedPage>
}

fun interface ValidateStage {
    suspend operator fun invoke(item: ParsedItem): Dropped?
}

fun interface EnrichStage {
    suspend operator fun invoke(item: ParsedItem): SavedItem
}

fun interface SaveStage {
    suspend operator fun invoke(items: List<SavedItem>)
}

/**
 * Pipeline data class holding all configured stages and settings.
 */
data class Pipeline(
    val download: DownloadStage,
    val parse: ParseStage,
    val validate: ValidateStage,
    val enrich: EnrichStage,
    val save: SaveStage,
    val stopWhen: (Crawling, Int) -> StopReason?,
    val concurrency: Int,
    val batchSize: Int,
    val dedup: ((String) -> Boolean)?,
    val stats: CrawlStats,
    val businessRules: BusinessRules,
    val retryPolicy: RetryPolicy,
) {
    /**
     * Run the pipeline for a given session.
     * Returns Either<DomainError, Crawled> with final crawl results.
     */
    suspend fun run(session: Session): Either<DomainError, Crawled> = either {
        val startTime = System.currentTimeMillis()
        var pageCount = 0
        var itemCount = 0
        var stopReason: StopReason? = null

        val taskFlow: Flow<Crawling> = flow {
            for (task in session.tasks) {
                if (stopReason != null) break
                val urlStr = task.url.toString()
                if (dedup != null && dedup!!(urlStr) == false) continue
                val reason = stopWhen(task, pageCount)
                if (reason != null) {
                    stopReason = reason
                    break
                }
                emit(task)
            }
        }

        // Download flow: flatMapMerge over tasks → Either<NetworkError, Fetched>
        val fetchedFlow: Flow<Either<NetworkError, Fetched>> = taskFlow.flatMapMerge(concurrency) { task ->
            flow {
                val result = retryDownload(task, download, retryPolicy)
                emit(result)
            }
        }

        // Parse flow: for each fetched, check success and parse
        val pageFlow: Flow<Either<StageFailure, ParsedPage>> = fetchedFlow.flatMapMerge(concurrency) { fetchedEither ->
            flow {
                fetchedEither.fold(
                    ifLeft = { err -> emit(Either.Left(StageFailure.Network(err.message, err.url, err.cause))) },
                    ifRight = { fetched ->
                        stats.recordDownload(fetched.durationMs)
                        if (!fetched.isSuccess) {
                            if (fetched.isRetryable) stats.recordError()
                            emit(Either.Left(StageFailure.DownloadFailure(
                                fetched.statusCode.value, fetched.task.url.toString())))
                        } else {
                            val pageResult = retryParse(fetched, parse, retryPolicy)
                            emit(pageResult)
                        }
                    },
                )
            }
        }

        pageFlow.collect { pageEither ->
            pageEither.fold(
                ifLeft = { failure ->
                    stats.recordError()
                },
                ifRight = { page ->
                    stats.recordParse(System.currentTimeMillis() - startTime)
                    pageCount++
                    if (page.isEmptyPage) {
                        stopReason = StopReason.EmptyPage
                        return@collect
                    }
                    val validItems = page.items.mapNotNull { item ->
                        val dropReason = item.dropIfInvalid(businessRules)
                        if (dropReason != null) {
                            stats.recordDrop(dropReason)
                            null
                        } else {
                            try {
                                enrich.invoke(item)
                            } catch (e: Exception) {
                                stats.recordError()
                                null
                            }
                        }
                    }
                    if (validItems.isNotEmpty()) {
                        save.invoke(validItems)
                        stats.recordSaved(validItems.size)
                        itemCount += validItems.size
                    }
                },
            )
        }

        Crawled(
            pagesCrawled = pageCount,
            itemsSaved = itemCount,
            stopReason = stopReason ?: if (pageCount > 0) StopReason.NoNextPage else StopReason.ManualStop,
            durationMs = System.currentTimeMillis() - startTime,
        )
    }

    private suspend fun retryDownload(
        task: Crawling,
        stage: DownloadStage,
        policy: RetryPolicy,
    ): Either<NetworkError, Fetched> {
        var attempt = 0
        while (true) {
            val result = stage.invoke(task)
            result.fold(
                ifLeft = { /* successful error result — don't retry the error itself */ return result },
                ifRight = { fetched ->
                    if (fetched.isSuccess || !fetched.isRetryable) return result
                },
            )
            // Retry on failure
            attempt++
            if (!shouldRetry(attempt, policy)) {
                return Either.Left(NetworkError(
                    "Retry exhausted after $attempt attempts",
                    null,
                    task.url.toString(),
                ))
            }
        }
    }

    private suspend fun retryParse(
        fetched: Fetched,
        stage: ParseStage,
        policy: RetryPolicy,
    ): Either<StageFailure, ParsedPage> {
        var attempt = 0
        while (true) {
            val result = stage.invoke(fetched)
            result.fold(
                ifLeft = { failure ->
                    if (!failure.isRetryable) return result
                },
                ifRight = { return result },
            )
            attempt++
            if (!shouldRetry(attempt, policy)) return result
        }
    }
}

/**
 * Result of a crawl run.
 */
data class Crawled(
    val pagesCrawled: Int,
    val itemsSaved: Int,
    val stopReason: StopReason,
    val durationMs: Long,
)

/**
 * DSL entry point.
 */
fun crawlPipeline(block: PipelineBuilder.() -> Unit): Pipeline {
    return PipelineBuilder().apply(block).build()
}

/**
 * Convert a [ParsedItem] to [SavedItem].
 */
private fun ParsedItem.toSavedItem(): SavedItem = SavedItem(
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
    createdAt = java.time.Instant.now().toString(),
    updatedAt = java.time.Instant.now().toString(),
)

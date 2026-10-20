package ru.wbparser.infra.runner

import arrow.core.Either
import arrow.core.getOrElse
import kotlinx.coroutines.CoroutineExceptionHandler
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.scheduling.Target
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.repositories.SubjectNameLookup
import ru.wbparser.infra.db.repositories.upsertSavedItems
import ru.wbparser.infra.http.WbCatalogRequest
import ru.wbparser.infra.pipeline.LogDropInterpreter
import ru.wbparser.infra.pipeline.LogInterpreter
import ru.wbparser.infra.pipeline.ParserPipelineConfig
import ru.wbparser.infra.pipeline.PipelineRunner
import ru.wbparser.infra.pipeline.SideInterpreterRegistry
import ru.wbparser.infra.pipeline.buildParserPipeline
import java.util.UUID
import ru.wbparser.domain.pipeline.Pipeline as DomainPipeline

/**
 * Outcome of crawling **one** target — not of the whole run.
 *
 * [error] is non-null when this target's pipeline returned a
 * [ru.wbparser.domain.error.DomainError]. A run aggregates the per-target errors into a single
 * job status; keeping the error here rather than throwing it is what lets one failing catalog
 * not abort the others.
 */
internal data class TargetResult(
    val pages: Int,
    val items: Int,
    val error: String? = null,
)

/**
 * Crawls one target: builds its pipeline, runs it, and reports what came out.
 *
 * Everything target-specific lives here — which URL the crawl starts at, which pipeline the
 * target gets, and what the target produced. [CrawlRunner] does not need to know any of it,
 * which is what keeps the runner's own loop readable.
 *
 * @param db where the saved items go.
 * @param source the downloader and the parser.
 * @param config the crawl's limits and rules.
 * @param metrics where the target's counters go.
 * @param failureHandler the handler for unhandled background coroutine failures.
 */
internal class TargetCrawl(
    private val db: DatabaseHandle,
    private val source: CrawlSource,
    private val config: CrawlRunnerConfig,
    private val metrics: CrawlMetrics,
    private val failureHandler: CoroutineExceptionHandler,
) {
    /**
     * WB's product-subject dictionary, read from `wb_subjects` on first use.
     *
     * The catalog API sends ids only; the names come from the dictionary. It is
     * read once per runner rather than once per saved batch, and a deployment
     * that has not loaded it writes ids without names.
     */
    private val subjectNames = SubjectNameLookup(db.ds)

    /**
     * Runs crawling for a single target.
     *
     * Constructs a per-target [DomainPipeline] so that [targetId] is correctly
     * captured in the enrich stage closure.
     *
     * @param startTaskIdGen ID generator for the initial [Crawling] task.
     *                        Defaults to [UUID.randomUUID] — override in tests for determinism.
     */
    suspend fun run(
        target: Target,
        startTaskIdGen: () -> String = { UUID.randomUUID().toString() },
    ): TargetResult {
        val targetId = target.id

        // How many pages of this target to crawl. The target's own
        // `known_pages_limit` wins; the runner-wide cap is the fallback.
        val pageLimit = target.knownPagesLimit ?: config.maxPagesPerCatalog

        val pipeline: DomainPipeline =
            buildParserPipeline(
                downloader = source.downloader,
                parser = source.parser,
                save = { items: List<SavedItem> ->
                    when {
                        items.isEmpty() -> Step.Done(Unit)
                        else ->
                            try {
                                // The real crawl_targets.id — scraped_items.target_id is a foreign key,
                                // so a generated UUID would reject every row.
                                val names = subjectNames.snapshot()
                                db.ds.upsertSavedItems(items, targetId, names)
                                val withoutName =
                                    items.count { it.subjectId != null && !names.containsKey(it.subjectId) }
                                if (withoutName > 0) {
                                    metrics.recordItemsWithoutCategoryName(withoutName)
                                }
                                Step.Done(Unit)
                            } catch (_: java.sql.SQLException) {
                                // Transient storage failure. Hand it to the save-stage retry loop:
                                // previously the exception escaped and took the whole page with it.
                                Step.Retry(Retry.Database())
                            }
                    }
                },
                targetId = targetId,
                config =
                    ParserPipelineConfig(
                        rules = config.rules,
                        retryPolicy = config.retryPolicy,
                        stopAt = { pages, depth ->
                            when {
                                // The target's own limit wins when it has one: `known_pages_limit`
                                // says how many pages of this category are worth crawling, and the
                                // runner-wide cap is only the fallback for a target that does not
                                // say.
                                pages >= pageLimit -> Stop.MaxPagesReached
                                depth >= config.maxDepth -> Stop.MaxDepthReached(config.maxDepth, depth)
                                else -> null
                            }
                        },
                        clock = config.clock,
                        idGen = startTaskIdGen,
                    ),
            )

        val registry =
            SideInterpreterRegistry(
                log = LogInterpreter(),
                metric = metrics.metricInterpreter(),
                drop = LogDropInterpreter(),
            )
        val runner = PipelineRunner(pipeline, registry, failureHandler)

        val startUrl =
            startUrlOf(target) ?: return TargetResult(
                pages = 0,
                items = 0,
                error = missingTokenError(target),
            )

        val startTask =
            Crawling(
                id = startTaskIdGen(),
                url = startUrl,
                depth = 0,
                targetId = targetId,
            )

        val result = runner.run(listOf(startTask), concurrency = config.concurrency)

        return when (result) {
            // The DomainError used to be dropped here, which let runUnsafe close the job
            // as "Completed" with a NULL error_message. A failed crawl has to say so.
            is Either.Left -> {
                @Suppress("USELESS_ELVIS") // DomainError.message can be null; fallback is a safety net
                TargetResult(0, 0, error = result.value.message ?: "crawl failed")
            }
            is Either.Right -> {
                val crawled: ru.wbparser.domain.pipeline.Crawled = result.value
                metrics.record(target, crawled.pagesCrawled, crawled.itemsSaved, crawled.stopReason)
                TargetResult(crawled.pagesCrawled, crawled.itemsSaved)
            }
        }
    }

    /**
     * The URL the crawl of [target] starts at.
     *
     * A target configured with a WB catalog ID is served through the internal catalog API,
     * whose entry point is the catalog page URL. The crawl URL carries WB's catalog search
     * token, because that token - not the catalog id - is what selects the category in the
     * API. Falling back to target.url keeps targets that carry a plain start URL working
     * unchanged.
     *
     * Returns null when the target has a catalog ID but no token: without the token WB
     * answers with its rotating popular list — a page of products unrelated to the category,
     * stored under the category's name. Failing the target is the honest outcome.
     */
    private fun startUrlOf(target: Target): CrawlUrl? {
        val startUrlString =
            target.wbCatalogId?.let { catalogId ->
                val searchQuery = target.wbSearchQuery
                if (searchQuery.isNullOrBlank()) return null
                WbCatalogRequest.crawlUrlOf(catalogId, searchQuery)
            } ?: target.url
        return CrawlUrl.of(startUrlString).getOrElse {
            throw IllegalArgumentException("Invalid target URL: $startUrlString")
        }
    }

    /**
     * Why the target cannot be crawled: it has a catalog ID but no WB search token.
     */
    private fun missingTokenError(target: Target): String {
        val catalogId = target.wbCatalogId ?: return "Target ${target.name} has no start URL"
        return "Catalog $catalogId has no WB search token (wb_search_query) - refusing to crawl"
    }
}

package ru.wbparser.domain.pipeline

/**
 * Identifies a step in the crawl pipeline.
 */
@JvmInline
value class StepId(val value: String) {
    companion object {
        val Download = StepId("download")
        val Parse = StepId("parse")
        val Filter = StepId("filter")
        val Enrich = StepId("enrich")
        val Save = StepId("save")
        val Skip = StepId("skip")
    }
}

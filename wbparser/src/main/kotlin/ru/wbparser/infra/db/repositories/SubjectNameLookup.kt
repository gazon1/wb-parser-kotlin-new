package ru.wbparser.infra.db.repositories

import javax.sql.DataSource

/**
 * The names of WB's product subjects, read once and kept.
 *
 * ## Why the names are looked up instead of invented
 *
 * The catalog API sends each product's place in the tree as ids: `subjectId`
 * and `subjectParentId`. It sends no names. The names exist — WB publishes a
 * dictionary of them — but they are not in the response, so a crawler that
 * wants a name has to fetch the dictionary, and a crawler that does not has to
 * leave the name columns null.
 *
 * ## The dictionary is read once
 *
 * The dictionary holds a few thousand rows and does not change while a crawl
 * runs. Reading it per saved batch would put a full table read in front of
 * every batch; reading it once per process puts it in front of the first batch
 * and nowhere else.
 *
 * An empty dictionary is a normal state, not a failure: a deployment that has
 * not loaded it yet writes ids without names.
 */
class SubjectNameLookup(
    private val dataSource: DataSource,
) {
    private val names: Map<Long, String> by lazy { dataSource.fetchSubjectNames() }

    /** The dictionary, loaded on first use. */
    fun snapshot(): Map<Long, String> = names

    /** The name of [subjectId], or null when the dictionary does not know it. */
    fun nameOf(subjectId: Long?): String? = subjectId?.let { names[it] }
}

package ru.wbparser.infra.db.repositories

import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import ru.wbparser.domain.pipeline.WbSubjectsTable
import ru.wbparser.infra.db.exposedOnce
import javax.sql.DataSource

/**
 * Reads WB's product-subject dictionary: `subjectId` → the name that id stands for.
 *
 * The WB catalog API sends ids only (`subjectId`, `subjectParentId`); the names
 * live in a dictionary WB publishes separately and that the crawler stores in
 * `wb_subjects`. Without it every scraped row carried an id with no name next
 * to it.
 *
 * The table is a reference table and may legitimately be empty — a deployment
 * that has not loaded the dictionary yet gets an empty map, and the write path
 * leaves the name columns null. An empty map and a missing table mean the same
 * thing here, so the read must not fail when the dictionary is absent.
 */
fun DataSource.fetchSubjectNames(): Map<Long, String> {
    exposedOnce()
    return transaction {
        WbSubjectsTable
            .selectAll()
            .associate { row -> row[WbSubjectsTable.id] to row[WbSubjectsTable.name] }
    }
}

/**
 * How many subjects the dictionary holds.
 *
 * Used by the startup bootstrap to decide whether the dictionary has to be fetched.
 */
fun DataSource.countSubjects(): Long = queryLong("SELECT count(*) FROM wb_subjects")

/**
 * How many crawl targets carry no WB search token.
 *
 * A target without a token cannot be crawled: the internal API would answer with WB's
 * rotating popular list instead of the category. The crawler refuses such targets, so
 * the number is worth surfacing — it is the difference between "no data" and "data in
 * the wrong categories".
 */
fun DataSource.countTargetsWithoutSearchToken(): Long =
    queryLong("SELECT count(*) FROM crawl_targets WHERE wb_search_query IS NULL OR wb_search_query = ''")

/**
 * Writes the subject dictionary, replacing what is there.
 *
 * The whole dictionary is rewritten rather than merged: WB renames and retires subjects,
 * and a merge would keep the retired ones forever. [subjects] is a `subjectId → name` map,
 * which already collapses WB's duplicate rows for the same id.
 *
 * Plain JDBC on purpose: the dictionary is written once, at startup, and a batch insert
 * over thousands of rows is a statement the write path does not need to be clever about.
 */
fun DataSource.upsertSubjects(subjects: Map<Long, String>) {
    connection.use { conn ->
        conn.autoCommit = false
        try {
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM wb_subjects")
            }
            conn.prepareStatement("INSERT INTO wb_subjects (id, name) VALUES (?, ?)").use { ps ->
                subjects.forEach { (id, name) ->
                    ps.setLong(1, id)
                    ps.setString(2, name)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }
}

private fun DataSource.queryLong(sql: String): Long =
    connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

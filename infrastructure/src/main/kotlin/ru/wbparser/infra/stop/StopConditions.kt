package ru.wbparser.infra.stop

import ru.wbparser.domain.model.Crawling

/**
 * Stop predicate: returns true if crawling should stop.
 */
typealias StopPredicate = (Crawling, Int) -> Boolean

/**
 * Stop after crawling the specified maximum number of pages.
 */
fun stopAfterMaxPages(limit: Int): StopPredicate = { _, pageCount ->
    pageCount >= limit
}

/**
 * Stop after reaching the specified depth.
 */
fun stopAfterMaxDepth(depth: Int): StopPredicate = { task, _ ->
    task.depth >= depth
}

/**
 * Combines multiple predicates with AND logic — all must return false to continue.
 */
fun predicatesAll(vararg predicates: StopPredicate): StopPredicate = { task, pageCount ->
    predicates.all { it(task, pageCount) }
}

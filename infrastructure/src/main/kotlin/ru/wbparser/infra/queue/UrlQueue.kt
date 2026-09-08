package ru.wbparser.infra.queue

import arrow.core.Either
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import ru.wbparser.domain.model.Crawling
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe URL queue for crawl tasks.
 * Uses a Channel for back-pressure and a ConcurrentHashSet for dedup.
 */
data class UrlQueue(
    val capacity: Int = 10_000,
) {
    private val channel = Channel<Crawling>(Channel.UNLIMITED)
    private val seen = ConcurrentHashMap.newKeySet<String>()

    val flow: Flow<Crawling> = channel.receiveAsFlow()

    /**
     * Offers a task to the queue.
     * Returns Either.Right(true) if the task is new and was offered.
     * Returns Either.Left(Duplicate) if the URL has already been seen.
     */
    suspend fun offer(task: Crawling): Either<Duplicate, Boolean> {
        val url = task.url.toString()
        if (seen.add(url)) {
            channel.send(task)
            return Either.Right(true)
        }
        return Either.Left(Duplicate(url))
    }

    /**
     * Closes the channel.
     */
    suspend fun close() = channel.close()

    /**
     * Returns the number of seen URLs.
     */
    fun size(): Int = seen.size
}

/**
 * Indicates a URL was already present in the queue.
 */
data class Duplicate(
    val url: String,
)

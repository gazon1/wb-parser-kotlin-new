package ru.wbparser.infra.http

import kotlinx.coroutines.delay
import java.util.concurrent.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * Domain-level rate limiter using semaphore + delay.
 * Limits concurrent requests per domain to avoid triggering antibot.
 */
data class DomainRateLimiter(
    val maxConcurrentPerDomain: Int = 3,
    val minDelayMs: Long = 500,
) {
    val semaphores = ConcurrentHashMap<String, Semaphore>()
    val lastRequestTime = ConcurrentHashMap<String, Long>()
}

/**
 * Acquires permit for the given domain, blocking if necessary.
 * Also enforces minimum delay between requests to the same domain.
 */
suspend fun <T> DomainRateLimiter.run(domain: String, block: suspend () -> T): T {
    val sem = semaphores.computeIfAbsent(domain) {
        Semaphore(maxConcurrentPerDomain)
    }
    sem.acquire()
    try {
        val lastTime = lastRequestTime[domain]
        if (lastTime != null) {
            val elapsed = System.currentTimeMillis() - lastTime
            if (elapsed < minDelayMs) {
                delay(minDelayMs - elapsed)
            }
        }
        return block()
    } finally {
        lastRequestTime[domain] = System.currentTimeMillis()
        sem.release()
    }
}

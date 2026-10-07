package ru.wbparser.domain.value

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import java.net.URI

@JvmInline
value class CrawlUrl private constructor(
    private val url: String,
) {
    init {
        require(url.isNotBlank()) { "URL cannot be blank" }
    }

    fun getDomain(): String =
        runCatching {
            URI(url).host ?: ""
        }.getOrDefault("")

    fun isAbsolute(): Boolean = url.startsWith("http://") || url.startsWith("https://")

    override fun toString(): String = url

    companion object {
        fun of(url: String): Either<InvalidUrl, CrawlUrl> =
            either {
                catch({
                    val u = CrawlUrl(url)
                    require(url.isNotBlank())
                    require(url.startsWith("http://") || url.startsWith("https://"))
                    u
                }) { e ->
                    raise(InvalidUrl("Invalid URL: $url — ${e.message}"))
                }
            }
    }
}

@JvmInline
value class InvalidUrl(
    val message: String,
)

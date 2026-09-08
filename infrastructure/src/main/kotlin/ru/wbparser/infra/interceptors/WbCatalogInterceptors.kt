package ru.wbparser.infra.interceptors

import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched

/**
 * Interceptor that runs before a request is sent.
 */
typealias RequestInterceptor = suspend (Crawling) -> Map<String, String>

/**
 * Interceptor that runs after a response is received.
 */
typealias ResponseInterceptor = suspend (Fetched) -> Fetched

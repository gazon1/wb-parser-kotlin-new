package ru.wbparser.infra.http

/**
 * The JavaScript that issues the WB catalog API call from inside the browser page.
 *
 * Kept as a constant so it can be asserted on directly: header values are interpolated at
 * runtime from a JSON argument rather than baked into the source, because a value like
 * `"Linux"` breaks a Kotlin string template.
 *
 * It lives apart from [WbCatalogApi] on purpose: the API object says *what* is asked and with
 * *which* headers, this object says *how* the answer is obtained — inside the page context,
 * with the browser's own cookies, because WB refuses a catalog call that does not look like it
 * came from the storefront.
 */
internal object WbCatalogFetchScript {
    /**
     * The JS evaluated inside the page.
     *
     * The argument is a JSON object with exactly two fields: `url` and `headers`. They used to
     * travel in one map, where the internal `__apiUrl`, `__catalogId` and `__cookies` keys were
     * indistinguishable from headers — and the first two really did reach WB as request
     * headers. A URL of its own makes "what is a header" impossible to get wrong.
     *
     * The request is made with `credentials: 'include'`, so the browser attaches the session
     * cookies itself. A `Cookie` header set from page JavaScript is a forbidden header and is
     * dropped by the browser without an error, so the manual cookie jar was dead weight: the
     * cookies it carried never left the page.
     */
    val FETCH_SCRIPT: String =
        """
        (requestArg) => {
            const request = JSON.parse(requestArg);
            return fetch(request.url, {
                method: 'GET',
                headers: request.headers,
                credentials: 'include'
            }).then(resp => resp.text().then(body => ({
                status: resp.status,
                ok: resp.ok,
                body: body
            })));
        }
        """.trimIndent()
}

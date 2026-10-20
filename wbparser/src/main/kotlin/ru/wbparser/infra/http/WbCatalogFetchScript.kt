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
     * The argument is a JSON object carrying the headers plus the meta keys `__apiUrl` and
     * `__cookies`; the script turns the cookie map into a `Cookie` header and issues the
     * request with `credentials: 'include'`.
     */
    val FETCH_SCRIPT: String =
        """
        (headersArg) => {
            const headers = JSON.parse(headersArg);
            const apiUrl = headers.__apiUrl;
            const fetchHeaders = {};
            for (const [k, v] of Object.entries(headers)) {
                if (k === '__cookies') continue;
                fetchHeaders[k] = v;
            }
            // Build Cookie header from the __cookies map so the API call is authenticated.
            const cookiesMap = JSON.parse(headers.__cookies || '{}');
            const cookieParts = [];
            for (const [k, v] of Object.entries(cookiesMap)) {
                cookieParts.push(k + '=' + v);
            }
            if (cookieParts.length > 0) fetchHeaders['cookie'] = cookieParts.join('; ');
            return fetch(apiUrl, {
                method: 'GET',
                headers: fetchHeaders,
                credentials: 'include'
            }).then(resp => resp.text().then(body => ({
                status: resp.status,
                ok: resp.ok,
                body: body
            })));
        }
        """.trimIndent()
}

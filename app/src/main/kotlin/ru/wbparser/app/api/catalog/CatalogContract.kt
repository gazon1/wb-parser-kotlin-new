package ru.wbparser.app.api.catalog

import org.yaml.snakeyaml.Yaml

/**
 * The contract document — `contract/catalog-v1.openapi.yaml`, served at
 * `/api/catalog/v1/contract`.
 *
 * ## Why the API serves its own contract
 *
 * A consumer that validates the responses it receives against a *copy* of the contract
 * validates against whatever that copy happens to say. Serving the document the API was
 * built from removes the copy: the storefront fetches the contract from the crawler and
 * validates against that.
 *
 * The file itself lives at the root of the repository, next to the ADR that explains it,
 * and the build copies it onto the classpath. There is no second copy in the source tree
 * that could drift from the first.
 *
 * ## Why it is parsed as well as served
 *
 * The served bytes are the document, verbatim — a consumer must be able to read exactly
 * what the crawler published. The parsed form is what the crawler's own tests compare the
 * Kotlin DTOs against, so a schema and a data class cannot disagree without a test
 * noticing.
 */
object CatalogContract {
    /** Where the document lives on the classpath. */
    const val RESOURCE: String = "/contract/catalog-v1.openapi.yaml"

    /**
     * The document, as it was published.
     *
     * Read once and kept: it does not change while the process runs, and the endpoint is
     * called by the storefront's contract test.
     */
    val document: String by lazy {
        val stream =
            checkNotNull(CatalogContract::class.java.getResourceAsStream(RESOURCE)) {
                "The contract document is not on the classpath at '$RESOURCE'. The build copies " +
                    "it there from the repository's contract/ directory; a missing copy means the " +
                    "API would answer /contract with nothing and every consumer of the contract " +
                    "would fall back to its own idea of the shapes."
            }
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * The document, parsed.
     *
     * The keys are the ones of the OpenAPI document: `openapi`, `info`, `paths`,
     * `components`, and so on.
     */
    val parsed: Map<String, Any?> by lazy {
        @Suppress("UNCHECKED_CAST")
        Yaml().load<Any?>(document) as? Map<String, Any?>
            ?: error("The contract document at '$RESOURCE' is not a mapping — it is not an OpenAPI document.")
    }

    /**
     * The schemas of the document, by name.
     *
     * `components.schemas` is where the shapes live, and the contract test walks them one
     * by one.
     */
    val schemas: Map<String, Map<String, Any?>> by lazy {
        val components = parsed["components"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        @Suppress("UNCHECKED_CAST")
        (components["schemas"] as? Map<String, Map<String, Any?>>) ?: emptyMap()
    }
}

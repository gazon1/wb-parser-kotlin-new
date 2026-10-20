package ru.wbparser.app.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import ru.wbparser.app.config.CatalogProperties

/**
 * The application's three security chains.
 *
 * | Chain | Matches | Key |
 * |---|---|---|
 * | admin | the admin prefix and everything under it | `ADMIN_API_KEY` |
 * | catalog | the catalog prefix and everything under it | `CATALOG_API_KEY` |
 * | public | everything else | none |
 *
 * The prefixes themselves are in the matchers below; they are spelled out here in prose
 * because a path ending in a double star inside a comment opens a nested comment in
 * Kotlin, and the file would not compile.
 *
 * ## Why the chains are ordered explicitly
 *
 * Spring Security picks the first chain whose matcher accepts the request, and the
 * matcher of the catch-all chain accepts everything. Without an explicit order the
 * catalog could end up behind the catch-all, and the catch-all is `permitAll` — the API
 * would be open. The `@Order` annotations make the precedence a fact of the file rather
 * than an accident of declaration order.
 *
 * ## Why the admin key does not open the catalog
 *
 * The two chains read different variables, and the catalog's filter compares against its
 * own list only. A shared key would turn a compromise of the admin API into a compromise
 * of the data, so the configuration refuses to start when the two are the same value.
 */
@Configuration
@EnableWebSecurity
@Profile("!once")
class WebSecurityConfig {
    @Bean
    @Order(1)
    fun adminSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/admin/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(
                AdminApiKeyFilter(),
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter::class.java,
            ).authorizeHttpRequests { auth ->
                auth.anyRequest().hasRole("ADMIN")
            }
        return http.build()
    }

    /**
     * The catalog chain.
     *
     * The filter answers `401` itself when the key is missing or wrong and stops the
     * request there, so the authorisation rule below only ever sees an authenticated
     * catalog request.
     *
     * The chain has three filters, in this order:
     *
     *  1. the request identifier — every request gets one, and the log line of a request
     *     that went wrong can be quoted by whoever reported it;
     *  2. the key — no key, no request, and nothing below this point runs without one;
     *  3. the rate limit — it counts per key, so it has to run after the key check.
     */
    @Bean
    @Order(2)
    fun catalogSecurityFilterChain(
        http: HttpSecurity,
        catalogProperties: CatalogProperties,
    ): SecurityFilterChain {
        http
            .securityMatcher("/api/catalog/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(
                CatalogRequestIdFilter(),
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter::class.java,
            ).addFilterBefore(
                catalogApiKeyFilter(catalogProperties),
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter::class.java,
            )
        // The rate limit is optional, and a filter that is not there is not added: the
        // chain is built the same way every time, and the setting decides whether the
        // filter is one of the filters.
        catalogRateLimitFilter(catalogProperties)?.let { rateLimit ->
            http.addFilterBefore(
                rateLimit,
                org.springframework.security.web.authentication.www.BasicAuthenticationFilter::class.java,
            )
        }
        http.authorizeHttpRequests { auth ->
            // `hasAuthority`, not `hasRole`: the authority the filter grants is the
            // full "ROLE_CATALOG", and `hasRole` prepends "ROLE_" itself — which
            // would look for an authority nobody holds and refuse every request.
            auth.anyRequest().hasAuthority(CatalogApiKeyFilter.ROLE_CATALOG)
        }
        return http.build()
    }

    @Bean
    @Order(3)
    fun publicSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/health/**", "/actuator/**")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll()
            }
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration =
            CorsConfiguration().apply {
                // No origin is allowed. The storefront talks to the catalog server-to-server
                // over the container network, so it never issues a browser request to it —
                // and a browser request would be one more way in for a page on another
                // origin to read the catalog. The headers the storefront's own pages rely
                // on are still exposed.
                allowedOrigins = emptyList()
                allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
                allowedHeaders = listOf("*")
                exposedHeaders = listOf("X-Total-Count", "X-Page-Count")
            }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }

    /**
     * Build the catalog's key filter, and refuse the configurations that would leave the
     * API open or make it a twin of the admin API.
     *
     * @throws IllegalStateException when no catalog key is configured, or when the catalog
     * key is the admin key.
     */
    private fun catalogApiKeyFilter(properties: CatalogProperties): CatalogApiKeyFilter {
        val filter = CatalogApiKeyFilter.fromConfiguredValue(properties.apiKey)
        val adminKey = System.getenv("ADMIN_API_KEY")
        val catalogKeys =
            properties.apiKey
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (adminKey != null && adminKey.isNotEmpty() && catalogKeys.any { it == adminKey }) {
            throw IllegalStateException(
                "CATALOG_API_KEY must not contain ADMIN_API_KEY. The admin API and the catalog " +
                    "are different consumers, and one key for both makes a compromise of one a " +
                    "compromise of the other.",
            )
        }
        return filter
    }

    /**
     * The catalog's rate limiter.
     *
     * It is a filter and not a setting of the connection pool on purpose: the limit is
     * about the number of requests a caller may make, and the pool's size is about how
     * many of them may run at once. The two answer different questions, and the second
     * one is the database's to answer.
     */
    private fun catalogRateLimitFilter(properties: CatalogProperties): CatalogRateLimitFilter? {
        if (!properties.rateLimit.enabled) return null
        return CatalogRateLimitFilter(requestsPerWindow = properties.rateLimit.requestsPerMinute)
    }
}

package ru.wbparser.app.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import ru.wbparser.app.config.CrawlProperties

@Configuration
@EnableWebSecurity
class WebSecurityConfig {

    @Bean
    @Order(1)
    fun adminSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/admin/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(AdminApiKeyFilter(), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter::class.java)
            .authorizeHttpRequests { auth ->
                auth.anyRequest().hasRole("ADMIN")
            }
        return http.build()
    }

    @Bean
    @Order(2)
    fun publicSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/health/**", "/actuator/**").permitAll()
                    .requestMatchers("/api/catalog/**").permitAll()
                    .requestMatchers("/api/admin/**").authenticated()
                    .anyRequest().permitAll()
            }
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = listOf("*")
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            exposedHeaders = listOf("X-Total-Count", "X-Page-Count")
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }
}

/**
 * Top-level security config builder — for programmatic use when needed.
 */
fun securityConfig(props: CrawlProperties): SecurityConfigData {
    return SecurityConfigData()
}

data class SecurityConfigData(
    val adminPath: String = "/api/admin/**",
    val publicPaths: List<String> = listOf("/api/health/**", "/actuator/**", "/api/catalog/**"),
)

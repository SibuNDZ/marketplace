package com.marketplace.api.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security. CSRF is disabled — CSRF protects cookie-based
 * sessions; a Bearer token in a header isn't sent automatically by browsers,
 * so the attack doesn't apply.
 *
 * @EnableMethodSecurity enables @PreAuthorize for ownership checks in services
 * (e.g. "vendor may only edit own products") where URL patterns can't tell
 * who owns product 42.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    /** True only when spring.h2.console.enabled=true (default profile / dev). */
    private final boolean h2ConsoleEnabled;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.h2ConsoleEnabled = h2ConsoleEnabled;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/reviews/**").permitAll();
                // H2 console access only when explicitly enabled (dev profile).
                // In prod, spring.h2.console.enabled=false so this branch never runs.
                if (h2ConsoleEnabled) {
                    auth.requestMatchers("/h2-console/**").permitAll();
                }
                auth.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated();
            })
            .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authEx) -> {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/problem+json");
                response.getWriter().write("""
                        {"type":"about:blank","title":"Unauthorized",\
                        "status":401,"detail":"Authentication required"}""");
            }))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        // X-Frame-Options: SAMEORIGIN lets the H2 console render in its iframe.
        // Only relaxed when the console is actually enabled.
        if (h2ConsoleEnabled) {
            http.headers(h -> h.frameOptions(f -> f.sameOrigin()));
        }

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

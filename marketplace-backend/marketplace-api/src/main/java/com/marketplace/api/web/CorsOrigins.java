package com.marketplace.api.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single parse of app.cors.allowed-origins. Two call sites need this list —
 * SecurityConfig's CorsFilter and AuthRateLimitFilter's hand-stamped 429 (the
 * filter runs before the security chain, so it can't rely on CorsFilter) —
 * and each parsing its own copy of the raw property is a footgun: the day the
 * Railway origin is added, an edit to one copy and not the other regresses
 * the 429 path silently for exactly the production origin.
 */
@Component
public class CorsOrigins {

    private final List<String> origins;

    public CorsOrigins(@Value("${app.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.origins = List.of(allowedOrigins.split(","));
    }

    public List<String> asList() {
        return origins;
    }

    public boolean contains(String origin) {
        return origins.contains(origin);
    }
}

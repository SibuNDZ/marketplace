package com.marketplace.api.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single parse of app.cors.allowed-origins. Three call sites need this list:
 * SecurityConfig's CorsFilter, plus AuthRateLimitFilter's 429 and
 * RequestBodyLimitFilter's 413, both stamped by hand because those filters
 * run before the security chain and can't rely on CorsFilter. Each parsing
 * its own copy of the raw property is a footgun: the day the Railway origin
 * is added, an edit to one copy and not another regresses an error path
 * silently for exactly the production origin.
 */
@Component
public class CorsOrigins {

    private final List<String> origins;

    public CorsOrigins(@Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.origins = List.of(allowedOrigins.split(","));
    }

    public List<String> asList() {
        return origins;
    }

    public boolean contains(String origin) {
        return origins.contains(origin);
    }
}

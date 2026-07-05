package com.marketplace.api.security;

import com.marketplace.api.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs once per request, before UsernamePasswordAuthenticationFilter.
 *
 * After validating the token we load the user from DB rather than trusting
 * the token's claims. This costs one indexed PK lookup per request and buys
 * immediate enforcement of bans and role changes.
 *
 * This filter NEVER sends error responses. No token, bad token, vanished user
 * — all mean "proceed unauthenticated" and let authorization rules reject
 * protected routes with a 401. A filter that writes its own error responses
 * bypasses exception handling and produces inconsistent error bodies.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            String token = header.substring(BEARER_PREFIX.length());

            jwtService.validateAndGetUserId(token)
                    .flatMap(userRepository::findById)
                    .map(UserPrincipal::from)
                    .filter(UserPrincipal::isEnabled)
                    .ifPresent(principal -> {
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(
                                        principal, null, principal.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
        }

        filterChain.doFilter(request, response);
    }
}

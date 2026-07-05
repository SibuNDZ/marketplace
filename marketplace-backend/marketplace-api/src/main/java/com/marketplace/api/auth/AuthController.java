package com.marketplace.api.auth;

import com.marketplace.api.auth.AuthDtos.AuthResponse;
import com.marketplace.api.auth.AuthDtos.LoginRequest;
import com.marketplace.api.auth.AuthDtos.RegisterRequest;
import com.marketplace.api.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * Smoke test for the full JWT chain: if this returns your user, the filter,
     * JwtService, and SecurityContext wiring all work.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(Map.of(
                "userId", principal.getId(),
                "email", principal.getUsername(),
                "role", principal.getRole()));
    }
}

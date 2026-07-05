package com.marketplace.api.auth;

import com.marketplace.api.auth.AuthDtos.AuthResponse;
import com.marketplace.api.auth.AuthDtos.LoginRequest;
import com.marketplace.api.auth.AuthDtos.RegisterRequest;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.UserRole;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and login.
 *
 * Login failure is ONE exception whether the email doesn't exist or the
 * password is wrong — distinguishing them would turn this endpoint into a
 * user-enumeration oracle.
 *
 * passwordEncoder.matches() runs even when the user doesn't exist (against a
 * dummy hash). Without this, "unknown email" returns in ~1ms and "known email,
 * wrong password" in ~100ms (bcrypt cost) — a timing side channel that
 * enumerates users just as effectively as different error messages.
 */
@Service
public class AuthService {

    private static final String DUMMY_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5B0mzVWkzZg9Yx0J8bIhH1S6y5tXe";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }

        String[] nameParts = request.fullName().trim().split("\\s+", 2);

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setFirstName(nameParts[0]);
        user.setLastName(nameParts.length > 1 ? nameParts[1] : "");
        user.setRole(UserRole.valueOf(request.roleOrDefault()));

        User saved = userRepository.save(user);

        String token = jwtService.generateToken(saved.getId(), saved.getRole().name());
        return AuthResponse.bearer(token, jwtService.getExpirySeconds(),
                saved.getId(), saved.getEmail(), saved.getRole().name());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();

        User user = userRepository.findByEmail(email).orElse(null);

        String hashToCheck = user != null ? user.getPassword() : DUMMY_HASH;
        boolean matches = passwordEncoder.matches(request.password(), hashToCheck);

        if (user == null || !matches) {
            throw new BadCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(user.getId(), user.getRole().name());
        return AuthResponse.bearer(token, jwtService.getExpirySeconds(),
                user.getId(), user.getEmail(), user.getRole().name());
    }

    public static class EmailAlreadyRegisteredException extends RuntimeException {
        public EmailAlreadyRegisteredException(String email) {
            super("Email already registered: " + email);
        }
    }
}

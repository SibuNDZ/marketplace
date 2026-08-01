package com.marketplace.api.controller;

import com.marketplace.api.entity.User;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Self-service account management, scoped to the caller's own row by
 * construction (id comes from the token, never from the request).
 *
 * Deliberately NOT here: email (it is the login identifier and is verified;
 * changing it needs a re-verification flow), username (unique handle,
 * rename needs collision UX), password (has its own reset flow), and role.
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final UserRepository userRepository;

    public AccountController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record AccountResponse(
            String email,
            String username,
            String firstName,
            String lastName,
            String phoneNumber,
            String role
    ) {
        static AccountResponse from(User u) {
            return new AccountResponse(u.getEmail(), u.getUsername(), u.getFirstName(),
                    u.getLastName(), u.getPhoneNumber(), u.getRole().name());
        }
    }

    /** lastName may be blank: mononyms are common (same rule as registration). */
    public record UpdateAccountRequest(
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Size(max = 30)
            @Pattern(regexp = "^$|^[0-9+()\\-\\s]{7,30}$", message = "Enter a valid phone number")
            String phoneNumber
    ) {}

    @GetMapping
    @Transactional(readOnly = true)
    public AccountResponse get(@AuthenticationPrincipal UserPrincipal me) {
        return AccountResponse.from(userRepository.findById(me.getId()).orElseThrow());
    }

    @PutMapping
    @Transactional
    public AccountResponse update(@Valid @RequestBody UpdateAccountRequest request,
                                  @AuthenticationPrincipal UserPrincipal me) {
        User user = userRepository.findById(me.getId()).orElseThrow();
        user.setFirstName(request.firstName().strip());
        user.setLastName(request.lastName() == null ? "" : request.lastName().strip());
        user.setPhoneNumber(request.phoneNumber() == null || request.phoneNumber().isBlank()
                ? null : request.phoneNumber().strip());
        return AccountResponse.from(user);
    }
}

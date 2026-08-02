package com.marketplace.api.controller;

import com.marketplace.api.auth.AuthService;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.UserRole;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

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
            String role,
            /** Null for buyers; the public storefront name for vendors. */
            String businessName
    ) {
        static AccountResponse from(User u) {
            return new AccountResponse(u.getEmail(), u.getUsername(), u.getFirstName(),
                    u.getLastName(), u.getPhoneNumber(), u.getRole().name(), u.getBusinessName());
        }
    }

    /** lastName may be blank: mononyms are common (same rule as registration). */
    public record UpdateAccountRequest(
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Size(max = 30)
            @Pattern(regexp = "^$|^[0-9+()\\-\\s]{7,30}$", message = "Enter a valid phone number")
            String phoneNumber,
            /** Vendors only; ignored for buyers, who have no storefront. */
            @Size(max = 200) String businessName
    ) {}

    /**
     * A buyer declaring they want to sell. Same fields a VENDOR registration
     * demands, because it produces the same kind of account — the upgrade
     * must not be a cheaper door into the same role.
     */
    public record BecomeVendorRequest(
            @NotBlank(message = "Business name is required — this is what buyers see on your listings")
            @Size(max = 200) String businessName,
            @NotBlank(message = "Last name is required for seller accounts")
            @Size(max = 100) String lastName
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

        // A vendor may rename their storefront but may not erase it: the name
        // is on every one of their listings. Buyers' submissions are ignored
        // rather than rejected — a stray field should not fail a name change.
        if (user.getRole() == UserRole.VENDOR) {
            String businessName = request.businessName() == null ? "" : request.businessName().strip();
            if (businessName.isBlank()) {
                throw new AuthService.VendorDetailsRequiredException(Map.of(
                        "businessName", List.of("Business name is required for seller accounts")));
            }
            user.setBusinessName(businessName);
        }
        return AccountResponse.from(user);
    }

    /**
     * Buyer -> seller, self-serve. Registration already lets anyone choose
     * "I'm selling" with no gate, so gating the upgrade would only punish
     * people who discovered selling later, and would imply a vetting step
     * that does not exist.
     *
     * No token juggling needed: JwtAuthenticationFilter reloads the user from
     * the database on every request, so the new role is live on the caller's
     * very next request with the session they already hold.
     */
    @PostMapping("/become-vendor")
    @Transactional
    public AccountResponse becomeVendor(@Valid @RequestBody BecomeVendorRequest request,
                                        @AuthenticationPrincipal UserPrincipal me) {
        User user = userRepository.findById(me.getId()).orElseThrow();

        // Idempotent for vendors, refused for admins: an admin "upgrading"
        // to VENDOR would be a silent privilege DOWNGRADE.
        if (user.getRole() == UserRole.ADMIN) {
            throw new AccessDeniedException("Admin accounts cannot be converted to seller accounts");
        }
        if (user.getRole() != UserRole.VENDOR) {
            user.setRole(UserRole.VENDOR);
            log.info("User {} upgraded CUSTOMER -> VENDOR", user.getId());
        }
        user.setBusinessName(request.businessName().strip());
        user.setLastName(request.lastName().strip());
        return AccountResponse.from(user);
    }
}

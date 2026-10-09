package com.marketplace.api.vendor;

import com.marketplace.api.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin actions on a store. Double-gated like the other admin controllers:
 * the /api/v1/admin/** rule in SecurityConfig requires ROLE_ADMIN before
 * routing, and @PreAuthorize repeats it here.
 */
@RestController
@RequestMapping("/api/v1/admin/vendors")
@PreAuthorize("hasRole('ADMIN')")
public class AdminVendorController {

    private final VendorProfileService profiles;

    public AdminVendorController(VendorProfileService profiles) {
        this.profiles = profiles;
    }

    public record ClearLinksRequest(
            @NotBlank(message = "Say why these links are being removed")
            @Size(max = 300) String reason
    ) {}

    /**
     * Removes every social link a store has: for a store linking an account
     * that is not theirs, or anything else that breaks the seller terms. The
     * reason is logged. POST with a body rather than DELETE, so the reason is
     * validated like any other input and never sits in an access-log URL.
     */
    @PostMapping("/{id}/social-links/clear")
    public Map<String, Integer> clearSocialLinks(@PathVariable Long id,
                                                 @Valid @RequestBody ClearLinksRequest request,
                                                 @AuthenticationPrincipal UserPrincipal admin) {
        return Map.of("removed", profiles.adminClearSocialLinks(id, admin.getId(), request.reason()));
    }
}

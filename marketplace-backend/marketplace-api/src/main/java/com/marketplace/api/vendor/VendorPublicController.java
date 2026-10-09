package com.marketplace.api.vendor;

import com.marketplace.api.vendor.VendorProfileService.VendorProfile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public store profiles. No authentication (SecurityConfig permits GET
 * /api/v1/vendors/**): these are shop fronts, and the home page and shop
 * pages are read by signed-out visitors.
 *
 * Nothing private is exposed: name, bio, picture and figures derived from
 * public listings. Never email, phone, banking or anything from the account
 * behind the store.
 */
@RestController
@RequestMapping("/api/v1/vendors")
public class VendorPublicController {

    private final VendorProfileService profiles;

    public VendorPublicController(VendorProfileService profiles) {
        this.profiles = profiles;
    }

    /**
     * Every store eligible for the home page spotlight, completed profiles
     * first. The client picks where to start and rotates, so the order here
     * is a preference, not a fixed pick.
     */
    @GetMapping("/spotlight")
    public List<VendorProfile> spotlight() {
        return profiles.spotlight();
    }

    /** One store's profile, for its shop page. 404 for anything not a vendor. */
    @GetMapping("/{id}")
    public VendorProfile profile(@PathVariable Long id) {
        return profiles.publicProfile(id)
                .orElseThrow(() -> new VendorNotFoundException(id));
    }

    /**
     * 404 (GlobalExceptionHandler). A customer's or admin's id answers the
     * same as a nonexistent one, so the endpoint cannot be used to learn
     * which ids are accounts.
     */
    public static class VendorNotFoundException extends RuntimeException {
        public VendorNotFoundException(Long id) {
            super("No store with id " + id);
        }
    }
}

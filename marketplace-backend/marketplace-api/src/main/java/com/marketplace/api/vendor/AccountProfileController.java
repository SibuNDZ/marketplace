package com.marketplace.api.vendor;

import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.vendor.VendorProfileService.OwnProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * The signed-in vendor's own store profile. Scoped to the caller by
 * construction: the id comes from the token, never from the request, so
 * there is no id to tamper with. Non-vendors get 403 from the service.
 */
@RestController
@RequestMapping("/api/v1/account/profile")
public class AccountProfileController {

    private final VendorProfileService profiles;

    public AccountProfileController(VendorProfileService profiles) {
        this.profiles = profiles;
    }

    public record BioRequest(
            @Size(max = VendorProfileService.BIO_MAX,
                    message = "Keep the bio to " + VendorProfileService.BIO_MAX + " characters or fewer")
            String bio
    ) {}

    @GetMapping
    public OwnProfile get(@AuthenticationPrincipal UserPrincipal me) {
        return profiles.own(me.getId());
    }

    @PutMapping
    public OwnProfile updateBio(@Valid @RequestBody BioRequest request,
                                @AuthenticationPrincipal UserPrincipal me) {
        return profiles.updateBio(me.getId(), request.bio());
    }

    /**
     * Per field: absent or null leaves that link unchanged, "" clears it,
     * anything else is normalised (seller-social-links.md section 5). A field
     * that cannot be read as a profile on its platform fails the whole
     * request with field-keyed errors, and nothing is saved.
     */
    public record SocialLinksRequest(
            @Size(max = 300) String instagram,
            @Size(max = 300) String tiktok,
            @Size(max = 300) String facebook,
            @Size(max = 300) String x
    ) {
        java.util.Map<SocialPlatform, String> byPlatform() {
            java.util.Map<SocialPlatform, String> m = new java.util.EnumMap<>(SocialPlatform.class);
            m.put(SocialPlatform.INSTAGRAM, instagram);
            m.put(SocialPlatform.TIKTOK, tiktok);
            m.put(SocialPlatform.FACEBOOK, facebook);
            m.put(SocialPlatform.X, x);
            return m;
        }
    }

    @PutMapping("/social-links")
    public OwnProfile updateSocialLinks(@Valid @RequestBody SocialLinksRequest request,
                                        @AuthenticationPrincipal UserPrincipal me) {
        return profiles.updateSocialLinks(me.getId(), request.byPlatform());
    }

    @PostMapping("/avatar")
    public OwnProfile uploadAvatar(@RequestParam("file") MultipartFile file,
                                   @AuthenticationPrincipal UserPrincipal me) {
        return profiles.uploadAvatar(me.getId(), file);
    }

    @DeleteMapping("/avatar")
    public OwnProfile removeAvatar(@AuthenticationPrincipal UserPrincipal me) {
        return profiles.removeAvatar(me.getId());
    }
}

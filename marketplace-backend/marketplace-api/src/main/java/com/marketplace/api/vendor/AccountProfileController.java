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

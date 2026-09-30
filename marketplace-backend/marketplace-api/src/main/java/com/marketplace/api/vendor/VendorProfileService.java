package com.marketplace.api.vendor;

import com.marketplace.api.entity.User;
import com.marketplace.api.entity.UserRole;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.storage.ImageValidation;
import com.marketplace.api.storage.ObjectStorageService;
import com.marketplace.api.vendor.VendorCardRepository.VendorCard;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A vendor's public store profile (V35): picture and bio.
 *
 * Writes are the vendor's own, by construction: the id always comes from the
 * token, and only VENDOR accounts have a storefront to describe. Reads are
 * public, because the profile's whole purpose is to be seen by buyers.
 *
 * The picture follows the product-photo rules exactly (ImageValidation: jpeg,
 * png or webp, 5MB, extension from the validated type, filename ignored) and
 * the same key discipline: every upload is a NEW key, so the immutable cache
 * headers can never serve a stale picture, and the old object is removed
 * best-effort after the row points at the new one.
 */
@Service
public class VendorProfileService {

    public static final int BIO_MAX = 500;

    /** Public view of a store. Figures are live; see VendorCardRepository. */
    public record VendorProfile(
            Long id,
            String name,
            String bio,
            String avatarUrl,
            long pieces,
            long sold,
            BigDecimal rating,      // null until the store has a review
            long reviewCount,
            String sampleImageUrl   // a photo from their newest listing, or null
    ) {}

    /** What the vendor edits on their own profile page. */
    public record OwnProfile(String name, String bio, String avatarUrl) {}

    private final UserRepository userRepository;
    private final VendorCardRepository cardRepository;
    private final ObjectStorageService storage;

    public VendorProfileService(UserRepository userRepository,
                                VendorCardRepository cardRepository,
                                ObjectStorageService storage) {
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
        this.storage = storage;
    }

    // ── public reads ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public java.util.Optional<VendorProfile> publicProfile(Long vendorId) {
        return cardRepository.findCard(vendorId).map(this::toProfile);
    }

    @Transactional(readOnly = true)
    public List<VendorProfile> spotlight() {
        return cardRepository.spotlightCards().stream().map(this::toProfile).toList();
    }

    // ── the vendor's own writes ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public OwnProfile own(Long userId) {
        return toOwn(requireVendor(userId));
    }

    /**
     * Blank clears the bio. Trimmed, because a bio that is only whitespace is
     * no bio, and leading space would push the text off its own paragraph.
     */
    @Transactional
    public OwnProfile updateBio(Long userId, String bio) {
        User vendor = requireVendor(userId);
        String trimmed = bio == null ? "" : bio.strip();
        if (trimmed.length() > BIO_MAX) {
            // The request DTO caps it too; this guards any other caller.
            throw new IllegalArgumentException("Bio must be " + BIO_MAX + " characters or fewer");
        }
        vendor.setBio(trimmed.isEmpty() ? null : trimmed);
        return toOwn(vendor);
    }

    @Transactional
    public OwnProfile uploadAvatar(Long userId, MultipartFile file) {
        User vendor = requireVendor(userId);
        String ext = ImageValidation.validateAndGetExtension(file);
        String newKey = "vendors/" + vendor.getId() + "/avatar-" + UUID.randomUUID() + "." + ext;
        try {
            storage.put(newKey, file.getInputStream(), file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String oldKey = vendor.getAvatarKey();
        vendor.setAvatarKey(newKey);
        // After the row points at the new picture: a failed delete leaves an
        // orphan object (pennies), never a profile pointing at a 404.
        if (oldKey != null) {
            storage.deleteQuietly(oldKey);
        }
        return toOwn(vendor);
    }

    @Transactional
    public OwnProfile removeAvatar(Long userId) {
        User vendor = requireVendor(userId);
        String oldKey = vendor.getAvatarKey();
        vendor.setAvatarKey(null);
        if (oldKey != null) {
            storage.deleteQuietly(oldKey);
        }
        return toOwn(vendor);
    }

    // ── internals ────────────────────────────────────────────────────────

    private User requireVendor(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        if (user.getRole() != UserRole.VENDOR) {
            // A buyer or admin has no storefront for a profile to describe.
            throw new AccessDeniedException("Only seller accounts have a store profile");
        }
        return user;
    }

    private OwnProfile toOwn(User u) {
        return new OwnProfile(u.getStorefrontName(), u.getBio(), url(u.getAvatarKey()));
    }

    private VendorProfile toProfile(VendorCard c) {
        return new VendorProfile(c.getId(), c.getName(), c.getBio(), url(c.getAvatarKey()),
                c.getPieces(), c.getSold(), c.getReviewCount() > 0 ? c.getRating() : null,
                c.getReviewCount(), url(c.getSampleKey()));
    }

    private String url(String key) {
        return key == null ? null : storage.publicUrl(key);
    }
}

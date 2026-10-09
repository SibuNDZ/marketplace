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
import java.util.Map;
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
            String sampleImageUrl,  // a photo from their newest listing, or null
            /**
             * The store's social links, set ones only, in platform order
             * (V36). Always empty in the spotlight: that section advertises
             * the store ON eRestyu, and is the wrong place to send people away
             * (seller-social-links.md section 3).
             */
            List<SocialLinkView> socialLinks
    ) {}

    /** One public link: platform key ("instagram") and the canonical URL built from the handle. */
    public record SocialLinkView(String platform, String url) {}

    /**
     * What the vendor edits on their own profile page. socialLinks has every
     * platform's key, with the canonical URL or null, so the form always has
     * all four fields.
     */
    public record OwnProfile(String name, String bio, String avatarUrl, Map<String, String> socialLinks) {}

    /** 400 with field-keyed errors ("instagram" -> message), the shape the forms already render. */
    public static class SocialLinkValidationException extends RuntimeException {
        private final Map<String, List<String>> fieldErrors;

        public SocialLinkValidationException(Map<String, List<String>> fieldErrors) {
            super("Invalid social links: " + String.join(", ", fieldErrors.keySet()));
            this.fieldErrors = fieldErrors;
        }

        public Map<String, List<String>> getFieldErrors() {
            return fieldErrors;
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VendorProfileService.class);

    private final UserRepository userRepository;
    private final VendorCardRepository cardRepository;
    private final ObjectStorageService storage;
    private final VendorSocialLinkRepository socialLinks;

    public VendorProfileService(UserRepository userRepository,
                                VendorCardRepository cardRepository,
                                ObjectStorageService storage,
                                VendorSocialLinkRepository socialLinks) {
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
        this.storage = storage;
        this.socialLinks = socialLinks;
    }

    // ── public reads ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public java.util.Optional<VendorProfile> publicProfile(Long vendorId) {
        return cardRepository.findCard(vendorId).map(c -> toProfile(c, publicLinks(c.getId())));
    }

    @Transactional(readOnly = true)
    public List<VendorProfile> spotlight() {
        return cardRepository.spotlightCards().stream().map(c -> toProfile(c, List.of())).toList();
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

    /**
     * Sets, replaces or clears the store's links. Per platform: null leaves
     * that link as it is, blank clears it, anything else is normalised.
     *
     * All-or-nothing: every field is validated before anything is written, so
     * a mistake in one field never half-saves the others, and all mistakes are
     * reported at once rather than one per attempt.
     */
    @Transactional
    public OwnProfile updateSocialLinks(Long userId, Map<SocialPlatform, String> input) {
        User vendor = requireVendor(userId);

        Map<SocialPlatform, String> toSet = new java.util.EnumMap<>(SocialPlatform.class);
        java.util.EnumSet<SocialPlatform> toClear = java.util.EnumSet.noneOf(SocialPlatform.class);
        Map<String, List<String>> errors = new java.util.LinkedHashMap<>();
        for (Map.Entry<SocialPlatform, String> e : input.entrySet()) {
            String raw = e.getValue();
            if (raw == null) continue;
            if (raw.isBlank()) {
                toClear.add(e.getKey());
                continue;
            }
            SocialLinkNormalizer.Result r = SocialLinkNormalizer.normalize(e.getKey(), raw);
            if (r.isOk()) toSet.put(e.getKey(), r.handle());
            else errors.put(e.getKey().key(), List.of(r.error()));
        }
        if (!errors.isEmpty()) {
            throw new SocialLinkValidationException(errors);
        }

        for (SocialPlatform p : toClear) {
            socialLinks.deleteOne(vendor.getId(), p);
        }
        for (Map.Entry<SocialPlatform, String> e : toSet.entrySet()) {
            VendorSocialLink link = socialLinks.findByVendorIdAndPlatform(vendor.getId(), e.getKey())
                    .orElseGet(() -> new VendorSocialLink(vendor.getId(), e.getKey(), e.getValue()));
            link.setHandle(e.getValue());
            socialLinks.save(link);
        }
        return toOwn(vendor);
    }

    /**
     * Admin removal of every link a store has, for impersonation or abuse
     * (there is no cheap way to prove an account belongs to the seller). The
     * reason is required and logged: it is the only record of why a store's
     * links disappeared. 404 for anything that is not a store.
     */
    @Transactional
    public int adminClearSocialLinks(Long vendorId, Long adminId, String reason) {
        User vendor = userRepository.findById(vendorId)
                .filter(u -> u.getRole() == UserRole.VENDOR)
                .orElseThrow(() -> new VendorPublicController.VendorNotFoundException(vendorId));
        int removed = socialLinks.deleteAllForVendor(vendor.getId());
        log.info("Admin {} cleared {} social link(s) from store {}: {}", adminId, removed, vendorId, reason.strip());
        return removed;
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
        Map<SocialPlatform, String> byPlatform = new java.util.EnumMap<>(SocialPlatform.class);
        socialLinks.findByVendorId(u.getId()).forEach(l -> byPlatform.put(l.getPlatform(), l.url()));
        Map<String, String> links = new java.util.LinkedHashMap<>();
        for (SocialPlatform p : SocialPlatform.values()) {
            links.put(p.key(), byPlatform.get(p));
        }
        return new OwnProfile(u.getStorefrontName(), u.getBio(), url(u.getAvatarKey()), links);
    }

    /** Set links only, in platform declaration order. */
    private List<SocialLinkView> publicLinks(Long vendorId) {
        return socialLinks.findByVendorId(vendorId).stream()
                .sorted(java.util.Comparator.comparing(VendorSocialLink::getPlatform))
                .map(l -> new SocialLinkView(l.getPlatform().key(), l.url()))
                .toList();
    }

    private VendorProfile toProfile(VendorCard c, List<SocialLinkView> links) {
        return new VendorProfile(c.getId(), c.getName(), c.getBio(), url(c.getAvatarKey()),
                c.getPieces(), c.getSold(), c.getReviewCount() > 0 ? c.getRating() : null,
                c.getReviewCount(), url(c.getSampleKey()), links);
    }

    private String url(String key) {
        return key == null ? null : storage.publicUrl(key);
    }
}

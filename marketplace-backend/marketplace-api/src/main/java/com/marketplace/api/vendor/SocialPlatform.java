package com.marketplace.api.vendor;

import java.util.List;

/**
 * The social platforms a store may link (V36, seller-social-links.md D3):
 * the same four the site's own footer uses. Stored by NAME, so never rename a
 * constant; add one instead. Declaration order is display order.
 *
 * Each platform owns the only URL template its links are ever built from.
 * A stored handle can therefore only become a link to that platform's
 * profile page, whatever the seller originally pasted.
 */
public enum SocialPlatform {

    INSTAGRAM("Instagram", List.of("instagram.com")),
    TIKTOK("TikTok", List.of("tiktok.com")),
    FACEBOOK("Facebook", List.of("facebook.com", "fb.com")),
    X("X", List.of("x.com", "twitter.com"));

    private final String label;
    private final List<String> hosts;

    SocialPlatform(String label, List<String> hosts) {
        this.label = label;
        this.hosts = hosts;
    }

    public String label() {
        return label;
    }

    /** Hosts a pasted link may come from: the domain itself or any subdomain of it. */
    List<String> hosts() {
        return hosts;
    }

    /** The JSON key for this platform ("instagram", "tiktok", "facebook", "x"). */
    public String key() {
        return name().toLowerCase();
    }

    /**
     * The canonical public link for a NORMALISED handle. Facebook handles that
     * are all digits are numeric profile ids (SocialLinkNormalizer never
     * produces an all-digit page name), which use the profile.php form.
     */
    public String urlFor(String handle) {
        return switch (this) {
            case INSTAGRAM -> "https://www.instagram.com/" + handle;
            case TIKTOK -> "https://www.tiktok.com/@" + handle;
            case FACEBOOK -> handle.chars().allMatch(Character::isDigit)
                    ? "https://www.facebook.com/profile.php?id=" + handle
                    : "https://www.facebook.com/" + handle;
            case X -> "https://x.com/" + handle;
        };
    }
}

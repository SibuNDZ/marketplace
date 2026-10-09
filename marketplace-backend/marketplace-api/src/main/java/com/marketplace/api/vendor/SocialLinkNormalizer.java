package com.marketplace.api.vendor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns whatever a seller pastes (a full link, a link without https://, or a
 * bare handle with or without @) into one normalised handle for a platform,
 * or a plain-language reason it cannot.
 *
 * Only the handle leaves here; the public link is rebuilt from it by
 * SocialPlatform.urlFor. So nothing a seller types is ever echoed as a link,
 * and the rules below decide only WHICH handle, never where a link points.
 *
 * Deliberately shape-only: the server never fetches the URL. A "does this
 * profile exist" check would be a server-side request to an address a user
 * chose, and the platforms block scrapers anyway. See seller-social-links.md §5.
 */
public final class SocialLinkNormalizer {

    private SocialLinkNormalizer() {}

    /** Either a handle or a reason, never both. */
    public record Result(String handle, String error) {
        static Result ok(String handle) { return new Result(handle, null); }
        static Result bad(String error) { return new Result(null, error); }
        public boolean isOk() { return handle != null; }
    }

    private static final Pattern INSTAGRAM = Pattern.compile("[a-z0-9._]{1,30}");
    private static final Pattern TIKTOK = Pattern.compile("[a-z0-9._]{2,24}");
    private static final Pattern X_HANDLE = Pattern.compile("[a-z0-9_]{1,15}");
    private static final Pattern FB_PAGE = Pattern.compile("[A-Za-z0-9.]{5,50}");
    private static final Pattern FB_ID = Pattern.compile("\\d{5,20}");

    /**
     * First path segments that parse like a handle but are platform pages, not
     * profiles. Not exhaustive of every platform route, only of the ones a
     * seller could plausibly paste; anything with a second path segment is
     * refused separately as a post.
     */
    private static final Set<String> RESERVED_INSTAGRAM = Set.of(
            "p", "reel", "reels", "stories", "explore", "tv", "accounts", "direct",
            "about", "developer", "legal", "web", "challenge", "emails");
    private static final Set<String> RESERVED_X = Set.of(
            "home", "explore", "notifications", "messages", "i", "search", "settings",
            "intent", "share", "hashtag", "compose", "login", "signup", "tos", "privacy");
    private static final Set<String> RESERVED_FACEBOOK = Set.of(
            "groups", "events", "sharer", "sharer.php", "share", "share.php", "watch",
            "marketplace", "login", "login.php", "help", "photo", "photo.php", "story.php",
            "permalink.php", "hashtag", "gaming", "reel", "reels", "stories", "l.php",
            "home.php", "search", "settings", "friends", "messages", "notifications");

    /** Facebook's outbound link shims: facebook.com subdomains that redirect anywhere. */
    private static final Set<String> FACEBOOK_SHIM_HOSTS = Set.of("l.facebook.com", "lm.facebook.com");

    public static Result normalize(SocialPlatform platform, String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) {
            return Result.bad("Empty");
        }
        if (s.startsWith("@")) {
            return handleRule(platform, s.substring(1));
        }
        // A bare handle has no slash and is not itself a domain. Instagram and
        // TikTok handles may contain dots ("karoo.honey"), so a dot alone is
        // not enough to call it a link.
        if (!s.contains("/") && !s.contains(":") && !looksLikeHost(s)) {
            return handleRule(platform, s);
        }
        return fromLink(platform, s);
    }

    private static Result fromLink(SocialPlatform platform, String s) {
        String withScheme = s.matches("(?i)^[a-z][a-z0-9+.-]*:.*") ? s : "https://" + s;
        URI uri;
        try {
            uri = new URI(withScheme);
        } catch (URISyntaxException e) {
            return Result.bad(notAProfile(platform));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        // http is accepted as input and rebuilt as https; javascript:, data:
        // and anything else are not links to a profile at all.
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return Result.bad(notAProfile(platform));
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!onPlatform(platform, host) || FACEBOOK_SHIM_HOSTS.contains(host)) {
            return Result.bad("Paste a link to your " + platform.label() + " profile, like "
                    + example(platform) + ". Short links and other sites can't be used.");
        }

        List<String> segments = new ArrayList<>(Arrays.stream(
                (uri.getRawPath() == null ? "" : uri.getRawPath()).split("/"))
                .filter(seg -> !seg.isBlank()).toList());
        if (segments.isEmpty()) {
            return Result.bad(notAProfile(platform));
        }

        return switch (platform) {
            case INSTAGRAM -> singleSegment(platform, segments, RESERVED_INSTAGRAM);
            case X -> singleSegment(platform, segments, RESERVED_X);
            case TIKTOK -> tiktok(segments);
            case FACEBOOK -> facebook(segments, uri.getRawQuery());
        };
    }

    private static Result singleSegment(SocialPlatform platform, List<String> segments,
                                        Set<String> reserved) {
        String first = segments.get(0);
        if (reserved.contains(first.toLowerCase(Locale.ROOT))) {
            return Result.bad(isPostRoute(first) ? postNotProfile() : notAProfile(platform));
        }
        if (segments.size() > 1) {
            return Result.bad(postNotProfile());
        }
        return handleRule(platform, first);
    }

    private static Result tiktok(List<String> segments) {
        String first = segments.get(0);
        // A TikTok profile is always /@handle; a path without @ is a short
        // link (vm.tiktok.com/ZM...) or some other page.
        if (!first.startsWith("@")) {
            return Result.bad(notAProfile(SocialPlatform.TIKTOK));
        }
        if (segments.size() > 1) {
            return Result.bad(postNotProfile());
        }
        return handleRule(SocialPlatform.TIKTOK, first.substring(1));
    }

    private static Result facebook(List<String> segments, String rawQuery) {
        String first = segments.get(0).toLowerCase(Locale.ROOT);
        // facebook.com/profile.php?id=615...: a numeric profile (eRestyu's own is one).
        if (first.equals("profile.php")) {
            String id = queryParam(rawQuery, "id");
            return id != null && FB_ID.matcher(id).matches()
                    ? Result.ok(id) : Result.bad(notAProfile(SocialPlatform.FACEBOOK));
        }
        // facebook.com/people/Some-Name/615... (where profile.php links redirect
        // to) and the older facebook.com/pages/Some-Name/123... page form.
        if (first.equals("people") || first.equals("pages")) {
            String id = segments.size() >= 3 ? segments.get(2) : null;
            return id != null && FB_ID.matcher(id).matches()
                    ? Result.ok(id) : Result.bad(notAProfile(SocialPlatform.FACEBOOK));
        }
        return singleSegment(SocialPlatform.FACEBOOK, segments, RESERVED_FACEBOOK);
    }

    /** The platform's own rule for a handle, after any @ has been removed. */
    private static Result handleRule(SocialPlatform platform, String candidate) {
        String h = candidate.strip();
        return switch (platform) {
            case INSTAGRAM -> {
                String lower = h.toLowerCase(Locale.ROOT);
                yield INSTAGRAM.matcher(lower).matches() ? Result.ok(lower)
                        : Result.bad("Instagram usernames are up to 30 letters, numbers, full stops or underscores.");
            }
            case TIKTOK -> {
                String lower = h.toLowerCase(Locale.ROOT);
                yield TIKTOK.matcher(lower).matches() ? Result.ok(lower)
                        : Result.bad("TikTok usernames are 2 to 24 letters, numbers, full stops or underscores.");
            }
            case X -> {
                String lower = h.toLowerCase(Locale.ROOT);
                yield X_HANDLE.matcher(lower).matches() ? Result.ok(lower)
                        : Result.bad("X usernames are up to 15 letters, numbers or underscores.");
            }
            // Facebook page names keep their case; an all-digit value is a
            // numeric profile id. A page name must contain a letter, which is
            // what lets SocialPlatform.urlFor tell the two apart.
            case FACEBOOK -> {
                if (FB_ID.matcher(h).matches()) yield Result.ok(h);
                yield FB_PAGE.matcher(h).matches() && h.chars().anyMatch(Character::isLetter)
                        && !RESERVED_FACEBOOK.contains(h.toLowerCase(Locale.ROOT))
                        ? Result.ok(h)
                        : Result.bad("That doesn't look like a Facebook page. Paste the link to your page.");
            }
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** On the platform's domain or a subdomain of it, matched on a dot boundary. */
    private static boolean onPlatform(SocialPlatform platform, String host) {
        return platform.hosts().stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
    }

    private static boolean looksLikeHost(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return Arrays.stream(SocialPlatform.values())
                .flatMap(p -> p.hosts().stream())
                .anyMatch(d -> lower.equals(d) || lower.endsWith("." + d))
                || lower.matches(".*\\.(com|co|za|ly|ee|io|me|net|org|app|link)$");
    }

    private static boolean isPostRoute(String first) {
        return Set.of("p", "reel", "reels", "stories", "tv", "watch", "photo.php", "story.php",
                "permalink.php").contains(first.toLowerCase(Locale.ROOT));
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    private static String example(SocialPlatform platform) {
        return switch (platform) {
            case INSTAGRAM -> "instagram.com/yourstore";
            case TIKTOK -> "tiktok.com/@yourstore";
            case FACEBOOK -> "facebook.com/yourstore";
            case X -> "x.com/yourstore";
        };
    }

    private static String notAProfile(SocialPlatform platform) {
        return "That doesn't look like a " + platform.label() + " profile. Paste the link to your profile, like "
                + example(platform) + ", or just your username.";
    }

    private static String postNotProfile() {
        return "That's a link to a post. Paste the link to your profile instead.";
    }
}

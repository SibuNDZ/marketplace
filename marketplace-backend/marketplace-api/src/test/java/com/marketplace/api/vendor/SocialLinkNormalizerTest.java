package com.marketplace.api.vendor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static com.marketplace.api.vendor.SocialPlatform.FACEBOOK;
import static com.marketplace.api.vendor.SocialPlatform.INSTAGRAM;
import static com.marketplace.api.vendor.SocialPlatform.TIKTOK;
import static com.marketplace.api.vendor.SocialPlatform.X;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything a seller might paste, reduced to one handle or refused. Pure
 * parsing, no Spring: the rules in seller-social-links.md §5.
 */
class SocialLinkNormalizerTest {

    private static String handle(SocialPlatform p, String raw) {
        SocialLinkNormalizer.Result r = SocialLinkNormalizer.normalize(p, raw);
        assertThat(r.isOk()).as("expected '%s' to be accepted for %s, got: %s", raw, p, r.error()).isTrue();
        return r.handle();
    }

    private static String refusal(SocialPlatform p, String raw) {
        SocialLinkNormalizer.Result r = SocialLinkNormalizer.normalize(p, raw);
        assertThat(r.isOk()).as("expected '%s' to be refused for %s", raw, p).isFalse();
        return r.error();
    }

    @Nested
    @DisplayName("every way a seller might paste their profile gives the same handle")
    class Accepted {

        @ParameterizedTest
        @ValueSource(strings = {
                "https://www.instagram.com/karoo.honey/", "https://instagram.com/karoo.honey",
                "instagram.com/karoo.honey", "www.instagram.com/karoo.honey?igsh=abc123",
                "http://instagram.com/Karoo.Honey", "@karoo.honey", "karoo.honey", "  @Karoo.Honey  "})
        void instagram(String raw) {
            assertThat(handle(INSTAGRAM, raw)).isEqualTo("karoo.honey");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://www.tiktok.com/@karoohoney", "tiktok.com/@KarooHoney/",
                "https://m.tiktok.com/@karoohoney?lang=en", "@karoohoney", "karoohoney"})
        void tiktok(String raw) {
            assertThat(handle(TIKTOK, raw)).isEqualTo("karoohoney");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://x.com/karoo_honey", "twitter.com/karoo_honey", "https://mobile.twitter.com/Karoo_Honey",
                "@karoo_honey", "karoo_honey"})
        void x(String raw) {
            assertThat(handle(X, raw)).isEqualTo("karoo_honey");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://www.facebook.com/KarooHoney", "facebook.com/KarooHoney/",
                "https://m.facebook.com/KarooHoney?ref=bookmarks", "fb.com/KarooHoney", "KarooHoney"})
        void facebookPage(String raw) {
            // Facebook page names keep their case.
            assertThat(handle(FACEBOOK, raw)).isEqualTo("KarooHoney");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://www.facebook.com/profile.php?id=61591684209144",
                "https://www.facebook.com/people/ERestyu/61591684209144/",
                "facebook.com/pages/ERestyu/61591684209144", "61591684209144"})
        void facebookNumericProfile(String raw) {
            // eRestyu's own page is this form, so it has to work.
            assertThat(handle(FACEBOOK, raw)).isEqualTo("61591684209144");
        }
    }

    @Test
    @DisplayName("the public link is always rebuilt from the handle, never echoed")
    void canonicalUrls() {
        assertThat(INSTAGRAM.urlFor("karoo.honey")).isEqualTo("https://www.instagram.com/karoo.honey");
        assertThat(TIKTOK.urlFor("karoohoney")).isEqualTo("https://www.tiktok.com/@karoohoney");
        assertThat(X.urlFor("karoo_honey")).isEqualTo("https://x.com/karoo_honey");
        assertThat(FACEBOOK.urlFor("KarooHoney")).isEqualTo("https://www.facebook.com/KarooHoney");
        assertThat(FACEBOOK.urlFor("61591684209144"))
                .isEqualTo("https://www.facebook.com/profile.php?id=61591684209144");
    }

    @Nested
    @DisplayName("anything that is not a profile on that platform is refused")
    class Refused {

        @ParameterizedTest
        @ValueSource(strings = {
                "https://notinstagram.com/karoo",          // lookalike on a dot boundary
                "https://instagram.com.evil.example/karoo", // platform name inside another domain
                "https://instagr.am/karoo",                 // shortener
                "https://bit.ly/karoo", "https://linktr.ee/karoo",
                "https://evil.example/instagram.com/karoo"})
        void notThePlatform(String raw) {
            assertThat(refusal(INSTAGRAM, raw)).contains("Paste a link to your Instagram profile");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://www.instagram.com/p/C1abcDEF/", "https://www.instagram.com/reel/C1abc/",
                "https://www.instagram.com/karoo.honey/tagged/"})
        void instagramPostsAndPages(String raw) {
            assertThat(refusal(INSTAGRAM, raw)).containsAnyOf("post", "profile");
        }

        @Test
        void reservedPathsAreNotHandles() {
            assertThat(refusal(INSTAGRAM, "instagram.com/explore")).contains("profile");
            assertThat(refusal(X, "x.com/home")).contains("profile");
            assertThat(refusal(FACEBOOK, "facebook.com/groups/12345/")).contains("profile");
            assertThat(refusal(FACEBOOK, "https://www.facebook.com/sharer/sharer.php?u=x")).contains("profile");
        }

        @Test
        void postsOnEachPlatform() {
            assertThat(refusal(TIKTOK, "https://www.tiktok.com/@karoohoney/video/7300000000000000000")).contains("post");
            assertThat(refusal(X, "https://x.com/karoo_honey/status/1800000000000000000")).contains("post");
            assertThat(refusal(FACEBOOK, "https://www.facebook.com/KarooHoney/posts/12345")).contains("post");
        }

        @Test
        void tiktokShortLinksAndPathsWithoutAt() {
            assertThat(refusal(TIKTOK, "https://vm.tiktok.com/ZMabc123/")).contains("profile");
            assertThat(refusal(TIKTOK, "https://www.tiktok.com/karoohoney")).contains("profile");
        }

        @Test
        void facebookLinkShimIsNotFacebook() {
            // l.facebook.com is a facebook.com subdomain that redirects anywhere.
            assertThat(refusal(FACEBOOK, "https://l.facebook.com/l.php?u=https%3A%2F%2Fevil.example"))
                    .contains("Paste a link to your Facebook profile");
        }

        @ParameterizedTest
        @ValueSource(strings = {"javascript:alert(1)", "data:text/html,<script>alert(1)</script>",
                "ftp://instagram.com/karoo"})
        void notWebLinks(String raw) {
            assertThat(refusal(INSTAGRAM, raw)).contains("profile");
        }

        @Test
        void handleRulesAtTheirBoundaries() {
            assertThat(handle(INSTAGRAM, "a".repeat(30))).hasSize(30);
            refusal(INSTAGRAM, "a".repeat(31));
            refusal(INSTAGRAM, "karoo honey");     // space
            refusal(INSTAGRAM, "karoo-honey");     // hyphen not allowed
            refusal(TIKTOK, "k");                  // TikTok minimum is 2
            assertThat(handle(TIKTOK, "k".repeat(24))).hasSize(24);
            refusal(TIKTOK, "k".repeat(25));
            assertThat(handle(X, "k".repeat(15))).hasSize(15);
            refusal(X, "k".repeat(16));
            refusal(X, "karoo.honey");             // X handles have no dots
            refusal(FACEBOOK, "Shop");             // page names are at least 5
            refusal(FACEBOOK, "l.php");            // a Facebook route, not a page
        }
    }
}

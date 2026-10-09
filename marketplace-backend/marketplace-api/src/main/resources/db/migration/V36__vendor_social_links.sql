-- V36__vendor_social_links.sql
-- A store's links to its own social profiles, shown on its shop page.
-- Spec: seller-social-links.md (owner decisions D1-D3 taken as recommended:
-- Instagram, TikTok, Facebook and X only; no WhatsApp numbers; no personal
-- websites).
--
-- The HANDLE is stored, never a URL. The public link is rebuilt by the
-- server from a fixed template per platform (SocialPlatform.urlFor), so a
-- stored row can only ever become a link to that platform's profile page: a
-- lookalike domain, a redirect or a phishing page cannot be represented at
-- all. That is the main security property of the feature.
--
-- platform holds a SocialPlatform enum name, with no CHECK constraint, the
-- same reasoning as referral_source in V33: adding a platform is a code
-- change, not a migration.
--
-- One link per platform per store (the unique constraint), replaced in place
-- when the seller changes it. Removed with the store's account (CASCADE).
--
-- V36 is the next free version on main. The unmerged Paystack branch's
-- migration must renumber past this one: Flyway runs with out-of-order
-- disabled, so a lower version arriving after this is applied is refused.

CREATE TABLE vendor_social_links (
    id          BIGSERIAL    PRIMARY KEY,
    vendor_id   BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    platform    VARCHAR(20)  NOT NULL,
    handle      VARCHAR(100) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_vendor_social_platform UNIQUE (vendor_id, platform)
);

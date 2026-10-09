-- V35__vendor_profile.sql
-- A vendor's public store profile: a picture and a short bio, shown in the
-- home page's vendor spotlight and on their shop page (/shop/{id}).
--
-- Before this, the only thing the site knew about a store was its business
-- name. The spotlight filled the space with generic copy ("A store currently
-- selling on eRestyu...") and the shop page had nothing to say about who is
-- behind the stall, which is the part of a marketplace of small local sellers
-- that buyers actually care about.
--
-- Both nullable, and they stay optional: a vendor can sell without either,
-- and the storefront falls back to initials and no bio rather than inventing
-- one. Customers never have them (the profile is a public storefront, and a
-- customer has no public page).
--
-- avatar_key is an object key in the same R2 bucket as product photos
-- (vendors/{id}/avatar-{uuid}.{ext}); the public URL is derived from it, as
-- for product images, never stored. Every upload writes a new key, so the
-- immutable cache headers can never serve a stale picture.
--
-- bio is capped at 500 characters: a few sentences about the store, not a
-- page. The cap is also in the request validation; the column enforces it
-- for anything that writes without going through the API.
--
-- V35: V33 (seller referral source) and V34 (order test flag) are on main.
-- The unmerged Paystack branch's V32 now needs renumbering past this one.

ALTER TABLE users
    ADD COLUMN bio        VARCHAR(500),
    ADD COLUMN avatar_key VARCHAR(255);

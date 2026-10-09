# Seller social links on storefronts

Status: **built** (2026-09-30). Owner decisions D1-D3 taken as recommended.
Where the build differs from the first draft of this spec, the text below
has been updated to match what shipped.

## 1. Why

Most small sellers here already sell through their socials; that is where
their audience lives. Letting a store link its Instagram, TikTok and Facebook
does two things (Charne's framing, and the reason this moved from "park it"
to "build it"):

1. **Trust for new buyers.** A store with a live, active Instagram is visibly
   a real business. That matters on a young marketplace where nobody has
   reviews yet.
2. **A reason for sellers to send their followers here.** The bigger effect
   is traffic coming *in*: a seller who has a proper eRestyu storefront puts
   its link in their bio and stories. Outbound links are the visible half;
   the inbound half is what grows the marketplace.

The risk is **leakage**: a buyer who finds a product here and then buys it
through the seller's DMs. That costs the commission, and it takes the buyer
out of everything the platform does for them (checkout, delivery tracking,
the refund path). A profile link carries some of that risk; a direct phone
number carries most of it. §2 is mostly about where to draw that line.

## 2. Owner decisions

| # | Question | Recommendation |
|---|---|---|
| D1 | Allow a **WhatsApp number**? | **No, not in v1.** |
| D2 | Allow a link to the seller's **own website**? | **No, not in v1.** |
| D3 | Which platforms? | **Instagram, TikTok, Facebook, X**: the same four the site's own footer uses. |

**D1, WhatsApp: recommend no.** A WhatsApp number is a direct line to the
seller, which is exactly the channel an off-platform sale happens on. It is
also a different kind of data: a personal phone number, published on a public
page. The public store profile deliberately contains no phone number today,
and `VendorProfileTest.publicProfile` asserts that. Allowing WhatsApp means
reversing that decision on purpose, plus a clear POPIA notice at the point of
entry. If buyers need to reach sellers, the better long-term answer is asking
the seller a question *through* eRestyu, which keeps the conversation and the
sale on the platform.

**D2, own website: recommend no.** A social profile mostly points people at
content; a seller's own shop points them at a checkout that competes with
this one. It is the most direct leakage path there is, with none of the
social proof that justifies the other links.

**D3, platforms.** The four the footer already supports, so the validation
and icons are shared, not duplicated. YouTube and Pinterest can follow the
same pattern later if sellers ask; each one is a row in two lookup tables.

The rest of this spec assumes the recommendations. If D1 or D2 goes the other
way, §9 lists what changes.

## 3. Scope

**In v1**
- A store sets up to one link per platform on its **Profile** page
  (Account menu → Profile, where the picture and bio already are).
- The links show as icons on the **shop page** header (`/shop/{id}`).
- A **"Copy your shop link"** button on the Profile page, with a line
  suggesting it goes in their Instagram and TikTok bio. This is the inbound
  half of §1, and it is cheap.
- An admin can clear a store's links.

**Not in v1**
- Links in the home page spotlight or on product pages. The spotlight is an
  advert for the store *on eRestyu*, and a product page is where the buyer
  should stay until checkout. Both are the wrong place to send people away.
- Verifying that a link really belongs to the seller (no platform offers a
  cheap way to prove it; see §7).
- Counting clicks on outbound links, or tracking visits arriving from sellers'
  bios (see §8 for a follow-up).

## 4. Data model

One new table, **V36** (the next free version on main). The unmerged
Paystack PR (#70) must renumber its migration past it to V37: Flyway runs
with out-of-order disabled, so taking a higher number here and leaving #70
below it would have made #70's migration fail on boot instead.

```sql
CREATE TABLE vendor_social_links (
    id          BIGSERIAL    PRIMARY KEY,
    vendor_id   BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    platform    VARCHAR(20)  NOT NULL,   -- INSTAGRAM | TIKTOK | FACEBOOK | X
    handle      VARCHAR(100) NOT NULL,   -- normalised; see §5
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_vendor_social_platform UNIQUE (vendor_id, platform)
);
```

**Store the handle, never the URL.** The public URL is built by the server
from a fixed template per platform (§5). A stored URL can be anything (a
lookalike domain, a redirect, a phishing page); a stored handle can only ever
become a link to that platform. This is the main security property of the
whole feature.

`platform` is a Java enum (`SocialPlatform`), stored by name, with no CHECK
constraint, the same reasoning as `ReferralSource` in V33: adding a platform
should be a code change, not a migration.

## 5. Validation and normalisation

The seller may paste a full URL, a URL without `https://`, or a bare handle
with or without `@`. All reduce to one handle, validated against the
platform's own rules, and the canonical URL is rebuilt from it.

| Platform | Accepted input (examples) | Handle rule | Canonical URL |
|---|---|---|---|
| Instagram | `instagram.com/karoohoney`, `@karoohoney` | 1-30 chars: letters, digits, `.`, `_` | `https://www.instagram.com/{h}` |
| TikTok | `tiktok.com/@karoohoney`, `@karoohoney` | 2-24 chars: letters, digits, `.`, `_` | `https://www.tiktok.com/@{h}` |
| Facebook | `facebook.com/karoohoney`, `facebook.com/profile.php?id=615...`, `facebook.com/people/Name/615...` | page name: 5-50 chars, letters, digits, `.`; OR numeric id | `https://www.facebook.com/{h}` or `https://www.facebook.com/profile.php?id={id}` |
| X | `x.com/karoohoney`, `twitter.com/karoohoney`, `@karoohoney` | 1-15 chars: letters, digits, `_` | `https://x.com/{h}` |

Rules:
- **Host check on a dot boundary** (`instagram.com` or a subdomain of it,
  never `notinstagram.com`), exactly as `publishableSocialLinks` does for the
  footer. Reuse that logic server-side rather than writing it twice.
- **Profiles only.** A link to a post, reel, video or story is refused
  (`instagram.com/p/...`, `tiktok.com/@h/video/...`). The link is meant to
  say who the store is, not advertise one post.
- **Reserved paths refused** (`instagram.com/explore`, `facebook.com/groups`,
  `facebook.com/sharer`, `x.com/home` and similar): they parse as a handle
  and are not one.
- **Shorteners and redirects refused** (`bit.ly`, `linktr.ee`, `l.facebook.com`).
  A redirect is a URL we cannot vouch for.
- **Facebook numeric ids** are real and common (eRestyu's own page is one),
  so `profile.php?id=` and `/people/{name}/{id}` both normalise to the
  numeric form.
- **Handles are lowercased** for Instagram, TikTok and X, which treat them
  case-insensitively; Facebook page names keep their case.
- **The server never fetches the URL.** No "check this link exists" call:
  that is a server-side request to an address a user chose, and the platforms
  block or rate-limit scrapers anyway. Validation is purely by shape.
- **`http://` is accepted as input** and rebuilt as `https://`, since only
  the handle is kept. Any other scheme (`javascript:`, `data:`, `ftp:`) is
  refused.
- **Blank clears** that platform's link.

Validation errors are field-keyed, the same shape the registration form
already renders: `{"instagram": ["That doesn't look like an Instagram profile link"]}`.

## 6. API

Own links, vendor only (403 for anyone else). A separate save endpoint
rather than a field on the bio PUT: that PUT treats a missing bio as "clear
it", so sharing it would have let saving links wipe the bio.

```
GET  /api/v1/account/profile               -> OwnProfile gains: socialLinks: { instagram, tiktok, facebook, x }  (canonical URLs or null)
PUT  /api/v1/account/profile/social-links  -> body: { instagram?, tiktok?, facebook?, x? }  (raw input; absent or null = unchanged, "" = clear)
```

A save is all-or-nothing: every field is validated first, and if any fails,
the response is 400 with every failing field keyed, and nothing is written.

Public, on the existing store profile, so the shop page needs no new call:

```
GET  /api/v1/vendors/{id}        -> VendorProfile gains: socialLinks: [{ platform, url }]   (only the set ones, fixed order)
```

`/api/v1/vendors/spotlight` does **not** include them (§3).

Admin:

```
POST /api/v1/admin/vendors/{id}/social-links/clear   body: { reason }  -> clears all of a store's links; reason required, logged
```

POST with a body rather than DELETE, so the reason is validated like any
other input and never sits in an access-log URL. On the shop page, admins see
a "Remove links (admin)" button that asks for the reason.

## 7. Safety and privacy

- **Links render with `rel="nofollow noopener noreferrer ugc" target="_blank"`.**
  `ugc` and `nofollow` tell search engines these are user-supplied, so
  eRestyu's reputation is not lent to whatever they point at.
- **Impersonation.** Nothing stops a seller linking someone else's account.
  There is no cheap proof of ownership, so the controls are the admin clear
  (§6), a note on the Profile page, and a line in the Selling section of the
  Terms page (added with this feature).
- **Personal data.** With D1 and D2 as recommended, nothing here is contact
  data: a public social handle the seller chose to show. The Profile page
  says the links are public, beside the fields.
- **The public profile privacy test stays.** `VendorProfileTest.publicProfile`
  keeps asserting no email, personal names, phone or banking in the public
  profile. Add social links to the fields it expects, not to the ones it
  forbids.

## 8. Measuring whether it works

The claim to test is §1.2: sellers send their followers here.

- **v1, free:** the admin "Where sellers came from" counts already exist.
  Watch whether stores that add links list more products and sell more than
  those that don't. It is weak evidence, but it is free.
- **Follow-up, small:** make the "Copy your shop link" button copy
  `https://erestyu.com/shop/{id}?ref=bio`, and record `ref` on shop page
  visits. That turns "did sellers' bios bring buyers" into a number. It is
  deliberately not in v1, to keep this change to one concern.

## 9. If D1 or D2 goes the other way

- **WhatsApp allowed:** store the number in E.164 form, build a `wa.me/` link
  (never show the raw number on the page), add an explicit POPIA consent line
  at entry ("this number will be shown publicly on your shop page"), move
  `phone` from the forbidden to the expected list in the privacy test, and
  consider showing it only to signed-in buyers.
- **Own website allowed:** it is the one link that must accept an arbitrary
  domain, so the handle-only property (§4) does not hold for it. It needs
  https-only, no IP addresses or `localhost`, a shortener blocklist, and
  admin review before it shows publicly.

## 10. Tests

Backend:
- Each platform: every accepted input form normalises to the same canonical
  URL; handle-rule boundaries (Instagram 30 vs 31 characters, and so on).
- Refused: lookalike hosts, shorteners, post and video URLs, reserved paths,
  `javascript:`, `data:` and `ftp:` URLs, handles with illegal characters.
- Facebook `profile.php?id=` and `/people/Name/{id}` both normalise to the
  numeric form.
- One link per platform (a second PUT replaces, it does not add); blank
  clears; absent leaves unchanged.
- Customers get 403; the public profile lists only set links, in fixed order;
  spotlight does not include them.
- Admin clear removes all of a store's links and requires a reason.
- The public profile privacy test still passes.

Frontend:
- Profile page shows the field-keyed errors against the right field.
- Shop page renders only set links, as icons with accessible names, and with
  the `rel` values above.
- "Copy your shop link" copies the canonical shop URL.

## 11. Effort

About a day with tests: one migration, one enum, a normaliser (most of the
work), two DTO changes, one admin endpoint, and the Profile and shop page UI.
The footer's icons (`SocialIcon`) and host check are reused.

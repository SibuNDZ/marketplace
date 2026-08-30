# Google sign-in: decision and design

Status: **Decided — build it** (scoped to "Continue with Google" on login and
registration; sign-in method management explicitly deferred).
Date: 2026-08-30.

## 1. The decision

Add "Continue with Google" as a second way to prove identity. It does not
replace email + password; it feeds the same `User` row, the same JWT + refresh
session, the same everything downstream.

Why yes:

- **The market is Android.** 75.5% of South African mobile devices run Android
  (Statcounter, July 2026), and an Android phone has a Google account signed in
  by construction. Most of the iOS quarter has a Gmail address too. For the
  people we watched struggle at Milnerton, "tap the Google button" replaces
  typing an email, inventing a password, opening the inbox, and finding the
  verification mail.
- **It removes our email dependency at the worst moment.** A Google signup
  needs no verification email: Google has already proved the mailbox
  (`email_verified` claim). Every signup that comes through Google is one that
  cannot be lost to a spam folder or a Resend hiccup.
- **The seam already exists.** `AuthService.issueSession(User)` is
  method-agnostic: anything that produces a `User` gets a session. Google
  becomes a third caller of that method (after register and login), not a
  parallel auth stack.

Why not more than this:

- The Lovable prototype's "sign-in method management" (connect/disconnect
  Google, primary method) is deferred. Disconnecting Google from an account
  with no password is a lockout; managing that safely is real work with no
  user asking for it. The escape hatch already exists: `resetPassword` sets a
  password on any account and works for null-password users unchanged.
- No Apple sign-in. The App Store rule that forces it applies to native apps
  offering third-party login; eRestyu is a web app. Revisit if we ship native.
- No Facebook. Nothing in the field notes asks for it.

## 2. Flow (settled)

**Google Identity Services (GIS) ID-token flow**, not the server-side OAuth2
redirect dance:

1. Frontend loads `https://accounts.google.com/gsi/client` and renders the
   Google button on the login and registration pages.
2. Google hands the browser a **credential** — a signed JWT (ID token) with
   `sub`, `email`, `email_verified`, `given_name`, `family_name`.
3. Frontend `POST /api/v1/auth/google { credential }`.
4. Backend verifies the JWT against Google's JWKS (issuer
   `https://accounts.google.com`, audience = our client ID) using a Nimbus
   `JwtDecoder` — the `spring-security-oauth2-jose` artifact, one dependency,
   no OAuth2 client machinery, no client secret, no server-side sessions.
5. Link-or-create (rules below), then `issueSession(user)` — the response is
   byte-identical in shape to a password login.

Rejected alternative: Spring Security `oauth2Login()` (authorization-code
redirect). It is session-oriented, drags in a client secret and redirect URIs,
and fights the stateless JWT architecture for zero gain at our scale.

## 3. Account model (settled)

**Migration V30:**

- `users.google_sub VARCHAR(255) NULL UNIQUE` — Google's stable subject ID.
  The join key is `sub`, never email: emails can change at Google, `sub`
  cannot.
- `users.password` → `NULL`able. Google-only accounts have no password.

**Entity change:** `User.password` currently carries `@NotBlank @Size(min=8)`
— that would refuse to persist a Google-only user. Move to plain nullable;
the real guards already live on the DTOs in `AuthDtos.java` (register and
reset both validate 8–100 chars there), so nothing is lost.

**Login hardening (one line):** login is already timing-safe against unknown
emails (`DUMMY_HASH` at `AuthService.java:180`). Extend the same treatment to
null-password accounts:
`user != null && user.getPassword() != null ? user.getPassword() : DUMMY_HASH`.
A password attempt against a Google-only account fails with the same generic
"invalid credentials" in the same time — we never disclose that an account is
Google-backed.

**Link-or-create rules for `POST /api/v1/auth/google`:**

1. Reject any credential where `email_verified` is not `true` (defense in
   depth; Google workspace edge cases exist).
2. Look up by `google_sub`. Found → sign in. Do NOT sync the stored email to
   Google's current one; our email is the order/notification identity and must
   not change silently.
3. Else look up by email (lowercased). Found → set `google_sub`, set
   `isVerified = true` if not already (Google's proof of inbox control is
   strictly stronger than our email link), sign in. This is safe *because* of
   rule 1 — linking on an unverified Google email would be an account-takeover
   vector.
4. Else create: role `CUSTOMER`, `isVerified = true`, `password = NULL`,
   names from `given_name`/`family_name` (fallback: email local part / empty),
   username generated from the email local part with the existing
   `[a-z0-9_]`/30-char normalisation plus a numeric suffix on collision.
5. Vendors: nothing new. A Google signup is a CUSTOMER; the existing
   become-a-vendor upgrade collects the business name exactly as it does for
   password accounts.

**Escape hatch:** a Google-only user who wants a password uses forgot-password.
`resetPassword` encodes-and-sets with no assumption about an existing hash and
already marks the account verified. Zero changes needed.

## 4. Configuration and rollout

- `GOOGLE_CLIENT_ID` env var (backend) and `VITE_GOOGLE_CLIENT_ID` (frontend).
  A client ID is public by design — not a secret, safe in the frontend bundle.
  No client secret exists in this flow at all.
- Feature is dark when unconfigured: frontend hides the button when the var is
  absent; backend returns 503 from `/auth/google` when unset. Staging gets it
  first with a staging-origin client ID (or the same ID with staging origin
  added).
- Rate-limit `/auth/google` the same as `/login`.

## 5. Testing (house pattern: seam, not live calls)

Wrap verification in a `GoogleIdentityVerifier` interface (mirrors
`PayfastValidator`): production impl = Nimbus decoder with issuer/audience
checks; tests inject a stub returning canned claims. Cases to pin:

- new-user create (username collision included), existing-email link,
  existing-sub sign-in, `email_verified=false` rejected, unconfigured → 503
- password login against a Google-only account → generic 401
- resetPassword on a null-password account sets one (escape-hatch pin)
- register with an email that already has `google_sub` → normal
  email-already-registered behavior (the account exists; that message is
  already public knowledge via registration, no new oracle)

## 6. Owner actions (blocking, ~15 minutes)

1. Google Cloud Console → create project (or reuse) → **OAuth consent screen**:
   app name eRestyu, logo, link the live privacy policy page. Email/profile
   are non-sensitive scopes — no Google verification review needed.
2. **Credentials → OAuth Client ID (Web application)**. Authorized JavaScript
   origins: `https://erestyu.com`, `https://www.erestyu.com`, the staging URL,
   `http://localhost:5173`. No redirect URIs needed for the GIS token flow.
3. Hand me the client ID; I set the Railway/Pages vars.

## 7. Honesty/privacy notes

- Privacy page gains a traced-to-code line: signing in with Google shares your
  name and email address with us; we store a Google account identifier to
  recognise your sign-in; we receive no contacts, no calendar, nothing else.
- No "recent sign-ins with approximate location" in this slice. If the
  sessions-list feature ships later, IP-derived location gets its own privacy
  line or gets dropped.

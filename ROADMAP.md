# Roadmap and standing decisions

This file exists to be **checked before building**, not read once. It sits in
the repo root beside the other memory files (`hibernate-locking.md`,
`product-variants.md`, `payfast-port.md`, `vendor-payouts.md`, `yoco-port.md`)
so that anyone — human or agent — working on a feature can find the decision
that already governs it.

It was previously drafted outside the repo. That is the direct cause of two
gaps in the 12–13 Aug 2026 work: nothing in the codebase recorded the parked
features or the reference-pricing rejection, so nothing could be checked
against them.

---

## The house rule: derived, not attested

**Every trust-sensitive number a shopper sees is derived from something the
system recorded. None is accepted as self-report from a party with an
incentive to inflate it.**

This is the principle behind the July 2026 honest-signals slice
(commit `45b86f1`), and it is not a preference — it is the line the platform
holds. Current examples, all live:

| Signal | Derived from |
|---|---|
| `soldCount` ("3 sold") | order items in PAID/SHIPPED/DELIVERED — refunds excluded |
| Star rating | reviews, Bayesian-shrunk, gated on a delivered order |
| "No reviews yet" | absence of reviews — never a placeholder score |
| "In demand, N bought" | distinct buyers in 24h, vendor self-purchases excluded |
| `views_30d` | recorded view events, 90-day retention |

**Before adding any number a shopper reads as a reason to trust or hurry,
ask: what recorded fact derives it?** If the answer is "the vendor typed it",
it does not ship in that form.

Vendor attestation is acceptable only where the vendor is asserting
responsibility for something they alone control and no party is advantaged by
lying (e.g. "I hold the rights to this image"). It is **not** acceptable where
the vendor gains commercially from the assertion and no other party in the
flow can catch a false one.

### Composed claims: a default that renders as a promise (added 2026-10-03)

The cases above were each one untrue string that someone wrote. This shape is
different: **two individually true things compose into an untrue claim**, and
no review of either file on its own catches it.

The case that surfaced it: `users.delivery_fee` is `NOT NULL DEFAULT 0`, the
vendor dashboard pre-fills the field with `0.00`, and the shipping page says
vendors who offer free delivery add no fee line. Together they tell every
shopper that every vendor delivers free, when no vendor ever chose that. The
column cannot even tell "chose R0" apart from "never touched it". The same
class: the cart panel's tick boxes changed its subtotal while checkout always
ordered the whole cart, and an "R200 Min. to checkout" pill stated a rule the
backend never enforced (both removed 2026-10-03).

**Before shipping copy or a default, ask:** if this value is the untouched
default, what does the shopper-facing copy say about it, and did anyone
actually decide that? A default that shopper copy reads as a commitment must
either be an explicit recorded choice (nullable until chosen, or a
chosen-at timestamp) or render as "not set", never as the promise.

---

## Rejected — permanent, not pending a trigger

These were removed in the honest-signals slice and do not come back in
attested form.

- **Fabricated reference pricing** — discount %, struck-through "was" prices
  with no real prior price behind them. False price-advantage representation
  under the SA Consumer Protection Act (s30 treats an advertised former price
  as a claim the goods were actually offered at it).
- **Flash-sale countdowns** — manufactured urgency toward a sale that exists
  nowhere in the backend.
- **Verified-seller badges** — no verification flow exists to back them.
- **Free-shipping tags, MOQ and shipping-origin chips** — commercial promises
  with no system behind them.

### Status: compare-at pricing (`products.original_price`, V23)

**PAUSED 2026-08-13, before any vendor set one. Zero rows in production.**

Built 13 Aug as a vendor-attested field with a `CHECK (original_price >
price)` guardrail and CPA disclosure on the vendor form. On review the
guardrail was found to prove only that the number is arithmetically bigger
than the price — not that it was ever charged. That made it the platform's
only self-reported trust-sensitive number, against the house rule above.

`ProductService.applyRequest` now refuses any new or changed value (400), and
the vendor form input is removed. The column, the CHECK, and the validation
stay — they are all needed by the replacement.

**The version that ships instead is derived:**

1. A `price_history` table recording real price changes, timestamped.
2. A "was" price is eligible only when there is a genuine recorded prior
   price that was **live for a real minimum duration** before being lowered.
3. Precedent for the shape: the EU Omnibus Directive requires a reference
   price to be the lowest genuine price in the preceding 30 days, precisely
   because vendor-typed "was" prices are a known, worldwide abuse pattern.

A vendor cannot fabricate a history that did not happen.

---

## Parked — pending an explicit trigger

Not rejected. They are the right features later and the wrong ones now,
because the data to make them work does not exist yet. **Do not build these
until the stated trigger has actually fired** — check, do not assume.

| Feature | Trigger |
|---|---|
| **Visual search** (search by uploading a photo) | 1,000+ product images |
| **Conversational search** (natural-language query understanding) | 500+ products with failed search queries |
| **Minimum order value** (server-enforced at placeOrder) | Any vendor's delivery fee above R0, **or** any product listed below R18 |

**Minimum order value: the number is computed, not trusted.** Breakeven per
card transaction is **goods ≥ 0.5 × total delivery + R17.25**: the 10%
commission is earned on goods only, but the card fee is charged on goods plus
delivery. With no delivery fees that reduces to goods ≥ R17.25. The minimum
belongs on the **goods** subtotal across the whole cart; a minimum on the full
charge does not protect anything (R50 goods + R150 delivery passes R200 and
still loses money).

Computed 2026-10-03 on these assumptions, and **recomputed, not reused, if any
of them change**: 10% commission; Paystack local card pricing of 2.9% + R1
excl. VAT, with the R1 waived under R10 (Capitec Pay and Ozow at 2% with no
flat fee never lose money); 15% VAT on that fee treated as a cost because
ERESTYU is not VAT-registered; one delivery fee per vendor per order. Paystack
is not yet on `main` (`feat/paystack-provider`), so if a different provider
goes live, its pricing replaces these numbers.

When it ships, the UI shows the minimum **only alongside** the server check.
The earlier "R200 Min. to checkout" pill was removed for stating a rule that
nothing enforced.

**Static FAQ widget (shipped)** occupies the conversational assistant's future
UI slot honestly. When the real trigger fires, this becomes the fallback /
no-match state inside the real assistant, not a separate thing to remove. It
has no input field and does no fuzzy matching, deliberately — a box that takes
a typed question and returns nothing is worse than one that never asked, and a
"closest guess" against unrelated canned answers is a wrong-answer machine.
Its content lives in `frontend/src/data/faqContent.ts`, shared with `/help`, and
nothing in it may be the first place a claim appears.

### What is already built, and why it is not these

- **Full-text search with curated synonyms** (V21) — lexical matching on the
  shopper's typed words. Proportionate to the catalogue size. Live.
- **Semantic related products** (V22, Voyage embeddings) — "You might also
  like". Embeds each product's *own* name and tags and compares meaning
  between products.

V22 is **not** the parked search work, and the distinction is the point:
it is not shopper-facing search, it takes no query, it needs no interaction
data or query volume, and it touches no images. It works on a catalogue with
zero sales because the vector comes from the product's own text.

Genuinely deferred within it, an infrastructure decision rather than a
feature one: moving the embedding column from `DOUBLE PRECISION[]` to
`pgvector`. Trigger stated in the V22 migration — **a few thousand products**,
or when query-time semantic search is wanted. At the current catalogue an
ANN index earns nothing and the planner would ignore it.

**Known quality caveat:** at ~13 products, similarity shelves are often
sparse or empty. That is honest (the 0.55 cosine floor returns nothing rather
than padding) but not yet especially useful. A quality observation to watch,
not a principle problem.

---

## Vendor payouts — commission ledger (shipped 2026-08-20)

The collect-then-disburse tier from `vendor-payouts.md` is built: every order
that goes PAID writes one ledger entry per vendor (same transaction as the
status flip), the admin Payouts page runs approve → Nedbank bulk CSV →
mark-paid, and vendors onboard (banking + versioned terms) from their
dashboard. Gateway splits remain future work, PayFast-conditional.

**Three config placeholders are NOT decisions** — see OWNER ACTIONS in the PR
and the ⚠ comments in `application.yml`: the commission rate (0.125), the
payout window (7 days), and the selling gate (`selling-gate-enabled`,
default OFF). The gate ships built-and-tested but disabled: enabling it while
the rate is a placeholder would make every vendor unsellable under terms
quoting a number nobody decided. Setting the real rate, the window, and the
flag is ONE owner action. The public Fees section renders the commission
sentence only once the gate is live — a placeholder is not published as if it
were a decision (house rule above).

---

## Payment structure, DECIDED 2026-10-17: Paystack subaccounts

Vendor funds settle through Paystack subaccounts with a dynamic flat split per
transaction; eRestyu receives only its commission and never holds vendor
money. Collect-then-pay (the ledger plus Nedbank bulk export above) stays
built as the fallback and is deleted only when Paystack confirms in writing
that it is not needed. Spec: `paystack-subaccounts.md`. The same rollout
turns the selling gate ON, bumps the payout terms to v4, and introduces vendor
verification (bank account plus identity document checked through Paystack's
`/bank/validate`; the document number is never stored, only its type, last
four characters and the result).

Two findings from the 2026-10-17 audit of what vendors actually accept, kept
here so they are not rediscovered:

- The v3 payout sentence promises payment "following delivery confirmation",
  but `PayoutAdminService.approve()` checks only entry status, never whether
  the order is DELIVERED. The rule held because the owner looked before
  approving. Under subaccounts the sentence is replaced; if collect-then-pay
  ever becomes the live path, enforce DELIVERED in `approve()` first.
- The ledger can void and claw back a vendor's share on refund
  (`CommissionLedgerService.reverseOnRefund`), but nothing the vendor accepted
  authorised it. v4 of the seller terms grants set-off explicitly. A contract
  must authorise what the code can do, in the same way copy must describe
  what the code does.

## Fault isolation for external providers

Every third-party API is optional at boot. A missing or bad key degrades the
one feature that uses it and never crashes the app. The pattern, applied
identically by `AnthropicConfig`, `EmailService`, `ObjectStorageService` and
`EmbeddingClient`:

- Key read with a **blank default** (`${PROVIDER_KEY:}`), never fail-fast.
- An `isConfigured()` check before any call.
- Every failure path returns empty/no-op rather than throwing to the caller.
- Keys live in Railway env vars only — never in a committed file.

Fail-fast is reserved for things the app genuinely cannot run without:
`JWT_SECRET`, the Stripe keys, the R2 credentials.

Current providers: Anthropic (listing drafter), Voyage AI (embeddings),
Resend (email), Cloudflare R2 (images), Stripe / PayFast / Yoco (payments).

---

## Before you build

1. Search this file for the feature area.
2. If it is **rejected**, it does not ship — including in a modified form that
   appears to dodge the objection. Raise it instead.
3. If it is **parked**, check whether the trigger has actually fired. Report
   the number you checked.
4. If it introduces a shopper-visible number, name the recorded fact it
   derives from.
5. If it adds an external provider, follow the fault-isolation pattern above.

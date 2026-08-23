# Vendor payouts — roadmap

Status: **the ledger tier (Tier 1 below) SHIPPED 2026-08-20** on
`feature/vendor-payouts` — commission ledger written at PAID time, admin
approve → Nedbank bulk CSV → mark-paid flow, vendor banking + versioned
terms with a config-flagged selling gate. Split payments (Tier 2) remain
future work and PayFast-conditional (§6a). Outstanding owner actions are
listed at the end of this file.

(A note on numbering: the build prompt for the shipped slice called these
tiers 2 and 3 — one higher than this file. This file's numbering is
canonical: Tier 0 nothing, Tier 1 ledger, Tier 2 splits.)

The goal is that a vendor's money never lands in eRestyu's Nedbank account
at all. Collecting everything into one account and disbursing by hand means
holding other people's money, which is where the NPS Act / FICA exposure
lives, plus a payout system to build and a support ticket for every failed
transfer. Splitting at payment time removes all three at the infrastructure
level rather than managing them.

## 1. The finding that shapes everything

Verified from PayFast's own developer documentation (developers.payfast.co.za,
Split Payments section, read 2026-08-02 — **not** from integration blogs,
which claim "two or more vendors"):

> Only one receiving merchant can be allocated a Split Payment, per split
> transaction.

**One third-party recipient per transaction.** That is a hard constraint, and
eRestyu is a multi-vendor marketplace whose carts routinely contain several
vendors — the delivery-fee design already assumes this ("one flat fee per
unique vendor in the cart"). So splits do NOT solve multi-vendor orders as
they stand.

This is the single most important thing to raise with PayFast, and it is a
sharper question than "do you support splits":

> For a marketplace order containing items from three different vendors, can
> a single payment be split to three receiving merchants? If not, what is the
> supported pattern — one transaction per vendor, or something else?

## 2. What splits actually look like (verified)

- eRestyu is the primary receiving merchant; the split portion goes to the
  **third party** (the vendor). So the commission model inverts from the
  obvious one: we receive, and the vendor's share is split out.
- Two configuration routes: **global** (PayFast support configures it, applies
  to every transaction) or **direct request** (per-payment `setup` field,
  takes precedence). We want direct request — the split differs per order.
- Payload rides in a `setup` form field as JSON:

```json
{ "split_payment": { "merchant_id": 10000105, "percentage": 10,
                     "amount": 500, "min": 100, "max": 100000 } }
```

- Amounts in **cents**. `percentage` or `amount`, or both.
- Calculation order matters: if both are given, the percentage is taken
  first, then the fixed amount comes off the remainder.
- `min` / `max` clamp the split. Worth using: `min` protects a vendor on tiny
  orders, `max` caps our exposure on a mispriced one.
- **Integration gotcha, already relevant to our code:** the `setup` field is
  explicitly NOT included in the signature. Our `PayfastSignature.sign()`
  iterates the whole field map, so `setup` must be excluded there or every
  split payment will fail signature validation. Noted now so it is not
  discovered at go-live.

## 3. The three tiers

**Tier 0 — before 2026-08-20.** Commission was not tracked at all. Money
landed in the Nedbank account and nothing computed what was owed to whom.

**Tier 1 — commission ledger + bank bulk file. SHIPPED 2026-08-20.**
What shipped (V27–V29):

- `vendor_payout_entries`: one PRIMARY row per (order, vendor), written in
  the SAME transaction as PENDING→PAID (`CommissionLedgerService`, hooked at
  the single `setStatus(PAID)` site in `PaymentEventService`, so all three
  providers are covered by one hook). Snapshotted rate, append-only history;
  full refunds VOID unpaid rows and claw back paid ones with negative
  ADJUSTMENT rows. Partial-refund adjustments exist as a tested mechanism
  with no HTTP trigger (no partial-refund flow exists yet).
- Arithmetic per §5: `net = items − commission + delivery fee`, fee never
  commissioned, commission rounded DOWN so the platform absorbs the
  sub-cent.
- Admin flow (`/admin/payouts`): pending grouped per vendor → approve into a
  batch → export Nedbank bulk CSV (`NedbankBulkPaymentsExporter`, column
  mapping isolated in that one file because the real NetBank profile is
  UNCONFIRMED) → mark paid with the bank reference. Batch lifecycle is
  derived from timestamps with approved_by/paid_by audit fields. Upload and
  authorisation stay human, deliberately — at this volume that is a control,
  not a limitation.
- Vendor onboarding: banking details (masked to last 4 on every surface
  except the bank file) + versioned terms built from the same config the
  ledger charges. A selling gate (409 at checkout for un-onboarded vendors)
  is built and tested but OFF by default — see owner actions.
- Backfill for pre-ledger PAID orders: dry-run every boot, commits only
  under `PAYOUT_BACKFILL_COMMIT=true` for one deploy.

**Tier 2 — PayFast Split Payments (the real answer).**
Vendor's share is routed at payment time; it never touches our account.
Requires each vendor to have their own PayFast account, linked during vendor
onboarding. That is real friction, and also a reasonable seriousness filter
for a vendor who wants automatic payment.

Given §1, Tier 2 most likely lands as: **single-vendor orders split
automatically; multi-vendor orders fall back to Tier 1** until PayFast
confirms a multi-recipient pattern. That fallback is not a hack — it is the
honest consequence of the constraint, and single-vendor orders are the
common case at this stage.

## 4. Do now (costs nothing, the account conversation is already open)

While onboarding with PayFast, ask in the same thread:

1. The multi-recipient question in §1, verbatim.
2. Is Split Payments available on our account type, and what enables it?
3. What fees apply per split, and to whom are they charged?
4. Does the receiving vendor need a full merchant account, or is a lighter
   account type sufficient?

Getting it enabled from day one beats retrofitting, even if we implement only
when the second selling vendor arrives.

## 5. The fee decision — DECIDED: the platform absorbs it

**Settled 2026-08-02. The vendor receives exactly their expected share:
sale price plus their delivery fee, minus commission. Nothing else.**

The rejected alternative was folding processing fees into the vendor's side.
That makes a vendor's payout vary with the card-versus-EFT mix of whoever
happened to buy from them, which turns every payout statement into a
reconciliation question. It fails the honest-fee rule in spirit even when
disclosed, because the vendor cannot predict their own income.

Consequence for pricing: the commission percentage must be set knowing it
has to cover the processor's cut (~3% on cards, varies by method) plus
margin. That is a one-time pricing decision by the operator, not a recurring
surprise for the vendor. **The percentage itself is still unset** — a
business call, not an engineering one.

Implementation note for whoever builds the ledger: "amount owed to vendor"
is therefore `sum(item price snapshots) + their delivery fee - commission`,
with the processing fee absent from that formula entirely. It comes off
eRestyu's side.

This belongs in the Fees section of How It Works **the day commission goes
live**, not after a vendor asks why the number is short.

## 5b. Provider strategy — DECIDED: Yoco ALONGSIDE PayFast

**Settled 2026-08-03. Yoco does not replace PayFast; both live behind
`PAYMENTS_PROVIDER`.**

The two do different jobs and the flag makes keeping both nearly free:

- **Yoco** is the fast route to accepting real money. Onboarding is quick
  and the integration is merged and tested. It is **card-only** and has no
  split mechanism, so while Yoco is the live provider the payout model is
  still collect-then-disburse — i.e. Tier 1 (ledger + Nedbank bulk file),
  with the NPS Act / FICA considerations in §1 still applying.
- **PayFast** remains the payout strategy, because Split Payments is what
  removes the money-holding problem structurally rather than managing it.

So the sequencing is: take real money on Yoco first if PayFast onboarding
drags, and move to PayFast for splits when the merchant account and the
multi-recipient answer (§1) are in hand. Switching is an env var, not a
rewrite — that is the whole point of the provider flag.

**Consequence to keep in view:** every day the live provider is Yoco is a
day vendor money lands in the Nedbank account and must be paid out by
hand. That makes the Tier 1 commission ledger MORE urgent, not less.

## 6. Trigger

Split payments moves from "parked until 3+ vendors" to **"ask PayFast now,
implement at the second selling vendor"**. The trigger got cheaper because
the onboarding conversation is already open.

### 6a. This entire trigger is PayFast-CONDITIONAL (added with the Yoco port)

Everything above assumes PayFast is the live processor. It is not, as of the
Yoco port: `PAYMENTS_PROVIDER` now selects `stripe | payfast | yoco`, and the
intended live provider is **yoco**, whose Checkout API has **no split-payment
equivalent** — one merchant receives, full stop.

So the trigger above fires only if PayFast is selected. Under Yoco:

- Payouts move entirely to the **ledger tier** (section 5's formula is
  unchanged and becomes the only mechanism, not an interim one).
- The "ask PayFast now" question is moot while yoco is live; do not spend the
  onboarding conversation on it.
- The one-recipient-per-transaction constraint stops being a blocker to work
  around, because there is no split path to be blocked from. Order #11 in
  production (Armani watch from vendor 29 + Lancôme cream from vendor 2, one
  R5105 payment) is the multi-vendor shape that a ledger has to settle by
  hand, and it is the regression case to keep.

The `setup`-field signature landmine in section 2 stays true and stays
dangerous, but it is now dormant code: it can only bite a deploy that switches
back to PayFast AND starts sending splits.

## 7. Outstanding owner actions (post-ledger, 2026-08-20)

None of these are engineering decisions; the code carries placeholders with
⚠ comments in `application.yml` until they are made:

1. **Set the real commission %** (`PAYOUT_COMMISSION_RATE`; 0.125 is a
   placeholder, not a decision — §5's pricing note applies) and the **payout
   window** (`PAYOUT_WINDOW_DAYS`), and confirm the **weekly cadence** the
   terms sentence promises.
2. **Flip the selling gate** (`PAYOUT_SELLING_GATE=true`) in the same act —
   it is off so vendors are not gated behind terms quoting placeholder
   numbers. Flipping it also makes the public Fees section render the
   commission sentence.
3. **Confirm the real Nedbank NetBank bulk-payment CSV profile** and adjust
   `NedbankBulkPaymentsExporter` (one file) to match.
4. **Run the backfill once** (`PAYOUT_BACKFILL_COMMIT=true` for one deploy)
   after reviewing the dry-run log — pre-ledger PAID orders (e.g. order #11's
   two vendors) get their entries at TODAY'S rate, because no historical rate
   ever existed.
5. **Decide whether vendor balances sit in a ring-fenced account** while the
   platform is collect-then-disburse (NPS Act / FICA posture, §5b).

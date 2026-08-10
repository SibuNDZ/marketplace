# Vendor payouts — roadmap

Status: **decided direction, blocked on a question to PayFast.** No code yet.

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

**Tier 0 — today.** Commission is not tracked at all. Money lands in the
Nedbank account and nothing computes what is owed to whom.

**Tier 1 — commission ledger + bank bulk file (interim).**
The queued ledger slice should gain one feature: export a payout batch
(vendor, bank details, amount owed, reference) in Nedbank's bulk payment
upload format. Fully automated payments from an SME account are not
realistically available — bank payment APIs are enterprise territory — but
bulk file upload is. The manual step shrinks from "capture each payment by
hand" to "upload one file, authorise once", and the authorisation stays
human deliberately. At this volume that is a control, not a limitation.

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

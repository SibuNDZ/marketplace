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

## 5. The fee decision, which is a promise not a config value

Whoever absorbs the processing fee must be decided explicitly:

- **Platform absorbs** — vendor receives exactly the number they expect.
- **Vendor's share carries it** — vendor receives slightly less than list.

This is the payout-transparency promise in concrete form. Whichever way it
goes, it belongs in the Fees section of How It Works **the day commission
goes live**, not after a vendor asks why the number is short.

## 6. Trigger

Split payments moves from "parked until 3+ vendors" to **"ask PayFast now,
implement at the second selling vendor"**. The trigger got cheaper because
the onboarding conversation is already open.

# Paystack subaccounts and vendor verification: spec

Status: **spec, ready to build once the Paystack branch is merged.** Decided by
the owner 2026-10-17: vendor funds settle through Paystack subaccounts with a
dynamic split per transaction (first option). The collect-then-pay path
(commission ledger + Nedbank bulk export, shipped 2026-08-20) stays built and
becomes the fallback if Paystack's marketplace review requires it.

Blocked on: (1) merging `feat/paystack-provider` (V37 is already numbered past
V36; the branch is level with `main` as of 2026-10-17); (2) answers to the
three open questions in §10, which change the refund and settlement wording
but not the data model. Build everything in §4 to §7 in Paystack **test mode**
in the meantime.

Sources, fetched 2026-10-17 and to be re-verified before implementing:
paystack.com/docs/payments/split-payments, /docs/payments/multi-split-payments,
/docs/api/subaccount, /docs/api/verification, /docs/api/miscellaneous,
/docs/payments/refunds, support.paystack.com article 360012149479 ("When will
I receive my payouts"), support2.paystack.com article 12219071815964
(managing subaccounts). Anything not stated in those pages is marked
**UNVERIFIED** below and must not appear in vendor- or buyer-facing copy.

## 1. Why subaccounts

- eRestyu stops holding vendor money. The buyer's payment is split by
  Paystack at the transaction; each vendor's share settles to the vendor's own
  bank account on Paystack's schedule. This is the structure most likely to
  settle the "receiving payments on behalf of vendors" and "holding funds"
  questions in Paystack's marketplace review, and it removes the TPPP
  exposure of collecting and disbursing ourselves.
- Paystack holds the **first** payout to a new or updated subaccount until we
  verify the subaccount in the Paystack dashboard (support article
  360012149479). That is a vetting step we get from the provider, not one we
  claim.
- Paystack's `POST /bank/validate` (South Africa) checks a bank account against
  the holder's name **and** an identity document (ID number, passport number
  or business registration number) and returns `accountHolderMatch`,
  `accountOpen`, `accountOpenForMoreThanThreeMonths`. That replaces "confirmed
  by the owner by eye" with a bank-backed check, and it means eRestyu never
  needs to retain the document number (§5).

What we give up: the hold until delivery. The vendor is paid on Paystack's
schedule whether or not they ship. Refunds for non-delivery therefore depend on
set-off against the vendor's future sales (§7), which must be in the vendor
terms the vendor accepts (§8) before the first split transaction.

## 2. What does NOT change

- `OrderTransitions`, `PaymentEventService.handleCheckoutCompleted`
  (idempotent PENDING -> PAID under `findByIdForUpdate`), `OrderExpiryJob`,
  price and delivery-fee snapshots, `totalAmount` as the single charged figure.
  The split is an extra field on the initialize request; the webhook flips the
  order exactly as today.
- 10% commission on the item subtotal; delivery fees pass through in full;
  eRestyu bears processing fees. In Paystack terms: `bearer_type: "account"`.
- The commission ledger is still written at PAID, in the same transaction as
  the status flip (`CommissionLedgerService`). Its role changes from
  **instruction to pay** to **record of what Paystack paid** (§6). The per-vendor
  `users.commission_rate` override and the snapshotted rate per entry stay.
- `SellingGate` stays the single enforcement point, at placeOrder, server-side.
  Its predicate grows (§4.4) and the flag turns ON as part of this rollout.
- `BankingMask` stays the only way banking leaves the server (§5.3).
- The Nedbank exporter and `PayoutAdminService` stay in the codebase, unused
  for split-settled entries. Delete nothing until the fallback question is
  closed.

## 3. Key Paystack facts the design rests on (verified)

| Fact | Where |
|---|---|
| Create Subaccount requires `business_name`, bank code, `account_number`, `percentage_charge`; optional `description`, `primary_contact_*`, `metadata`. Returns `subaccount_code`, `is_verified`, `settlement_schedule`. The doc is inconsistent on the bank field name (`bank_code` in the table, `settlement_bank` in the sample): try `bank_code` first in test mode and record which one the API accepted. | /docs/api/subaccount |
| A dynamic split is passed as a `split` object on Transaction Initialize: `type` (`flat` or `percentage`), `bearer_type` (`all`, `all-proportional`, `account`, `subaccount`), `subaccounts: [{subaccount, share}]`, optional `reference`. "One or more" subaccounts, no stated maximum. Flat shares are in the currency subunit; the total may not exceed the transaction amount. Percentage shares must be whole numbers. | /docs/payments/multi-split-payments |
| `charge.success` carries a `split` object with the formula and the computed share for Paystack, the integration and each subaccount. | same |
| Deleting or deactivating a subaccount with a pending payout does **not** redirect that payout to the main account; it is paid when the subaccount is re-enabled. | support2 12219071815964 |
| Payout account for a registered business must be a corporate account matching the registration documents. Starter Business accounts have an R80,000 lifetime collections cap, Sole Proprietorship R1,000,000. | support 360012149479 |
| `POST /bank/validate`: `account_name`, `account_number`, `account_type` (`personal` or `business`), `bank_code`, `country_code` ("ZA"), `document_type` (`identityNumber`, `passportNumber`, `businessRegistrationNumber`), `document_number` (marked optional). | /docs/api/verification |
| `GET /bank?country=south africa&enabled_for_verification=true` lists the SA banks supported for verification, with their codes. | /docs/api/miscellaneous |

**UNVERIFIED (not on any page read):** the ZAR settlement schedule for
subaccounts; where the unallocated remainder of a flat split lands (we assume
the main account; **prove it in test mode before anything says so**); whether
and how a refund on a split transaction is recovered from the subaccount; the
per-check fee for `/bank/validate`; whether `account_name` matching is exact or
fuzzy; whether `settlement_schedule` can be set on create or update.

## 4. Vendor onboarding and the gate

### 4.1 What the vendor submits (PayoutSetupCard, extended)

Replace the free-text `bank_name` with a **bank picker** fed by
`GET /bank?country=south africa&enabled_for_verification=true` (cached
server-side, refreshed daily). Store `bank_code`; keep `bank_name` as the
display label from the same list.

Add to the form, under the banking fields:

- Account type: Personal or Business (`account_type`, already exists as
  `BankAccountType`; map to Paystack's two values).
- Legal identity: document type (SA ID number / Passport number / CIPC
  registration number) and the number. The number is used once (§4.2) and is
  **never persisted**. The form says so in one sentence, with a link to the
  Privacy Policy section added in §9.
- Name as it appears on the bank account. `account_holder_name` already
  exists; label it exactly that way, because `/bank/validate` matches on it.

Vendor-facing copy may not say "verified by your bank" or "instant" until the
test-mode run shows what the response actually looks like.

### 4.2 Server: validate, record, never store the number

`PUT /vendor/settings/payouts/banking` (existing) grows into a two-step:

1. Save banking as today.
2. Call `POST /bank/validate` with the submitted fields and the document
   number. Write **one row** to `vendor_verifications` (§5.1) with the result
   flags, the document type, the **last four characters** of the document
   number, and the account-holder name as submitted. Return the masked status
   to the vendor: "Submitted for review" (never the raw flags; a vendor who
   fails `accountHolderMatch` is told the name on the account did not match
   and asked to correct it, nothing more).

The document number must be excluded from logs, Sentry breadcrumbs and
`toString()` by construction: pass it in a dedicated request record whose
`toString()` omits it, and mark the field `@JsonProperty(access = WRITE_ONLY)`.
Add a test that serialises the record and asserts the number is absent, the
way banking is kept out of `User.toString()` today.

If Paystack's validate endpoint is down (5xx / timeout), save the banking,
write the verification row with `provider_result = UNAVAILABLE`, and let the
admin retry from the review screen. A vendor must never be blocked from
submitting because a third party is down (fault-isolation rule in ROADMAP).

### 4.3 Admin review and approval

New admin screen "Vendor verification" (or a section on the existing admin
listings page from #87, since that is where the first listings are reviewed):

- Shows the verification row: document type, last four, the five Paystack
  flags, holder name, bank, masked account, `account_type`, and the vendor's
  first listings.
- Two actions: **Approve** and **Reject (with note)**. Both write
  `verified_by`, `verified_at`, `outcome` on the verification row. Approve also:
  - calls `POST /subaccount` with `business_name` (vendor `businessName`, or
    first + last name for an individual), `bank_code`, `account_number`,
    `percentage_charge = 10` (the default is overridden per transaction by the
    flat split, but it must still be a true number), `primary_contact_email`
    (vendor email), `metadata: {"vendorId": ...}`;
  - stores `subaccount_code` on `users`; sets `users.vendor_approved_at`.
- After approval the owner **verifies the subaccount in the Paystack
  dashboard**. Until then Paystack holds that vendor's first payout. Add this
  to OWNER-ACTIONS.md as a per-vendor step; the admin screen shows the
  `is_verified` value from `GET /subaccount/:code` so the owner can see it is
  outstanding.

Approve is refused (409) when `accountHolderMatch` or `accountOpen` is false
or the provider result is `UNAVAILABLE`; the admin retries validation or
rejects. There is no "approve anyway" button. If one is ever needed, it is a
deliberate change to this spec, not a flag.

### 4.4 The gate predicate (SellingGate.sellable)

```
enabled
&& vendor.hasAcceptedPayoutTerms(terms.version())      // v4, §8
&& vendor.hasCompleteBankingDetails()                    // now includes bank_code
&& vendor.getVendorApprovedAt() != null
&& vendor.getPaystackSubaccountCode() != null
```

Any change to `account_holder_name`, `account_number`, `bank_code` or
`account_type` **clears `vendor_approved_at`** in the same transaction and
re-runs §4.2. The vendor is unsellable until re-approved, and Paystack holds
the next payout to the updated subaccount until the owner re-verifies it
(§3). Listings from a vendor with `vendor_approved_at == null` are hidden from
the catalogue, search and similar-items (filter at the repository level, the
way soft-deleted products are).

### 4.5 Turning the gate on

Rollout, not a config flip (the existing yml comment is right):

1. Deploy with `PAYOUT_SELLING_GATE=false`, `PAYOUT_TERMS_VERSION=4`.
2. Every live vendor goes through §4.1 to §4.3. The vendor dashboard shows a
   banner naming what is outstanding (terms v4, banking, identity, approval).
3. When the vendors you want selling are approved, set
   `PAYOUT_SELLING_GATE=true`. Vendors not yet approved disappear from the
   catalogue at that moment; tell them the day before.

## 5. Data model

Migration **V38** (V37 is `order_payment_reference` on the Paystack branch).

### 5.1 `vendor_verifications`

| column | type | notes |
|---|---|---|
| id | bigserial | |
| vendor_id | bigint FK users | |
| document_type | varchar(32) | `ID_NUMBER`, `PASSPORT`, `CIPC` |
| document_last4 | varchar(4) | the only part of the number stored |
| account_holder_name | varchar(120) | as submitted at that moment |
| bank_code | varchar(16) | |
| account_last4 | varchar(4) | |
| account_type | varchar(20) | |
| provider | varchar(16) | `PAYSTACK` |
| provider_result | varchar(16) | `OK`, `MISMATCH`, `UNAVAILABLE`, `NOT_RUN` (slice 1 writes `NOT_RUN`: the validate call is not built yet) |
| account_holder_match | boolean null | from the response |
| account_open | boolean null | |
| account_open_3m | boolean null | |
| accepts_credits | boolean null | |
| verification_message | varchar(255) | Paystack's text |
| outcome | varchar(16) | `PENDING`, `APPROVED`, `REJECTED` |
| verified_by | bigint FK users null | admin |
| verified_at | timestamp null | |
| note | text null | |
| created_at | timestamp | |

One row per attempt; the latest row is the vendor's state. Never updated in
place except for the three outcome columns.

### 5.2 `users` additions

`bank_code varchar(16)`, `vendor_approved_at timestamp null`,
`paystack_subaccount_code varchar(40) null`. `hasCompleteBankingDetails()`
adds `bank_code`. `toString()` is **not** extended.

### 5.3 What is and is not protected, stated plainly

- Full ID / passport / CIPC numbers: **not stored anywhere**. They pass
  through one request to Paystack. (Owner decision 2026-10-17: verification
  record only. If Paystack's review requires retention, that is a new spec
  with an `AttributeConverter` and a logged reveal, not a column added here.)
- Bank account numbers: stored as today, plain columns, masked on every output
  by `BankingMask`. Under subaccounts, the full number leaves the server once
  more (to `POST /subaccount`) and the Nedbank CSV path stops being used, so
  the copy on the owner's laptop disappears. Encrypting `account_number` at
  rest is **parked** behind the trigger "any second person gets database or
  admin access".
- Admin account: single account, password only. **MFA before the first real
  verification row.** Cheapest route is Cloudflare Access in front of
  `/admin/*` on the frontend plus the `/api/v1/admin/**` rule that already
  exists; Spring TOTP is the in-code alternative. Owner action.
- Sentry: confirm `sentry.max-request-body-size` is `none` (default) so a
  `log.error` during the banking PUT cannot ship the body. Add the check to
  the test that already guards `User.toString()`.

## 6. Checkout and settlement

### 6.1 Building the split (PaystackCheckoutService.createCheckout)

For each vendor in the order (one `OrderDeliveryFee` row per vendor already
exists):

```
share_cents = toCents( itemsSubtotal × (1 − resolvedRate) + deliveryFee )
```

`resolvedRate` is the same per-vendor resolution the ledger uses, so the
ledger entry and the split share are equal by construction; add a test that
asserts it for a two-vendor cart with one override. Round half-up per vendor;
the commission is whatever remains, which is what already happens in the
ledger.

If the vendor has outstanding clawback (§7), `share_cents` is reduced by the
outstanding amount, floored at 0, and the ledger records the recovery on that
order.

Request body addition:

```json
"split": {
  "type": "flat",
  "bearer_type": "account",
  "subaccounts": [
    { "subaccount": "ACCT_...", "share": 85000 },
    { "subaccount": "ACCT_...", "share": 12000 }
  ],
  "reference": "ORDER-<id>-SPLIT"
}
```

The sum of shares must be ≤ `amount`. Assert it before sending; a violation is
a bug in our arithmetic, so fail the checkout with a 500 and a Sentry event
rather than silently trimming.

**Remainder:** the docs do not state where an unallocated remainder goes. The
first test-mode transaction with a two-vendor flat split settles this. Record
the answer at the top of this file. If the remainder does **not** stay with the
main account, add eRestyu's own subaccount to every split and route the
commission there explicitly.

### 6.2 Webhook

`charge.success` already flips PENDING -> PAID and writes the ledger. Add:
read `data.split` and, per subaccount, set the matching `VendorPayoutEntry`
to status `PAID` with `payment_reference = <paystack reference>` and
`settlement_mode = SPLIT` (new column on `vendor_payout_entries`, values
`EFT` for the historic path and `SPLIT`), `paid_at = now()`, note "Settled by
Paystack split". If `data.split` is absent on a split transaction, log
`SPLIT MISSING ON CHARGE.SUCCESS` and leave the entry `PENDING`; that string is
the one to alert on.

"PAID" here means Paystack owes the vendor and will settle on its schedule,
not that the money has reached the vendor's bank. The admin payouts page
labels SPLIT entries "Settled by Paystack" and shows no approve or export
action for them. Reconciliation against `GET /settlement` is **parked** until
there is more than one vendor with more than one settlement a month.

### 6.3 What the vendor sees

Vendor dashboard payout card: per order, "Paid to your account by Paystack as
part of this sale" with the Paystack reference. No timing promise until the
ZAR subaccount settlement schedule is confirmed in writing by Paystack; then
the line becomes "settles within N business days" with N from their answer,
not from a blog post.

## 7. Refunds, cancellations and disputes under splits

Because every vendor entry is PAID at charge, **every** refund is a clawback,
never a void. `CommissionLedgerService.reverseOnRefund` already writes a
`CLAWBACK` adjustment when the entry is PAID; nothing changes in the ledger.

Changes:

1. **Transition map.** `REFUNDED` is reachable only from `DELIVERED` today. Add
   `PAID -> REFUNDED` and `SHIPPED -> REFUNDED` so a paid order the vendor never
   ships has an honest path. Stock is **not** restored on refund (the goods
   may be in transit or with the buyer); restocking stays a vendor action.
2. **Provider refund.** Still manual: the admin issues it in the Paystack
   dashboard (or `POST /refund`, which the branch does not implement) from
   eRestyu's balance, then marks the order REFUNDED. Whether Paystack also
   recovers the vendor's share from the subaccount is **UNVERIFIED** (§10 Q1).
   Until answered, assume eRestyu funds the whole refund and recovers the
   vendor's share by set-off.
3. **Set-off.** Outstanding clawbacks reduce the vendor's share in their next
   split transactions (§6.1) until recovered. The vendor dashboard shows the
   outstanding balance and which order it came from. The vendor terms (§8)
   grant this right explicitly.
4. **Dispute handling, as practised today:** the buyer writes to
   hello@erestyu.com or replies to the order email; eRestyu takes it up with
   the vendor; eRestyu decides and issues the refund. Write that down as the
   process in the Returns page (it is already there in substance) and in the
   reply to Paystack. Do not describe an SLA that is not enforced anywhere.

## 8. Vendor terms, version 4

`PayoutTerms.text()` currently builds one sentence. Keep the builder for the
numbers, but the sentence a vendor accepts becomes a **page** (`/seller-terms`,
rendered through `LegalPage`) whose commission and delivery lines are
interpolated from `PayoutTerms`, and the acceptance checkbox references the
page by version. Bump `PAYOUT_TERMS_VERSION` to 4; every vendor re-accepts.

Draft text. Owner to review before it is published; nothing in it may promise
what §3 lists as UNVERIFIED.

> **Seller terms** (version 4)
>
> **Who you are dealing with.** eRestyu is operated by ERESTYU (Pty) Ltd. You
> sell your own goods to buyers through eRestyu. eRestyu provides the
> marketplace and the payment process; it does not buy, hold or ship your
> goods.
>
> **Fees.** eRestyu charges a commission of {commissionPercent}% of the item
> total of each sale. Delivery fees you set are passed to you in full.
> eRestyu pays the payment processing fees out of its commission. There are no
> listing or monthly fees.
>
> **How you are paid.** Your share of each sale (item total less commission,
> plus your delivery fee) is paid to the bank account you nominated by our
> payment provider, Paystack, as part of that transaction's settlement. The
> settlement timing is set by Paystack. eRestyu does not hold your share.
>
> **Verification.** Before you can sell, we verify the bank account you
> nominate against the name of its holder and an identity document (South
> African ID number, passport number or CIPC registration number) through our
> payment provider, and we review your first listings. We keep a record of
> the outcome and the last four characters of the document number; we do not
> keep the number itself. Changing your bank details suspends selling until
> the new details are verified.
>
> **Your responsibilities.** You are responsible for each item you list: that
> it is as described, that it is yours to sell, that it is lawful to sell in
> South Africa, and that it reaches the buyer. You may not list counterfeit or
> replica goods, brands you are not authorised to sell, or stolen goods.
> eRestyu may remove any listing, and close any store, that breaks these
> rules, and may remove a listing it has reason to doubt while it checks.
>
> **Refunds and set-off.** Buyers have the rights set out in our Returns and
> Refunds policy, including the seven-day cooling-off right under the ECT Act
> and the six-month defect right under the Consumer Protection Act. When
> eRestyu refunds a buyer for a sale of yours, you owe eRestyu the amount of
> your share of that sale. eRestyu may recover it by reducing your share of
> your later sales until it is recovered, and will show you the outstanding
> amount and the order it relates to.
>
> **Photos and store links.** By uploading a product photo you confirm you
> hold the rights to it. Photos are public. You may link only social profiles
> that belong to your store.
>
> **Ending.** You may stop selling at any time by removing your listings;
> orders already paid must still be fulfilled. eRestyu may close a store for
> breach of these terms. These terms change by version; selling continues
> only after you accept the current version.
>
> **Law and contact.** South African law applies. Write to
> hello@erestyu.com.

Missing on purpose, pending the owner: a liability cap, and anything about
VAT (ERESTYU is not VAT-registered; say nothing until that changes).

## 9. Copy that must change together (one PR)

| Where | Today | Becomes |
|---|---|---|
| `data/company.ts` `MARKETPLACE_DISCLOSURE` | "eRestyu does not vet sellers, authenticate brands or hold stock." | "eRestyu verifies each seller's bank account and identity document through its payment provider and reviews their first listings. It does not authenticate brands or hold stock." |
| `/sell` | "You are not vetted, and we do not pretend to vet you." + payout by EFT within 7 days | Verification paragraph matching §8; payment "by Paystack as part of each sale's settlement"; no timing number |
| `/terms` "Who you are buying from" | "takes payment on the seller's behalf" | "Buyers pay through Paystack, which pays each seller their share directly; eRestyu receives its commission." (only once the remainder behaviour is proven) |
| `/privacy` | no identity section | New section "Seller verification": what is collected (document type and number), purpose (verification of the payout account, payment-provider compliance), that the number is sent to Paystack and not retained by eRestyu, what is retained (type, last four, result), retention (five years after the seller's last sale), the lawful basis (performance of the seller agreement), and the seller's POPIA rights |
| Paystack business profile description | per company.ts | Updated to the new disclosure, word for word, on the same day |
| `PayoutTerms.text()` | EFT within 7 days of the weekly run following delivery confirmation | §8 "How you are paid" |
| `/help` FAQ "Fees" and HowItWorks "Selling" | 7-day EFT | same as above |

## 10. Open questions for Paystack (ask in the reply to the review)

1. When a refund is issued on a transaction that was split to subaccounts, is
   the subaccount's share recovered by Paystack, or does the main account fund
   the whole refund?
2. What is the settlement schedule for ZAR subaccounts, and does the first
   payout hold (until dashboard verification) apply in South Africa?
3. In a flat split whose shares do not add up to the transaction amount, does
   the remainder settle to the main account?
4. Is there a per-check fee for `POST /bank/validate` in South Africa?
5. Does Paystack require eRestyu to retain vendor identity documents or
   numbers, or is a verification record (type, last four, result) sufficient?

## 11. Owner actions

- Confirm the eRestyu payout account is a corporate account in the name of
  ERESTYU (Pty) Ltd (Paystack: registered business requirement).
- Put MFA in front of admin before the first verification row (§5.3).
- Check Sentry request-body setting (§5.3).
- Verify each new subaccount in the Paystack dashboard after approving the
  vendor (§4.3). Add to OWNER-ACTIONS.md.
- Run the two-vendor flat-split test transaction and record the remainder
  behaviour at the top of this file (§6.1).
- Review and publish §8 and §9 copy together, then bump
  `PAYOUT_TERMS_VERSION=4`, then take live vendors through §4.5.

## 12. Test plan (test mode)

- Create two test subaccounts via API; confirm the field name the API accepts
  for the bank; confirm `is_verified` starts false.
- `POST /bank/validate` with a known-good and a deliberately mismatched name;
  record the exact response shapes for both, including the 400 body the docs
  omit.
- Two-vendor cart, one with a commission override: initialize with the flat
  split; assert ledger entries equal split shares; complete payment; assert
  `charge.success` carries `split`; assert entries flip to PAID / SPLIT.
- Where the remainder went (§6.1).
- Refund the transaction in the dashboard; observe what happens to the
  subaccount shares (Q1); mark the order REFUNDED; assert the clawback row and
  that the next checkout for that vendor reduces their share.
- Change a verified vendor's account number; assert `vendor_approved_at`
  clears and the gate blocks checkout with the 409-with-items shape.
- Serialise the identity request record; assert the document number is absent
  from `toString()` and from any log line emitted during the request.

## 13. Parked, with triggers

| Item | Trigger |
|---|---|
| Encrypt `account_number` at rest | A second person gets database or admin access |
| Settlement reconciliation against `GET /settlement` | More than one vendor with more than one settlement per month |
| Automated provider refund (`POST /refund`) | More than two refunds in a month |
| OTP phone verification | Owner can no longer phone each new vendor |
| Deleting the Nedbank exporter and batch run | Paystack confirms in writing that collect-then-pay is not required as a fallback |

# Ticket: Vendor verification, slice 1 (no Paystack dependency)

**Status:** Open, approved by the owner 2026-10-17
**Spec:** `paystack-subaccounts.md` (repo root). This ticket implements the parts
of §4, §5 and §7 of that spec that need nothing from Paystack. Read the spec
first; where this ticket and the spec disagree, stop and ask.
**Branch:** `feat/vendor-verification-1`, off `main` after
`feat/paystack-provider` has been merged (its migration is V37). If the
Paystack branch is not yet on `main` when you start, stop and say so; do not
pick a migration number around it.

## Rules that apply to every change here

- Every statement a user can read must describe what the code does at that
  moment. Nothing in this slice makes identity verification live, so no copy
  anywhere may say that sellers are verified. The existing "eRestyu does not
  vet sellers" sentences stay as they are in this slice.
- `app.payouts.selling-gate-enabled` stays `false` and `terms-version` stays
  `3`. This slice builds enforcement and leaves it switched off. Do not flip
  either flag, in any environment.
- Banking fields and the identity document number must never appear in a log
  line, an exception message, a `toString()`, a Sentry breadcrumb or an API
  response. `User.toString()` is not extended. `BankingMask.of()` stays the
  only way banking leaves the server.
- Preserve CRLF line endings in files that have them. Do not reformat files you
  did not change.
- Each slice below is one commit with its own tests passing. Backend tests are
  JUnit with Testcontainers (see `payout/PayoutTermsGateTest.java` for the
  gate's existing test style); frontend tests are Vitest.
- Do not touch `PayoutAdminService`, `NedbankBulkPaymentsExporter`,
  `CommissionLedgerService` or any Paystack class. They are out of scope.

## Slice A: migration V38

New Flyway migration `V38__vendor_verification.sql`:

1. `vendor_verifications` table, columns exactly as spec §5.1. `document_last4`
   is `varchar(4)`; there is no column for the full number and none may be
   added. Check constraints on `document_type`
   (`ID_NUMBER`, `PASSPORT`, `CIPC`), `provider_result`
   (`OK`, `MISMATCH`, `UNAVAILABLE`, `NOT_RUN`) and `outcome`
   (`PENDING`, `APPROVED`, `REJECTED`), mirroring how
   `vendor_payout_entries_status_check` is done in V27. Index on
   `(vendor_id, created_at desc)`.
2. `users`: add `bank_code varchar(16) null`, `vendor_approved_at timestamp
   null`, `paystack_subaccount_code varchar(40) null`. All nullable; no
   backfill.
3. `vendor_payout_entries`: add `settlement_mode varchar(8) not null default
   'EFT'` with a check constraint (`EFT`, `SPLIT`).

Add `NOT_RUN` to the spec's `provider_result` values: it is the state this
slice writes, because the Paystack validate call is not built yet. Note that
addition in the spec's §5.1 table in the same commit.

Entities: `VendorVerification` (new), `User` gains the three fields with
getters and setters only, `VendorPayoutEntry` gains `settlementMode` with an
enum `SettlementMode { EFT, SPLIT }`. `User.hasCompleteBankingDetails()` now
also requires `bankCode` non-blank. Update `PayoutTermsGateTest` and any other
test that builds a "complete" vendor so they set `bankCode`.

Test: a repository test that saves a `VendorVerification` and reads it back,
and asserts `VendorVerification.toString()` contains none of
`accountHolderName`, `accountLast4`, `documentLast4`.

## Slice B: gate predicate and re-verification on banking change

`SellingGate.sellable(User)` becomes:

```
enabled
  && vendor.hasAcceptedPayoutTerms(terms.version())
  && vendor.hasCompleteBankingDetails()
  && vendor.getVendorApprovedAt() != null
```

(`paystackSubaccountCode` is NOT part of the predicate in this slice; it joins
in the Paystack slice.)

In `VendorPayoutSettingsService` (the service behind
`PUT /api/v1/vendor/settings/payouts/banking`): when any of
`accountHolderName`, `accountNumber`, `bankCode`, `branchCode`, `accountType`
changes value, set `vendorApprovedAt = null` in the same transaction and write
a `vendor_verifications` row with `provider_result = NOT_RUN`,
`outcome = PENDING`, the submitted holder name, bank code, account type, and
`account_last4`. Unchanged banking (same values resubmitted) writes nothing.

Return shape: `PayoutSettingsStatus` gains `approved: boolean` and
`verificationState: "NONE" | "PENDING" | "APPROVED" | "REJECTED"` (from the
latest verification row). Nothing else about the response changes;
`banking` stays masked.

Tests, extending `PayoutTermsGateTest`:
- vendor with terms + complete banking but `vendorApprovedAt == null` is
  blocked when the gate is on, with the existing 409-with-items shape;
- approved vendor sells;
- changing `accountNumber` clears `vendorApprovedAt` and creates a PENDING
  verification row; resubmitting identical banking creates no row;
- gate off: all of the above are sellable (unchanged behaviour).

## Slice C: order transitions for refunds

In `OrderTransitions.ALLOWED`, add `REFUNDED` to the sets for `PAID` and
`SHIPPED`. Update the class comment and the ASCII diagram to match. In
`OrderAdminService.transition`, the existing `ledger.reverseOnRefund(order)`
call already fires on any transition to `REFUNDED`; confirm it does for the two
new sources and add a test for each (`PAID -> REFUNDED` voids a PENDING entry;
`SHIPPED -> REFUNDED` likewise). Stock is NOT restored on refund; add an
assertion that `stockQuantity` is unchanged after `PAID -> REFUNDED`, with a
comment pointing at spec §7 item 1.

Frontend: `AdminPage.tsx` holds a transition map (`DELIVERED: ['REFUNDED']`).
Add `REFUNDED` to the `PAID` and `SHIPPED` entries so the admin UI offers it.
`StatusChip` already knows `REFUNDED`. Update `AdminOrderDetailPage` tests if
they enumerate allowed targets.

## Slice D: admin vendor verification screen

Backend, new `AdminVendorController` under `/api/v1/admin/vendors`, class-level
`@PreAuthorize("hasRole('ADMIN')")` in the same style as
`AdminPayoutController`:

- `GET /pending`: vendors whose latest verification row is `PENDING`, with
  the row's fields (holder name, bank code, bank name, `account_last4`,
  account type, `document_type`, `document_last4`, `provider_result`,
  `verification_message`), the vendor's business name and email, and their
  five most recent live products (id, name, price, first image URL).
- `POST /{vendorId}/approve` and `POST /{vendorId}/reject` with body
  `{ "note": string }` (note required on reject, optional on approve). Both
  set `verified_by`, `verified_at`, `outcome` on the latest row. Approve also
  sets `users.vendor_approved_at = now()`. Approve returns 409 when the latest
  row's `provider_result` is `MISMATCH` or `UNAVAILABLE`; `NOT_RUN` and `OK`
  may be approved. Reject does not touch `vendor_approved_at` (it is already
  null for a pending vendor). There is no "approve anyway" path.
- Both actions are idempotent on an already-decided row: return the current
  state with 200, change nothing.

Frontend: `AdminVendorsPage` at `/admin/vendors`, linked from the admin nav
next to Listings. One row per pending vendor, masked banking, the listing
thumbnails, Approve and Reject (Reject opens a note field). Follow
`AdminListingsPage.tsx` for layout and `AdminListingsPage.test.tsx` for the
test shape. No full account number is rendered anywhere on this page.

Tests: controller tests for the 409 on `MISMATCH`, the idempotent re-approve,
and that the pending list response contains no `accountNumber` field.

## Slice E: identity request container (no call behind it yet)

Add `IdentityDocumentRequest(DocumentType type, String number)` as a Java
record in `payout/` with:
- `toString()` returning `IdentityDocumentRequest[type=..., last4=...]`;
- `@JsonProperty(access = WRITE_ONLY)` on `number`;
- a static `last4()` helper used by slice B when a document is supplied.

Extend `PUT /payouts/banking` to accept an optional `identity` object of that
shape. When present, slice B's verification row records `document_type` and
`document_last4`; the number itself is dropped on the floor after `last4()`.
No Paystack call is made in this slice. Vendor-facing copy for the new field:
"Used once to verify your bank account. We keep only the last four
characters." Do not add the field to the form until that sentence is true,
which is this slice: the server keeps only the last four. Add the field to
`PayoutSetupCard` with that exact sentence.

Tests: serialise the record and the full request with Jackson and assert the
number is absent from output; assert the saved row's `document_last4` is the
last four characters; assert `number` does not appear in any log captured
during the request (use the logging capture pattern already used for the
"MANUAL REFUND REQUIRED" tests if one exists; otherwise add a `ListAppender`
on the `com.marketplace.api` logger).

## Slice F: seller terms page, built but not switched on

Frontend: `pages/legal/SellerTermsPage.tsx` at `/seller-terms`, rendered
through `LegalPage`, containing spec §8 text. The two numbers (commission
percent, delivery pass-through) come from the public fees endpoint that
`PublicFeesController` already exposes, not from hardcoded strings. The
"How you are paid" section is rendered from a constant that is **not** the
§8 text yet: in this slice it says exactly what `PayoutTerms.text()` says
today (EFT within 7 days of the weekly run following delivery confirmation),
because that is what the live system does. The §8 version of that section is
committed alongside it as a second constant with a comment "switch when
settlement moves to Paystack split; bump PAYOUT_TERMS_VERSION in the same
deploy".

Add a footer link "Seller terms" next to "Terms of Service" in `Footer.tsx`,
and link to the page from `PayoutSetupCard` beside the acceptance checkbox.
The acceptance call does not change in this slice.

Test: the page renders both numbers from the fees response and does not
contain the string "Paystack".

## Not in this slice (do not build)

- Hiding unapproved vendors' listings from the catalogue. There are 17
  `deleted_at IS NULL` filter sites; that change needs its own slice, keyed on
  the gate flag so a flag-off deploy does not blank the catalogue.
- Anything that calls Paystack: bank list, validate, subaccounts, split,
  webhook `split` handling.
- The §9 copy changes (company.ts, /sell, /terms, /privacy, FAQ).
- Bumping `PAYOUT_TERMS_VERSION`.
- Encrypting `account_number`.

## Done when

- `mvn test` and `npm test` pass; `npm run build` passes.
- With `PAYOUT_SELLING_GATE=false`, a manual walkthrough shows no change in
  buyer or vendor behaviour except the new identity field, the new
  `/seller-terms` page, and the admin `/admin/vendors` page.
- With `PAYOUT_SELLING_GATE=true` on a local database, an unapproved vendor's
  product cannot be checked out (409 with the item listed), and approving the
  vendor in `/admin/vendors` makes it checkout-able without a restart.
- `grep -rn "getAccountNumber\|documentNumber\|identity.number"` over
  `src/main` shows reads only in `BankingMask`, the banking save path, and
  `IdentityDocumentRequest.last4()`.
- A short PR description listing which spec sections each commit covers, and
  anything you found that contradicts the spec.

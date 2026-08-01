# PayFast port spec

Status: **spec only, ready to build.** Blocked on nothing technically (full
sandbox exists); the live cutover waits on PayFast merchant onboarding with
the CIPC docs and Nedbank account. Stripe test mode keeps powering the demo
until this lands.

Decision context: Paystack raised a trading-history gate; PayFast has no such
gate, zero monthly fees on the aggregation plan, and carries card + Instant
EFT + SnapScan + Capitec Pay + Apple/Google Pay + Payflex, which matches both
our buyer base and the footer's "We Accept" row. Yoco is the card-only
fallback if onboarding stalls. Source: developers.payfast.co.za (fetched
2026-08-01; re-verify signatures section before implementing).

## What does NOT change

The port's whole premise is that the payment provider is an edge concern:

- `OrderTransitions` state machine and `PaymentEventService.handleCheckoutCompleted`
  (idempotent PENDING -> PAID under `findByIdForUpdate`): the ITN handler calls
  exactly this, the way the Stripe webhook does today.
- `OrderExpiryJob`, success-page polling, order emails, delivery-fee and price
  snapshots, `totalAmount` as the single charged figure.
- `StripeCheckoutService.attachShipping` semantics: address written in the
  same transaction that creates the checkout, before any redirect exists.
  This moves to the new service unchanged.

## Key dialect differences from Stripe

1. **No line items.** PayFast takes ONE `amount` and one `item_name`. The
   charged amount is `order.totalAmount` (items + delivery fees, already
   snapshotted); `item_name` is the order number ("eRestyu order ORD-...").
   Itemization stays on our order pages and emails, where it already lives.
2. **`m_payment_id` carries the order id natively.** No metadata JSON, which
   removes the class of parsing bug the Stripe webhook hardening addressed.
3. **Redirect is a form POST, not a URL.** The backend returns the process
   URL plus signed fields; the frontend renders a hidden auto-submitting
   form. (GET with query params also works but exposes fields in history;
   use POST.)
4. **ITN is form-encoded, MD5+passphrase signed,** not JSON with HMAC.
5. **Amounts are client-visible** in the redirect form, which is why the
   amount check in the ITN handler is load-bearing, not belt-and-braces.

## Config

```yaml
app:
  payments:
    provider: payfast            # stripe | payfast — cutover switch, default stripe until live-verified
  payfast:
    merchant-id: ${PAYFAST_MERCHANT_ID}
    merchant-key: ${PAYFAST_MERCHANT_KEY}
    passphrase: ${PAYFAST_PASSPHRASE}     # MUST be set on the PayFast dashboard too
    process-url: https://sandbox.payfast.co.za/eng/process     # prod: www.payfast.co.za
    validate-url: https://sandbox.payfast.co.za/eng/query/validate
    return-url: ${APP_FRONTEND_URL}/checkout/success
    cancel-url: ${APP_FRONTEND_URL}/checkout/cancelled
    notify-url: https://api.erestyu.com/api/v1/payments/payfast/itn
```

Sandbox: create our own sandbox account (docs recommend it) rather than the
shared demo pair (10000100 / 46f0cd694581a) so the passphrase path is tested.
Test config stubs the values the same way `app.stripe.secret-key` is stubbed.

## Endpoint shapes

### `POST /api/v1/payments/{orderId}/pay` (existing route, response shape changes)

Request: unchanged (`ShippingAddressRequest`).
Behavior: `attachShipping` (ownership + PENDING check + address write, one
transaction), then build the signed field map.
Response:

```json
{
  "processUrl": "https://sandbox.payfast.co.za/eng/process",
  "fields": {
    "merchant_id": "...", "merchant_key": "...",
    "return_url": "...", "cancel_url": "...", "notify_url": "...",
    "name_first": "<buyer first name>", "email_address": "<buyer email>",
    "m_payment_id": "<order id>",
    "amount": "590.00",
    "item_name": "eRestyu order ORD-XXXX",
    "signature": "<md5>"
  }
}
```

Frontend: replace `window.location = sessionUrl` with rendering the fields as
a hidden form and submitting it. Everything else on the pay page is untouched.

### `POST /api/v1/payments/payfast/itn` (new, permitAll, form-encoded)

Thin controller (like the Stripe webhook shell): reads the raw form params
IN ORDER (LinkedHashMap via `request.getParameterMap()` is NOT order-safe;
parse the raw body string ourselves, order matters for the signature), runs
the security gauntlet below, then calls
`paymentEventService.handleCheckoutCompleted(orderId)` for
`payment_status=COMPLETE`. Always returns 200 unless the source is invalid
(non-200 makes PayFast retry: immediately, after 10 minutes, then
exponentially).

## Signature rules (both directions)

- Pairs joined with `&`, values URL-encoded with UPPERCASE percent-encoding
  and spaces as `+` (Java's `URLEncoder.encode(v, UTF_8)` matches both).
- Blank values are EXCLUDED when generating the request signature.
- **Request signature: fields in DOCUMENTED attribute order** (merchant block,
  customer block, transaction block...), NOT alphabetical (that ordering is
  the API-signature format, a known footgun).
- **ITN verification: fields in the ORDER RECEIVED**, all fields up to but
  excluding `signature`.
- Append `&passphrase=<urlencoded passphrase>`, MD5, lowercase hex.

## ITN validation checklist, as the tests to write

`PayfastItnTest` (Testcontainers, mirrors `PaymentEventServiceTest`):

1. **Valid COMPLETE ITN transitions PENDING -> PAID** with history row and
   (after commit) the order emails, exactly like the Stripe path.
2. **Bad signature -> rejected, order untouched.** Flip one byte.
3. **Amount mismatch -> rejected + loud log** (tolerance 0.01). This is the
   "MANUAL REVIEW" analogue of the Stripe non-payable log line; it is the
   alert string. Order stays PENDING.
4. **Server-confirmation failure -> rejected.** The handler POSTs the exact
   received payload to `/eng/query/validate` and requires the body `VALID`.
   In tests, stub the validator client interface (small `PayfastValidator`
   seam so tests do not need PayFast reachable).
5. **Invalid source -> rejected.** Referer/resolved-IP not in
   {www,w1w,w2w,sandbox}.payfast.co.za. (Weakest check behind proxies; treat
   as defense-in-depth. Railway sits behind a proxy, so read the client IP
   from `X-Forwarded-For` leftmost hop and document that decision.)
6. **Duplicate COMPLETE ITN is idempotent** (already-PAID -> log + 200, no
   second history row, no second email). Free from the state machine, but pin
   it here for the PayFast path.
7. **payment_status=CANCELLED -> no transition**, 200, logged.
8. **Signature util round-trip:** generate a request signature, re-verify it
   with the documented example vector; encode a value with spaces and
   reserved chars and assert uppercase hex + `+`.

## Stripe decommission notes

- Keep `StripeCheckoutService` and the webhook route behind
  `app.payments.provider` until the FIRST real PayFast rand clears end to end
  in production (sandbox proof is necessary, not sufficient).
- Then: delete `StripeCheckoutService`, `PaymentController`'s Stripe webhook
  branch, `stripe-java` from `pom.xml`, `app.stripe.*` config and Railway env
  vars, and the Stripe stub values in test application.yml. Port the
  signature-verification test IDEAS to PayfastItnTest before deleting the
  Stripe tests; the webhook-hardening lessons (raw body, API-version drift)
  are encoded there.
- Frontend copy: utility bar says "Secure Stripe checkout"; Terms and the
  footer trust row name Stripe. Sweep for "Stripe" at cutover.

## Ops sequence

1. NOW: register at payfast.co.za with CIPC docs + Nedbank account (only step
   with a wait). Set a passphrase in the dashboard immediately.
2. Create a sandbox account; wire sandbox creds into local/dev config.
3. Build the slice per this spec; verify in sandbox (ngrok for local ITN).
4. Merchant approved: set Railway env vars, switch provider flag in prod,
   R5 live test order, verify ITN + PAID + email + Nedbank settlement.
5. Decommission Stripe (above), update legal/footer copy.

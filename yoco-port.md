# Yoco Checkout port — third provider behind `PAYMENTS_PROVIDER`

Companion to `payfast-port.md`. Same architecture rule: the order state
machine never learns which provider is live. All provider knowledge stays
behind the `app.payments.provider` flag and the `CheckoutPreparation` seam.

## 0. Verified from primary sources

Read 2026-08-03 from Yoco's own developer docs and the Checkout API OpenAPI
description — **not** from integration blogs. `developer.yoco.com` 303-redirects
to `yoco.docs.buildwithfern.com`; the `.md` variants of those paths are the
machine-readable source.

| Fact | Value | Source |
|---|---|---|
| Create checkout | `POST https://payments.yoco.com/api/checkouts` | [create-checkout](https://developer.yoco.com/checkout-api-reference/checkout/create-checkout) |
| Auth | `Authorization: Bearer <secret key>` | same |
| Idempotency | `Idempotency-Key` header, optional | same |
| Required body | `amount` (integer **cents**), `currency` (ZAR only) | same |
| Optional body | `successUrl`, `cancelUrl`, `failureUrl`, `metadata`, `clientReferenceId`, `externalId`, `lineItems`/`totalDiscount`/`totalTaxAmount`/`subtotalAmount` (display only) | same |
| Response | `id`, `redirectUrl`, `status` (`created`/`started`/`processing`/`completed`), `paymentId`, `processingMode` (`live`/`test`) | same |
| Success event | **`payment.succeeded`** (also `payment.failed`, `refund.succeeded`, `refund.failed`) | [Checkout API OpenAPI](https://yoco.docs.buildwithfern.com/openapi/checkout-api-reference.json) |
| Event envelope | `{id, type, createdDate, payload}` | same |
| Success payload | `{id, type, status, amount, currency, mode, metadata, paymentMethodDetails}` | same |
| Webhook registration | `POST https://payments.yoco.com/api/webhooks` `{name, url}` → `{id, name, url, mode, secret}`; `secret` returned **once** | same |
| Signature headers | `webhook-id`, `webhook-timestamp`, `webhook-signature` | [verifying-events](https://developer.yoco.com/online/api-reference/webhooks/verifying-events/) |
| Signed content | `"{webhook-id}.{webhook-timestamp}.{raw-body}"` (full stops) | same |
| Key derivation | strip `whsec_`, **base64-decode the remainder** | same |
| Signature | base64(HMAC-SHA256(key, signed content)) | same |
| Header format | space-separated entries, each `v1,<base64>`; several during rotation | same |
| Timestamp tolerance | Yoco recommends **up to 3 minutes** | same |
| Test mode | keys from Yoco app → Checkout API integration → **Test keys** tab; test transactions do not appear on the portal dashboard or sales report | [testing](https://developer.yoco.com/online/resources/testing-info/) |
| Test minimum | payments under **R2.00 (200 cents) are rejected** | same |

### The finding that shapes the design

**`payment.succeeded` carries no checkout reference.** Its payload has a
payment `id` and nothing else pointing back — not the checkout id, not
`clientReferenceId`, not `externalId`. `payload.metadata` is therefore the
**only** route from a delivered webhook to an order. This is why
`YocoCheckoutService` sends `metadata.orderId` and why the webhook logs a hard
error when it is absent: money has moved and there is nothing to reconcile it
against.

`clientReferenceId` and `externalId` are still sent because they surface in
Yoco's dashboard for manual reconciliation, but no code depends on them
returning.

### Two doc regimes — do not mix them

Yoco's general webhooks pages document a **different** product (the Business
API, `api.yoco.com/v1/webhooks/subscriptions/`) with a flat payload
`{business_id, event_type, order_id, payment_id}` and the event
`payment.created`. That is **not** the Checkout API. The Checkout API uses the
nested `{id, type, createdDate, payload}` envelope and `payment.succeeded`,
registered at `payments.yoco.com/api/webhooks`. Same lesson as PayFast's two
signature regimes: the provider ships more than one scheme and the docs do not
always say which page belongs to which.

## 1. What changed

| File | Change |
|---|---|
| `payment/YocoCheckoutService.java` | new — creates the checkout, returns `redirectUrl` |
| `payment/YocoWebhookController.java` | new — `POST /api/v1/payments/yoco/webhook` |
| `payment/YocoSignature.java` | new — svix scheme, every edge pinned by tests |
| `payment/YocoConfigValidator.java` | new — fail-fast **only** when `provider=yoco` |
| `payment/Money.java` | new — `toCents` extracted from `StripeCheckoutService` |
| `payment/PaymentController.java` | third branch; `{checkoutUrl}` like Stripe |
| `payment/PaymentEventService.java` | `handleCheckoutCompleted(orderId, provider)` — the audit note was hardcoded `(Stripe)` for every provider |
| `payment/PayfastItnService.java` | passes `"PayFast"` — fixes the existing misattribution |
| `security/SecurityConfig.java` | `permitAll` for the webhook path |
| `application.yml` (main + test) | `app.yoco.*` block |

**No frontend change.** `PayResponse` is a structural union and Yoco returns
`{checkoutUrl}`, which the existing redirect branch in `CartPage.tsx` already
handles. Verified, not assumed.

## 2. Deliberate deviations from the brief

1. **No provider interface.** The brief assumed Stripe and PayFast implement a
   common interface. They do not — selection is a string compare in
   `PaymentController` with both services injected. Yoco follows the existing
   pattern (a third branch) rather than triggering a refactor of the Stripe
   path that is currently taking live money.
2. **Fail-fast, not contained failure.** The brief asks for both (§1 says
   fail-fast when `provider=yoco`, §4 says contained failure). Fail-fast wins:
   a *selected* payment provider with no keys should not boot healthy and 502
   every checkout. Contained failure is right for peripheral providers
   (Anthropic, R2), not for the one taking the money.
3. **Tolerance 3 minutes, not 5.** Yoco documents 3; the stricter documented
   value is used and is configurable via `YOCO_WEBHOOK_TOLERANCE_SECONDS`.
4. **Idempotency key is per-attempt, not the order id.** The brief suggested
   the order id. An order-scoped key replays the *first* checkout, which may
   have expired, leaving a customer unable to pay. Two live checkouts for one
   order is harmless (only one completes; the transition is idempotent); a
   customer who cannot pay is not. The key is reused only across the single
   connect-error retry.

## 3. Go-live checklist

Do these in order. Steps 1-3 are reversible; step 5 is not.

1. **Verify refund mechanics and fees first**, before taking any real money:
   `POST /api/checkouts/{id}/refund`. Confirm in the Yoco dashboard whether the
   processing fee is returned on a refund — this determines the true cost of
   step 5.
2. Register the live webhook:
   `POST https://payments.yoco.com/api/webhooks` with
   `{"name":"erestyu-prod","url":"https://api.erestyu.com/api/v1/payments/yoco/webhook"}`
   using the **live** secret key. Capture `secret` from the response — it is
   shown once. Confirm `mode` is `live`.
3. Railway: set `YOCO_SECRET_KEY` (live) and `YOCO_WEBHOOK_SECRET` (the value
   from step 2). Do **not** set `PAYMENTS_PROVIDER` yet. Redeploy and confirm
   the app still boots on Stripe.
4. Set `PAYMENTS_PROVIDER=yoco`, redeploy, check `/actuator/health`. A boot
   failure here means a missing key and is the validator doing its job.
5. **R5 real order end to end.** Pay with a real card. Watch `PENDING -> PAID`
   in the logs with the requestId, confirm the audit note reads
   `Payment completed (Yoco)`, confirm the order lands in the right tab, the
   vendor sees it, and it can be marked shipped.
6. Refund the R5 from the Yoco dashboard.
7. Rollback if anything fails: `PAYMENTS_PROVIDER=stripe`, redeploy. Stripe
   keys stay set permanently for exactly this reason. PayFast stays merged and
   inert.
8. Update the footer "We Accept" row (`PaymentMarks.tsx`) to the rails a
   **real** Yoco checkout page actually offers — read off the live checkout in
   step 5, not off Yoco's marketing pages. The current row lists PayPal, which
   is almost certainly wrong under Yoco. The rest of the site copy is already
   provider-neutral ("our secure payment provider") and needs no change.

## 4. Still open

- **The metadata echo is unproven.** The OpenAPI description says
  `payload.metadata` exists on `payment.succeeded`; it does not explicitly
  promise that it is the metadata supplied at *checkout creation*. Everything
  reconciles through that assumption. The sandbox contract check below is what
  proves it, and it must be done before step 5.
- **Sandbox contract check** (not yet run — needs real test keys): create a
  checkout against the test API from a local run, open `redirectUrl`, pay with
  Yoco's test card, and confirm the delivered webhook contains
  `payload.metadata.orderId`. Tunnel with `cloudflared tunnel --url
  http://localhost:8080` and register the tunnel URL as a test-mode webhook.
  Remember the R2.00 test minimum.
- Vendor split payouts: **no Yoco equivalent exists.** See `vendor-payouts.md`
  §6a — the ledger tier is now the only payout mechanism, not an interim one.

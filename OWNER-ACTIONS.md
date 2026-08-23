# Owner actions — payment 502 on live checkout

Do **not** put secrets in git, chat logs, or this file. The API now fails
fast at boot when the **selected** provider is missing keys or has an
unreadable mode, and `GET /api/v1/payments/health` reports
`{provider, mode, configured, lastErrorType}` with no secret values.

Live symptom this addresses: `POST /api/v1/orders/{id}/pay` returned
**502** with title/detail `Payment provider unavailable`. After deploy,
the RFC 7807 `type` (and `code`) tells keys apart from outages:

| `type` / `code` | Meaning |
|---|---|
| `https://erestyu.com/problems/payments:provider-misconfigured` | Keys, webhook secret, or test/live mix-up |
| `https://erestyu.com/problems/payments:provider-unavailable` | Upstream timeout, 5xx, or network |

Confirm with:

```bash
curl -sS https://api.erestyu.com/api/v1/payments/health
```

A healthy selected provider looks like
`{"provider":"yoco","mode":"live","configured":true,"lastErrorType":null}`
(`mode` is `test` or `live`; `lastErrorType` fills in after a 502).

Do **not** treat a browser console CORS error against `screendemos.com` as
this API. eRestyu uses header JWTs, not cookie credentials.

---

## Railway variables to verify (selected provider only)

Shared:

| Variable | Required | Notes |
|---|---|---|
| `PAYMENTS_PROVIDER` | yes | `stripe` \| `yoco` \| `payfast` (case-insensitive). Default in code is `stripe`. |
| `APP_FRONTEND_URL` | yes | Must be `https://erestyu.com` in production (success/cancel URLs derive from it). |
| `APP_CORS_ALLOWED_ORIGINS` | yes | `https://erestyu.com` — **no** localhost in prod. |

### If `PAYMENTS_PROVIDER=stripe`

| Variable | Required | Test vs live |
|---|---|---|
| `STRIPE_SECRET_KEY` | yes | `sk_test_…` (Dashboard → Test mode) or `sk_live_…`. Boot fails if the prefix is neither. |
| `STRIPE_WEBHOOK_SECRET` | yes | `whsec_…` from the **same** mode's webhook endpoint (`/api/v1/payments/stripe/webhook`). |

Checklist:

- [ ] Secret key mode matches the Stripe Dashboard toggle you intend to charge in.
- [ ] Webhook endpoint exists on **that** mode (test and live have different `whsec_` values).
- [ ] Webhook listens for `checkout.session.completed`.
- [ ] `APP_FRONTEND_URL` matches the domain on the Checkout success/cancel URLs.

### If `PAYMENTS_PROVIDER=yoco`

| Variable | Required | Test vs live |
|---|---|---|
| `YOCO_SECRET_KEY` | yes | Checkout API **Test keys** (`sk_test_…`) or **Live keys** (`sk_live_…`). |
| `YOCO_WEBHOOK_SECRET` | yes | `whsec_…` returned **once** by `POST https://payments.yoco.com/api/webhooks`. Mode-scoped: a test registration cannot verify live deliveries. |

Checklist:

- [ ] Key prefix matches the Yoco app tab (Test vs Live).
- [ ] Webhook URL is `https://api.erestyu.com/api/v1/payments/yoco/webhook`.
- [ ] Webhook was registered with the **same** mode's secret key.
- [ ] Test charges are ≥ R2.00 (Yoco rejects smaller test payments).

### If `PAYMENTS_PROVIDER=payfast`

| Variable | Required | Test vs live |
|---|---|---|
| `PAYFAST_MERCHANT_ID` | yes | Sandbox merchant or live merchant. Do not leave the public demo pair (`10000100`) in production. |
| `PAYFAST_MERCHANT_KEY` | yes | Matching key for that merchant. |
| `PAYFAST_PASSPHRASE` | live: yes | Must match the passphrase set on the PayFast dashboard. |
| `PAYFAST_PROCESS_URL` | yes | Sandbox: `https://sandbox.payfast.co.za/eng/process`. Live: `https://www.payfast.co.za/eng/process`. |
| `PAYFAST_VALIDATE_URL` | yes | Must be the **same mode** as process URL. Mixed sandbox/live fails boot. |
| `PAYFAST_NOTIFY_URL` | yes | `https://api.erestyu.com/api/v1/payments/payfast/itn` |
| `PAYFAST_VERIFY_SOURCE` | prod: `true` | Source-IP gate for ITN. Off locally (tunnels rewrite hops). |

Checklist:

- [ ] Process and validate URLs are both sandbox **or** both live.
- [ ] Merchant id/key are **your** sandbox or live pair, not the public demo.
- [ ] Passphrase is set on both Railway and the PayFast dashboard.
- [ ] ITN URL is reachable without JWT (it is `permitAll` + signed).

---

## After changing variables

1. Redeploy the API on Railway so `PaymentsConfigValidator` runs.
2. If the service crash-loops, the boot message names the missing variable — that is the intended failure, not a 502 on checkout.
3. Hit `/api/v1/payments/health` and confirm `configured: true` and the expected `mode`.
4. Place a **test-mode** order end to end before switching to live keys.
5. Do not deploy frontend-only; the 502 is an API/provider config problem.

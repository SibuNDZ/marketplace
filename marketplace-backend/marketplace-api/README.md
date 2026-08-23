# Marketplace API

Spring Boot **3.5.16** / **Java 25** / PostgreSQL 16 multi-vendor API for
eRestyu (`https://api.erestyu.com`). Shoppers browse and buy, vendors list
and fulfil, admins drive order state. Payments are Stripe, PayFast, or Yoco
behind `PAYMENTS_PROVIDER`. Flyway owns the schema (`V1`–`V26`); Hibernate
runs `ddl-auto: validate`.

## What it does

JWT with rotate-on-use refresh tokens and reuse detection. Product catalog
with vendor ownership, soft delete, categories (tree + handmade + tags),
full-text search (V21), Voyage embeddings for similar products (V22),
multi-image galleries (V24), and one-axis variants on the buy path (V20/V25).
Carts and checkout under pessimistic row locks. Order state machine
`PENDING → PAID → SHIPPED → DELIVERED` (plus `CANCELLED` / `REFUNDED`) with
an append-only audit table. Purchase-gated reviews. Honest signals
(`soldCount`, Bayesian rating, demand) derived from recorded facts — see
repo-root `ROADMAP.md`. Listing drafter (Anthropic), Resend email, R2 images,
all optional at boot except JWT, R2, and the **selected** payment provider
(`PaymentsConfigValidator`).

## Running it

Prerequisites: **JDK 25**, Docker.

```bash
docker compose up -d          # PostgreSQL 16 on :5434
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
# Flyway V1..V26; Swagger UI at /swagger-ui.html (dev only)
./mvnw test                   # Testcontainers PostgreSQL 16
```

Dev profile (`application-dev.yml`) supplies a JWT secret and local DB
password. Payment keys are **not** defaulted: either export the selected
provider's vars or see `OWNER-ACTIONS.md` at the repo root.

Production fail-fast: `JWT_SECRET`, `R2_ACCOUNT_ID` / `R2_ACCESS_KEY_ID` /
`R2_SECRET_ACCESS_KEY`, and the selected `PAYMENTS_PROVIDER` keys (see
`PaymentsConfigValidator`). Optional with blank-default degrade:
`ANTHROPIC_API_KEY`, `VOYAGE_API_KEY`, `RESEND_API_KEY`, `SENTRY_DSN`.

`GET /api/v1/payments/health` → `{provider, mode, configured, lastErrorType}`
(no secrets).

Vendor payouts (all optional; defaults carry PLACEHOLDER comments in
`application.yml`, see `vendor-payouts.md` section 7 for the owner actions):

| Variable | Default | Meaning |
|---|---|---|
| `PAYOUT_COMMISSION_RATE` | `0.125` (placeholder) | Platform commission as a fraction; per-vendor overrides live on `users.commission_rate` |
| `PAYOUT_WINDOW_DAYS` | `7` (placeholder) | Days after the weekly payout run promised in the vendor terms |
| `PAYOUT_TERMS_VERSION` | `1` | Bump on any change to terms wording or numbers, which forces vendor re-acceptance |
| `PAYOUT_SELLING_GATE` | `false` | When true, un-onboarded vendors (terms + banking) 409 at checkout |
| `PAYOUT_BACKFILL_COMMIT` | `false` | Set true for ONE deploy to write ledger entries for pre-ledger PAID orders (dry-run logs otherwise) |

## Tests

~40 test classes against PostgreSQL 16 in Testcontainers (same Flyway
migrations). Coverage includes: last-unit oversell and variant oversell,
same-user checkout double-submit, order state machine + audit rows,
refresh-token reuse, Stripe/PayFast/Yoco signatures and webhooks, payment
config fail-fast, RFC 7807 payment types, search synonyms, embeddings
persistence, reviews, images/R2 isolation, auth verification, vendor
scoping, compare-at pause, cart images.

CI (`.github/workflows/ci.yml`): `./mvnw verify` on Temurin 25, Docker
image boot smoke (JWT_SECRET fail-fast), frontend `tsc` + Vite build.

## Migrations (V1–V26)

| Version | What |
|---------|------|
| V1 | Baseline schema |
| V2 | `order_status_history` |
| V3 | Audit columns NOT NULL |
| V4 | Review unique (user, product) |
| V5 | `refresh_tokens` (hashed) |
| V6 | FK indexes |
| V7 | `PAID` in status CHECK |
| V8 | Product soft delete |
| V9 | Views / favorites / popularity |
| V10 | Product category |
| V11 | Product image (single) |
| V12 | Shipping address on orders |
| V13 | Email verification, username, tokens |
| V14 | Category tree, handmade, tags |
| V15 | Category expansion |
| V16 | Vendor delivery fees |
| V17 | Order tracking number |
| V18 | Platform feedback |
| V19 | Vendor business name |
| V20 | Product variants |
| V21 | Product search FTS |
| V22 | Product embeddings |
| V23 | `original_price` (compare-at; writes paused) |
| V24 | Product images (gallery) |
| V25 | Variant-aware cart |
| V26 | Search includes category name |

## Deployment

Multi-stage Dockerfile, non-root runtime. Railway: `PORT`,
`/actuator/health` probes, `forward-headers-strategy` in prod. Frontend is
Cloudflare Pages at `https://erestyu.com`. Payment cutover checklist:
`OWNER-ACTIONS.md`.

# Marketplace API

A production-grade multi-vendor e-commerce backend in Spring Boot 3.2 / Java 21 / PostgreSQL 16, built slice by slice with the concurrency, payment, and operational concerns a real marketplace has — not a CRUD demo with a shopping-cart table.

Customers browse and buy, vendors list and manage inventory, admins drive order fulfilment, Stripe takes the money, and every one of those paths is guarded by tests that run against real PostgreSQL.

## What it does

JWT authentication with rotate-on-use refresh tokens and reuse detection. A product catalog with vendor ownership enforcement and soft deletion that preserves order history. Carts, and a checkout that is safe under concurrency: pessimistic row locks with deadlock-preventing lock ordering, price/name snapshots at purchase time, and a same-user double-submit guard. An order lifecycle driven by an explicit state machine (`PENDING → PAID → SHIPPED → DELIVERED → REFUNDED`, with `CANCELLED` off `PENDING`) where every transition is recorded in an append-only audit table whose writes cannot commit independently of the status changes they describe. Stripe Checkout integration with signature-verified webhooks, idempotent event handling, and a payment-window expiry job that releases inventory held by abandoned checkouts. Purchase-verified reviews, where "verified" is a live query over delivered orders rather than a flag that can drift. And an operational layer: request correlation IDs on every log line and error body, JSON logging in production, per-IP rate limiting on authentication endpoints, Sentry error reporting, and a CI pipeline where the full test suite — including the concurrency tests — gates every push.

## Architecture in one paragraph

Controllers are thin and never see entities; DTOs cross the boundary and a single `@RestControllerAdvice` turns every failure into RFC 7807 `application/problem+json`, so clients parse exactly one error shape (including the correlation ID). Business rules live in services; invariants that must hold under concurrency are enforced by the database — row locks for stock and status transitions, unique constraints as backstops for double-submits — with the service layer providing the good error messages. Identity comes exclusively from the JWT via the security filter: cart and order routes contain no user IDs, which makes the classic IDOR bug unrepresentable rather than merely checked. The schema is owned by Flyway (8 migrations); Hibernate runs in `validate` and never writes DDL. Tests run against PostgreSQL in TestContainers built from those same migrations, so the suite is simultaneously a migration test.

## Engineering notes — the bugs worth reading about

These four issues shaped the codebase more than any feature did. They're documented here because none of them is visible from the final code alone.

**The oversell that locking didn't fix.** The first concurrency test (two threads racing for the last unit of stock) failed even though checkout acquired `SELECT … FOR UPDATE` correctly. The lock was real; the data was stale. The cart had been loaded with an entity graph that pulled products into Hibernate's first-level cache moments earlier, and Hibernate's contract is that a locking query returns the *cached managed instance* if one exists — lock acquired at the database, pre-lock state returned to the application. The fix (`entityManager.refresh()` after locking) was later extracted into a `lockAndRefresh` helper used by both checkout and cancellation, with the failure mode documented at the helper.

**The same bug, wearing a different hat.** The cart-row lock guarding against same-user checkout double-submits failed three implementation attempts for related reasons: Hibernate 6's `@Lock(PESSIMISTIC_WRITE)` on a JPQL query whose WHERE traverses an association path (`c.user.id`) does not reliably emit `FOR UPDATE`, and a native `SELECT * … FOR UPDATE` returning the entity re-poisoned the first-level cache. The pattern that works — and that two occurrences promoted to a project rule in `hibernate-locking.md` — is to lock the **scalar primary key** (`SELECT id … FOR UPDATE`, nothing enters the session) and reload the entity separately.

**The audit trail that couldn't lie, and the revocation that had to.** Order status history is written by a component with `Propagation.MANDATORY`: it refuses to run outside a transaction, guaranteeing every history row commits or rolls back *with* the change it describes. Refresh-token reuse detection needed the opposite: when a stolen token is replayed, all of the user's sessions are revoked *and then* the request fails — but a naive implementation participates in the caller's transaction, which the failure rolls back, un-revoking everything. The revocation runs in `REQUIRES_NEW` with `noRollbackFor`, committing the security response before the exception propagates. Two transaction-boundary designs, opposite in shape, each correct for its write.

**The enum value that would have failed in production, at webhook time, on a real payment.** Hibernate 6.2+ generates CHECK constraints for `@Enumerated(STRING)` columns, and the Flyway baseline had captured one on `orders.status`. Adding `PAID` to the enum without migrating the constraint compiles, boots, and passes every test that doesn't reach the webhook — then dies with a constraint violation the first time Stripe confirms a payment. Migration V7 drops and recreates the constraint; the incident that never happened is why "adding an enum value needs no migration" is only true until it isn't.

Two smaller decisions with outsized consequences: soft deletion deliberately avoids `@SQLDelete`/`@Where`, because entity-level filtering breaks `OrderItem.getProduct()` on historical orders — the very thing soft delete exists to protect — so filtering is explicit per-query; and vendor stock management is a **delta** operation (`+50 units`) rather than an absolute set, because a delta commutes with concurrent sales while "set stock to 25" from a stale form silently un-sells units.

## Running it

Prerequisites: JDK 21, Docker.

```bash
# 1. Database
docker compose up -d          # PostgreSQL 16 on :5434

# 2. Configure (dev profile reads these; see application-dev.yml)
#    JWT secret: any 32+ byte base64 value — generate with:
#    openssl rand -base64 32

# 3. Run
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
# Flyway applies V1..V8; Swagger UI at /swagger-ui.html (dev only)

# 4. Test — needs Docker running (TestContainers)
./mvnw test                   # 40 tests incl. 4 concurrency suites

# 5. End-to-end smoke (app running, second terminal)
powershell -ExecutionPolicy Bypass -File .\smoke-test.ps1
```

Required environment for production (fail-fast where marked):
`JWT_SECRET`★, `STRIPE_SECRET_KEY`★, `STRIPE_WEBHOOK_SECRET`★,
`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`,
`APP_BOOTSTRAP_ADMIN_EMAIL`, `APP_BOOTSTRAP_ADMIN_PASSWORD`
(seeds the first admin — without one, no order can ever ship),
`SENTRY_DSN` (optional; blank disables),
`SPRING_PROFILES_ACTIVE=prod`.

## Testing philosophy

Every test runs against PostgreSQL 16 in TestContainers, built by the real Flyway migrations — H2 was removed from the project after it masked two lock-semantics bugs that only reproduce on the real engine. The concurrency tests use `CyclicBarrier`-synchronized threads against genuinely committed data (fixtures commit in `REQUIRES_NEW`; the test classes are deliberately *not* `@Transactional`, which would deadlock against worker-thread row locks). The suite covers: last-unit oversell, same-user checkout double-submit, stock-adjustment racing checkout, illegal state transitions leaving zero audit rows, refresh-token reuse detection, webhook idempotency, payment-received-after-cancellation, and rate-limiter scoping.

## Migrations

| Version | What | Why it exists |
|---------|------|---------------|
| V1 | Baseline | `pg_dump` of the Hibernate-generated schema; round-trip verified |
| V2 | `order_status_history` | Append-only transition audit |
| V3 | Audit columns NOT NULL | Schema made to enforce what the app guarantees |
| V4 | Review constraints | Unique `(user, product)` — the double-submit backstop |
| V5 | `refresh_tokens` | SHA-256 hashes only; raw tokens never stored |
| V6 | FK indexes | PostgreSQL doesn't index FK columns; Hibernate didn't either |
| V7 | `PAID` in status CHECK | The Hibernate 6 enum-constraint trap, defused |
| V8 | Product soft delete | Partial indexes: live-only listing + SKU reuse after delete |

## Deployment

Multi-stage Dockerfile (layered jars, non-root runtime); GitHub Actions runs the full suite plus an image-boot smoke on every push. Railway deployment checklist — including the `PORT` mapping, `/actuator/health` probe path, and the forward-headers setting the rate limiter depends on behind a proxy — lives in the repo's integration notes.

---

*Built iteratively with AI-assisted development; every generated slice was integrated, adapted to the live codebase, and verified by tests before commit. The bugs in the engineering notes were all caught by tests written before deployment — which is the point of them.*

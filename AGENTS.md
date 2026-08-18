# AGENTS.md

Repo overview and pointers live in the root `README.md`,
`marketplace-backend/marketplace-api/README.md`, and `OWNER-ACTIONS.md`.
This file only captures durable, non-obvious guidance for agents.

## Cursor Cloud specific instructions

This is a two-service monorepo. The base VM ships Node 22 and Docker with
JDK 25 (Temurin) already provisioned; the startup update script only
refreshes project dependencies (`npm ci` for the frontend, Maven
dependency resolution for the API). Standard build/test/run commands are
documented in the two READMEs — the notes below are only the non-obvious
gotchas discovered during setup.

### Services

| Service | Path | Dev run | Port |
|---|---|---|---|
| API (Spring Boot 3.5 / Java 25) | `marketplace-backend/marketplace-api` | `./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"` | 8080 |
| Storefront (React 19 / Vite 6) | `marketplace-frontend` | `npm run dev` | 5173 |
| Postgres 16 (dev DB) | `marketplace-backend/marketplace-api` | `docker compose up -d` | 5434 (host) |

The frontend dev server talks to the API at `http://localhost:8080` by
default (`src/lib/api.ts` falls back when `VITE_API_URL` is unset).
`VITE_API_URL` is only *required* for a production `npm run build`.

### API boot requires payment provider keys (dev)

`PaymentsConfigValidator` fails fast at boot when the selected
`PAYMENTS_PROVIDER` (default `stripe`) is missing keys. The keys are only
*format*-validated at boot, so dummy test values are enough to run
locally — no real Stripe account is contacted:

```bash
export STRIPE_SECRET_KEY=sk_test_dummy_local
export STRIPE_WEBHOOK_SECRET=whsec_dummy_local
```

R2 storage vars (`R2_ACCOUNT_ID`, etc.) are **not** needed to boot — the
`R2Config` S3 client is `@Lazy`, so a missing key only surfaces as a 500
on the image-upload endpoint, not a boot crash.

Start Postgres (`docker compose up -d`) before the API; Flyway runs
migrations V1–V26 on boot against `localhost:5434`.

### Backend tests require `APP_CORS_ALLOWED_ORIGINS`

`./mvnw verify` / `./mvnw test` run ~40 test classes against PostgreSQL 16
in Testcontainers (Docker must be running). Every `@SpringBootTest`
(full-context) test needs `APP_CORS_ALLOWED_ORIGINS` exported, otherwise
the context fails with:

```
Could not resolve placeholder 'app.cors.allowed-origins'
```

Reason (non-obvious): `src/test/resources/application.yml` fully *shadows*
`src/main/resources/application.yml` (same classpath filename → only one
loaded), and the test copy does not define `app.cors.allowed-origins`.
The main file supplies a default, but tests never see it, and
`web/CorsOrigins` reads the property with no in-code default (removed in
the "audit 2.8" commit so prod has no localhost CORS fallback). So run:

```bash
export APP_CORS_ALLOWED_ORIGINS=http://localhost:5173
./mvnw --no-transfer-progress verify
```

### Known pre-existing test flake (not environment-related)

`service.ProductSignalsTest#catalogList_minSold_ranksBySales_andDropsUnsolds`
is order-dependent: it passes in isolation but fails when sibling methods
in the same class run first (they leave products with sales in the shared
Testcontainers DB, and the `minSold`/`sales`-sorted query then returns a
polluting product first). This is a repo-side test-isolation issue on this
branch, not a setup problem — everything else in the suite (269/270)
passes with `APP_CORS_ALLOWED_ORIGINS` set.

### Lint / typecheck

There is no separate ESLint config. The frontend "lint" gate is the
TypeScript build (`npm run build` = `tsc -b && vite build`). The backend
has no standalone linter; `./mvnw verify` (compile + tests) is the gate.

### Frontend test tooling

- Unit tests: `npm test` (Vitest, jsdom).
- E2E: `npm run test:e2e` (Playwright). Requires the Chromium browser —
  `npx playwright install --with-deps chromium`. Playwright starts its own
  Vite server on port 4173, independent of the `npm run dev` server.

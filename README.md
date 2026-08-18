# eRestyu marketplace

Monorepo for [erestyu.com](https://erestyu.com) (storefront) and
[api.erestyu.com](https://api.erestyu.com) (API).

```
marketplace-frontend/          React 19 + Vite 6 + TypeScript (strict)
marketplace-backend/marketplace-api/   Spring Boot 3.5 / Java 25 / PostgreSQL
OWNER-ACTIONS.md               Railway payment env checklist (no secrets)
ROADMAP.md                     Honest-signals house rules
```

Live frontend `VITE_API_URL` is committed in
`marketplace-frontend/.env.production` (public by construction). Real
secrets live in Railway only.

## Scripts

```bash
# API (JDK 25 + Docker)
cd marketplace-backend/marketplace-api
docker compose up -d
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
./mvnw verify

# Storefront
cd marketplace-frontend
npm ci
npm run dev          # http://localhost:5173 → API on :8080
npm run build        # tsc -b && vite build
```

CI: `.github/workflows/ci.yml` (API tests + image smoke + frontend build).

## Environment

See `marketplace-backend/marketplace-api/README.md` for API variables and
`OWNER-ACTIONS.md` before touching `PAYMENTS_PROVIDER` / Stripe / Yoco /
PayFast. Frontend needs `VITE_API_URL` at **build** time (guarded in
`vite.config.ts`).

Do not commit `.env.local` or `marketplace-api/.metadata/`.

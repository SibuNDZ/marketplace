/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// VITE_API_URL is baked into the bundle at BUILD time, not read at
// runtime — a production build made without it silently points at
// localhost:8080, which "works" on a laptop and 404s deployed. Same
// fail-fast philosophy as JWT_SECRET on the backend: catch it at build
// time, not in a user's browser console after a deploy.
//
// The check MUST use loadEnv, not process.env. Vite does not load .env
// files into process.env — they only reach import.meta.env in app code,
// and vite.config.ts is evaluated before that. A guard reading
// process.env.VITE_API_URL therefore cannot see .env.production and fails
// a build that has the value sitting right there. loadEnv reads the .env
// files AND merges matching process.env entries, so both sources work and
// a Pages/CI environment variable still overrides the file.
//
// Gated on `mode` rather than process.env.NODE_ENV: `vite build` sets mode
// to production, but does not necessarily set NODE_ENV before the config
// is evaluated, so the old check could pass over a broken build.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')

  if (mode === 'production' && !env.VITE_API_URL) {
    throw new Error(
      'VITE_API_URL must be set for production builds — it is compiled ' +
      'into the bundle, so this cannot be fixed after the build finishes. ' +
      'Set it in .env.production, or as a build environment variable.',
    )
  }

  return {
    plugins: [react()],
    server: {
      port: 5173,
      // Dev-only opt-in for browsing with real data: set VITE_API_URL to an
      // EMPTY string in .env.development.local and /api requests proxy to
      // the live API server-side, sidestepping CORS (the API rightly
      // refuses localhost origins). Same pattern the Electron app uses.
      // Opt-in on purpose: with the default VITE_API_URL, dev talks to a
      // local backend and cannot accidentally write to production.
      proxy: {
        '/api': {
          target: env.VITE_DEV_PROXY_TARGET || 'https://api.erestyu.com',
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
      include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    },
  }
})

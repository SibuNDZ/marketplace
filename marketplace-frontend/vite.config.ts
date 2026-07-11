import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// VITE_API_URL is baked into the bundle at BUILD time, not read at
// runtime — a production build made without it silently points at
// localhost:8080, which "works" on a laptop and 404s deployed. Same
// fail-fast philosophy as JWT_SECRET on the backend: catch it at build
// time, not in a user's browser console after a deploy.
if (process.env.NODE_ENV === 'production' && !process.env.VITE_API_URL) {
  throw new Error(
    'VITE_API_URL must be set for production builds — it is compiled ' +
    'into the bundle, so this cannot be fixed after the build finishes.',
  )
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
})

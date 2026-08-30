import React, { useEffect, useRef } from 'react'

/**
 * The official "Continue with Google" button (Google Identity Services).
 *
 * Renders nothing when VITE_GOOGLE_CLIENT_ID is not set, so environments
 * without a client ID simply have no button — mirroring the backend, whose
 * /auth/google answers 503 when its GOOGLE_CLIENT_ID is unset.
 *
 * The GIS script is loaded once and shared: login and register both mount
 * this, and double-injecting the script would double-initialize the SDK.
 */

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (config: {
            client_id: string
            callback: (response: { credential: string }) => void
          }) => void
          renderButton: (parent: HTMLElement, options: Record<string, unknown>) => void
        }
      }
    }
  }
}

const CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined

let gisLoad: Promise<void> | null = null
function loadGis(): Promise<void> {
  if (window.google?.accounts?.id) return Promise.resolve()
  if (!gisLoad) {
    gisLoad = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = 'https://accounts.google.com/gsi/client'
      script.async = true
      script.onload = () => resolve()
      script.onerror = () => {
        // Reset so a later mount can retry - ad blockers and flaky mobile
        // networks make one failed load a bad reason to lose the button
        // for the whole session.
        gisLoad = null
        reject(new Error('Google sign-in script failed to load'))
      }
      document.head.appendChild(script)
    })
  }
  return gisLoad
}

export function GoogleSignInButton({ onCredential, onError }: {
  /** Receives the GIS credential (a Google-signed ID token). Wrap in useCallback. */
  onCredential: (credential: string) => void
  /** Script blocked or failed to load. The page should stay silent: the password form is right there. */
  onError?: () => void
}) {
  const host = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!CLIENT_ID) return
    let cancelled = false
    loadGis()
      .then(() => {
        if (cancelled || !host.current || !window.google) return
        window.google.accounts.id.initialize({
          client_id: CLIENT_ID,
          callback: (response) => onCredential(response.credential),
        })
        window.google.accounts.id.renderButton(host.current, {
          theme: 'outline',
          size: 'large',
          text: 'continue_with',
          width: 328,
        })
      })
      .catch(() => { if (!cancelled) onError?.() })
    return () => { cancelled = true }
  }, [onCredential, onError])

  if (!CLIENT_ID) return null

  return (
    <>
      <div aria-hidden style={{
        display: 'flex', alignItems: 'center', gap: 10,
        margin: '18px 0 14px', color: 'var(--ink-soft)', fontSize: 12,
      }}>
        <span style={{ flex: 1, height: 1, background: 'var(--line)' }} />
        or
        <span style={{ flex: 1, height: 1, background: 'var(--line)' }} />
      </div>
      <div ref={host} style={{ display: 'flex', justifyContent: 'center' }} />
    </>
  )
}

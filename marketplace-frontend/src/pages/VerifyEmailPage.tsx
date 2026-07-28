import React, { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError, auth as authApi } from '../lib/api'
import { AuthShell } from '../components/auth/AuthShell'

type State =
  | { kind: 'verifying' }
  | { kind: 'done' }
  | { kind: 'failed'; message: string }

/**
 * Where the emailed confirmation link lands. Consumes the token on mount.
 */
export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [state, setState] = useState<State>({ kind: 'verifying' })

  // React 18 StrictMode double-invokes effects in development. The token is
  // single-use, so the second call would consume an already-burnt token and
  // report failure for a verification that actually succeeded. This guard
  // makes the request fire exactly once.
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    started.current = true

    if (!token) {
      setState({ kind: 'failed', message: 'This link is missing its confirmation code.' })
      return
    }

    authApi.verifyEmail(token)
      .then(() => setState({ kind: 'done' }))
      .catch((err) => setState({
        kind: 'failed',
        message: err instanceof ApiError
          ? err.detail || err.title
          : 'Could not reach the server. Check your connection and try again.',
      }))
  }, [token])

  if (state.kind === 'verifying') {
    return (
      <AuthShell title="Confirming your email">
        <p style={{ fontSize: 14, color: 'var(--ink-soft)' }}>One moment…</p>
      </AuthShell>
    )
  }

  if (state.kind === 'done') {
    return (
      <AuthShell title="Email confirmed">
        <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--ink-soft)' }}>
          Your account is active. You can sign in now.
        </p>
        <Link to="/login" style={{
          background: 'var(--ink)', color: '#fff', textDecoration: 'none',
          borderRadius: 'var(--r-sm)', padding: '11px', fontWeight: 600,
          fontSize: 15, textAlign: 'center',
        }}>
          Sign in
        </Link>
      </AuthShell>
    )
  }

  // Neutral title: this branch covers expired, already-used, and malformed
  // links, and "expired" is wrong for two of the three.
  return (
    <AuthShell title="This link didn't work">
      <p style={{
        fontSize: 14, lineHeight: 1.6, background: 'var(--clay-tint)',
        color: 'var(--clay)', padding: '12px 14px', borderRadius: 'var(--r-sm)',
      }}>
        {state.message}
      </p>
      {/* Sign-in is the route to a fresh link: attempting to log in
          unverified surfaces the resend button, so this does not dead-end. */}
      <Link to="/login" style={{
        color: 'var(--aloe)', fontWeight: 600, fontSize: 14, textAlign: 'center',
      }}>
        Back to sign in
      </Link>
    </AuthShell>
  )
}

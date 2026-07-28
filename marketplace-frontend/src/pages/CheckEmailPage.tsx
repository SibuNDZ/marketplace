import React, { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { auth as authApi } from '../lib/api'
import { AuthShell } from '../components/auth/AuthShell'

interface NavState { email?: string; emailSent?: boolean }

/**
 * Landing screen after registration.
 *
 * Two distinct states, and conflating them is the failure worth avoiding:
 * emailSent=true means go look in your inbox, emailSent=false means the
 * account exists but nothing was delivered. Telling the second group to
 * check their mail leaves them waiting for something that is never coming,
 * on an account they also cannot re-register.
 */
export function CheckEmailPage() {
  const { state } = useLocation()
  const { email, emailSent } = (state ?? {}) as NavState

  const [resent, setResent] = useState(false)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string>()

  const resend = async () => {
    if (!email) return
    setSending(true); setError(undefined)
    try {
      await authApi.resendVerification(email)
      setResent(true)
    } catch {
      setError('Could not send right now. Please try again in a moment.')
    } finally { setSending(false) }
  }

  // Reached directly rather than via the register redirect, so there is no
  // address to act on.
  if (!email) {
    return (
      <AuthShell title="Check your email">
        <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--ink-soft)' }}>
          Open the confirmation link we sent you to activate your account.
        </p>
        <Link to="/login" style={{ color: 'var(--aloe)', fontWeight: 600, fontSize: 14 }}>
          Back to sign in
        </Link>
      </AuthShell>
    )
  }

  return (
    <AuthShell title={emailSent ? 'Check your email' : 'Account created'}>
      {emailSent ? (
        <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--ink-soft)' }}>
          We sent a confirmation link to <strong style={{ color: 'var(--ink)' }}>{email}</strong>.
          Open it to finish setting up your account. It expires in 24 hours.
        </p>
      ) : (
        <p style={{
          fontSize: 14, lineHeight: 1.6, background: 'var(--clay-tint)',
          color: 'var(--clay)', padding: '12px 14px', borderRadius: 'var(--r-sm)',
        }}>
          Your account was created, but we could not send the confirmation email
          to <strong>{email}</strong> just now. Try again below.
        </p>
      )}

      {resent && (
        <p style={{ fontSize: 13, color: 'var(--aloe)' }}>
          Sent. If it does not arrive, check your spam folder.
        </p>
      )}
      {error && <p style={{ fontSize: 13, color: 'var(--clay)' }}>{error}</p>}

      <button onClick={resend} disabled={sending || resent}
        style={{
          background: 'var(--ink)', color: '#fff', border: 'none',
          borderRadius: 'var(--r-sm)', padding: '11px', fontWeight: 600,
          fontSize: 15, opacity: sending || resent ? 0.6 : 1,
        }}>
        {sending ? 'Sending…' : resent ? 'Email sent' : 'Resend confirmation email'}
      </button>

      <Link to="/login" style={{ color: 'var(--aloe)', fontWeight: 600, fontSize: 14, textAlign: 'center' }}>
        Back to sign in
      </Link>
    </AuthShell>
  )
}

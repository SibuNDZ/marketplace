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
        /* The email failing is OUR problem, not the user's: the backend
           auto-verifies the account in exactly this case, so the truthful
           message is "you're in", not an error banner. Sign-in is the
           primary action; resending is the afterthought. */
        <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--ink-soft)' }}>
          Your account for <strong style={{ color: 'var(--ink)' }}>{email}</strong> is
          ready. We could not send a confirmation email right now, but you do not
          need one: you can sign in straight away.
        </p>
      )}

      {resent && (
        <p style={{ fontSize: 13, color: 'var(--aloe)' }}>
          Sent. If it does not arrive, check your spam folder.
        </p>
      )}
      {error && <p style={{ fontSize: 13, color: 'var(--clay)' }}>{error}</p>}

      {emailSent ? (
        <>
          <button onClick={resend} disabled={sending || resent}
            style={{
              background: 'var(--ink)', color: 'var(--paper)', border: 'none',
              borderRadius: 'var(--r-sm)', padding: '11px', fontWeight: 600,
              fontSize: 15, opacity: sending || resent ? 0.6 : 1,
            }}>
            {sending ? 'Sending…' : resent ? 'Email sent' : 'Resend confirmation email'}
          </button>
          <Link to="/login" style={{ color: 'var(--aloe)', fontWeight: 600, fontSize: 14, textAlign: 'center' }}>
            Back to sign in
          </Link>
        </>
      ) : (
        <>
          <Link to="/login" style={{
            background: 'var(--ink)', color: 'var(--paper)', borderRadius: 'var(--r-sm)',
            padding: '11px', fontWeight: 600, fontSize: 15, textAlign: 'center',
          }}>
            Continue to sign in
          </Link>
          <button onClick={resend} disabled={sending || resent}
            style={{
              background: 'none', border: 'none', color: 'var(--aloe)',
              fontWeight: 600, fontSize: 14, cursor: 'pointer',
              opacity: sending || resent ? 0.6 : 1,
            }}>
            {sending ? 'Sending…' : resent ? 'Confirmation email sent' : 'Try sending the confirmation email again'}
          </button>
        </>
      )}
    </AuthShell>
  )
}

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
 *
 * The two states therefore lead with DIFFERENT actions:
 *
 *   emailSent=true  -> the inbox is the next step. Resending is the useful
 *                      button; signing in now would just fail the gate.
 *   emailSent=false -> AuthService's fail-safe already marked this account
 *                      verified precisely so a mail outage cannot lock
 *                      anyone out. Signing in works RIGHT NOW, so that is
 *                      the primary action and resending is the footnote.
 *
 * Leading with "Resend" in the failure state pushes every user toward
 * retrying the exact thing that just failed — and while the sending domain
 * is unverified, that retry cannot ever succeed. It reads as a dead end on
 * an account that is actually ready to use.
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
        <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--ink-soft)' }}>
          Your account is ready and you can sign in now. We could not send a
          confirmation email to <strong style={{ color: 'var(--ink)' }}>{email}</strong>,
          so nothing is waiting in your inbox.
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
          <button onClick={resend} disabled={sending || resent} style={primaryButton(sending || resent)}>
            {sending ? 'Sending…' : resent ? 'Email sent' : 'Resend confirmation email'}
          </button>
          <Link to="/login" style={secondaryLink}>Back to sign in</Link>
        </>
      ) : (
        <>
          <Link to="/login" style={{ ...primaryButton(false), textAlign: 'center', textDecoration: 'none' }}>
            Sign in
          </Link>
          <button onClick={resend} disabled={sending || resent} style={secondaryButton(sending || resent)}>
            {sending ? 'Sending…' : resent ? 'Email sent' : 'Try sending the email again'}
          </button>
        </>
      )}
    </AuthShell>
  )
}

const secondaryLink: React.CSSProperties = {
  color: 'var(--aloe)', fontWeight: 600, fontSize: 14, textAlign: 'center',
}

function primaryButton(dimmed: boolean): React.CSSProperties {
  return {
    background: 'var(--ink)', color: '#fff', border: 'none',
    borderRadius: 'var(--r-sm)', padding: '11px', fontWeight: 600,
    fontSize: 15, opacity: dimmed ? 0.6 : 1,
  }
}

/** Same shape, no fill — reads as the alternative, not the instruction. */
function secondaryButton(dimmed: boolean): React.CSSProperties {
  return {
    background: 'transparent', color: 'var(--ink-soft)',
    border: '1.5px solid var(--line)',
    borderRadius: 'var(--r-sm)', padding: '11px', fontWeight: 600,
    fontSize: 14, opacity: dimmed ? 0.6 : 1,
  }
}

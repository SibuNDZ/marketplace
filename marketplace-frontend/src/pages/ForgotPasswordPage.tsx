import React, { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, auth as authApi } from '../lib/api'
import { AuthShell } from '../components/auth/AuthShell'

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string>()

  const submit = async (e: FormEvent) => {
    e.preventDefault(); setLoading(true); setError(undefined)
    try {
      await authApi.forgotPassword(email)
      setSent(true)
    } catch (err) {
      // The endpoint returns 202 for unknown addresses too, so anything
      // thrown here is a transport or server fault, never "no such user".
      setError(err instanceof ApiError
        ? err.detail || err.title
        : 'Something went wrong')
    } finally { setLoading(false) }
  }

  if (sent) {
    return (
      <AuthShell title="Check your email">
        {/* Deliberately unconditional. The API does not reveal whether the
            address exists, and neither does this screen — a different
            message for unknown addresses would leak the account list. */}
        <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--ink-soft)' }}>
          If an account exists for <strong style={{ color: 'var(--ink)' }}>{email}</strong>,
          we have sent it a link to reset the password. It expires in 1 hour.
        </p>
        <Link to="/login" style={{ color: 'var(--aloe)', fontWeight: 600, fontSize: 14, textAlign: 'center' }}>
          Back to sign in
        </Link>
      </AuthShell>
    )
  }

  return (
    <AuthShell title="Reset your password">
      <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--ink-soft)' }}>
        Enter the email address on your account and we will send you a link to
        set a new password.
      </p>
      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {error && (
          <p style={{ background: 'var(--clay-tint)', color: 'var(--clay)', padding: '10px 14px', borderRadius: 'var(--r-sm)', fontSize: 13 }}>{error}</p>
        )}
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
          Email
          <input type="email" required value={email} onChange={e => setEmail(e.target.value)}
            autoCapitalize="none" autoCorrect="off"
            style={{ width: '100%', boxSizing: 'border-box', minWidth: 0, padding: '9px 12px', border: '1.5px solid var(--line)', borderRadius: 'var(--r-sm)', fontFamily: 'var(--body)', fontSize: 14 }} />
        </label>
        <button type="submit" disabled={loading}
          style={{ background: 'var(--ink)', color: 'var(--paper)', border: 'none', borderRadius: 'var(--r-sm)', padding: '11px', fontWeight: 600, fontSize: 15, opacity: loading ? 0.6 : 1 }}>
          {loading ? 'Sending…' : 'Send reset link'}
        </button>
      </form>
      <Link to="/login" style={{ color: 'var(--aloe)', fontWeight: 600, fontSize: 14, textAlign: 'center' }}>
        Back to sign in
      </Link>
    </AuthShell>
  )
}

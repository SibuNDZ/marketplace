import React, { FormEvent, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { ApiError, auth as authApi } from '../lib/api'
import { AuthShell } from '../components/auth/AuthShell'

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const navigate = useNavigate()

  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string>()

  const submit = async (e: FormEvent) => {
    e.preventDefault()

    // Checked here rather than only on the server: a mismatch is the common
    // typo and a round trip would burn the single-use token to tell them so.
    if (password !== confirm) {
      setError('The two passwords do not match.')
      return
    }
    if (!token) {
      setError('This link is missing its reset code. Request a new one.')
      return
    }

    setLoading(true); setError(undefined)
    try {
      await authApi.resetPassword(token, password)
      // Reset revokes every session server-side, so there is nothing to log
      // in with here — send them to sign in with the new password.
      navigate('/login', { replace: true, state: { passwordReset: true } })
    } catch (err) {
      setError(err instanceof ApiError
        ? err.detail || err.title
        : 'Something went wrong')
    } finally { setLoading(false) }
  }

  // Single-column here, so stretch already sizes these correctly — but keep
  // the same box model as RegisterPage so moving a field into a two-up row
  // later does not silently reintroduce the intrinsic-width overflow.
  const inputStyle: React.CSSProperties = {
    width: '100%', boxSizing: 'border-box', minWidth: 0,
    padding: '9px 12px', border: '1.5px solid var(--line)',
    borderRadius: 'var(--r-sm)', fontFamily: 'var(--body)', fontSize: 14,
  }

  return (
    <AuthShell title="Choose a new password">
      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {error && (
          <p style={{ background: 'var(--clay-tint)', color: 'var(--clay)', padding: '10px 14px', borderRadius: 'var(--r-sm)', fontSize: 13 }}>{error}</p>
        )}
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
          New password
          <input type="password" required minLength={8} value={password}
            onChange={e => setPassword(e.target.value)} style={inputStyle} />
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
          Confirm new password
          <input type="password" required minLength={8} value={confirm}
            onChange={e => setConfirm(e.target.value)} style={inputStyle} />
        </label>
        <button type="submit" disabled={loading}
          style={{ background: 'var(--ink)', color: 'var(--paper)', border: 'none', borderRadius: 'var(--r-sm)', padding: '11px', fontWeight: 600, fontSize: 15, opacity: loading ? 0.6 : 1 }}>
          {loading ? 'Saving…' : 'Set new password'}
        </button>
      </form>
      <p style={{ fontSize: 12, color: 'var(--ink-soft)', lineHeight: 1.5 }}>
        Setting a new password signs you out everywhere else.
      </p>
      <Link to="/forgot-password" style={{ color: 'var(--aloe)', fontWeight: 600, fontSize: 14, textAlign: 'center' }}>
        Request a new link
      </Link>
    </AuthShell>
  )
}

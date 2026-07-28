import React, { FormEvent, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { ApiError, auth as authApi } from '../lib/api'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const { state } = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [loading, setLoading] = useState(false)
  // Set when the server rejects a CORRECT password because the address is
  // unconfirmed. Without this the user is told their credentials are wrong,
  // retries the same correct password forever, and has no route to a new
  // confirmation link.
  const [needsVerification, setNeedsVerification] = useState(false)
  const [resent, setResent] = useState(false)

  const justReset = (state as { passwordReset?: boolean } | null)?.passwordReset

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setLoading(true); setError(undefined); setNeedsVerification(false)
    try {
      await login(email, password)
      navigate('/')
    } catch (err) {
      if (err instanceof ApiError && err.code === 'EMAIL_NOT_VERIFIED') {
        setNeedsVerification(true)
        setError(err.detail || err.title)
      } else {
        setError(err instanceof ApiError ? err.detail || err.title : 'Something went wrong')
      }
    } finally {
      setLoading(false)
    }
  }

  const resend = async () => {
    try {
      await authApi.resendVerification(email)
      setResent(true)
    } catch {
      setError('Could not send right now. Please try again in a moment.')
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--paper)', padding: 24 }}>
      <div style={{ background: 'var(--card)', borderRadius: 'var(--r)', padding: '40px 36px', width: '100%', maxWidth: 400, boxShadow: 'var(--shadow)' }}>
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <div style={{
            fontFamily: 'var(--display)', fontWeight: 800, fontSize: 28, letterSpacing: '-0.03em', marginBottom: 8,
            background: 'var(--flame-gradient)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent',
            backgroundClip: 'text', display: 'inline-block',
          }}>
            eRestyu
          </div>
          <p style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 20, color: 'var(--ink)' }}>Welcome back</p>
        </div>
        <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {justReset && !error && (
            <p style={{ background: 'var(--aloe-tint)', color: 'var(--aloe)', padding: '10px 14px', borderRadius: 'var(--r-sm)', fontSize: 13 }}>
              Password updated. Sign in with your new password.
            </p>
          )}
          {error && (
            <p style={{ background: 'var(--clay-tint)', color: 'var(--clay)', padding: '10px 14px', borderRadius: 'var(--r-sm)', fontSize: 13 }}>{error}</p>
          )}
          {needsVerification && (
            resent ? (
              <p style={{ fontSize: 13, color: 'var(--aloe)' }}>
                Confirmation email sent. Check your inbox and spam folder.
              </p>
            ) : (
              <button type="button" onClick={resend}
                style={{ background: 'none', border: 'none', padding: 0, textAlign: 'left', color: 'var(--aloe)', fontWeight: 600, fontSize: 13, cursor: 'pointer', textDecoration: 'underline' }}>
                Resend the confirmation email
              </button>
            )
          )}
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
            Email
            <input type="email" required value={email} onChange={e => setEmail(e.target.value)}
              style={{ padding: '9px 12px', border: '1.5px solid var(--line)', borderRadius: 'var(--r-sm)', fontFamily: 'var(--body)', fontSize: 14 }} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
            Password
            <input type="password" required value={password} onChange={e => setPassword(e.target.value)}
              style={{ padding: '9px 12px', border: '1.5px solid var(--line)', borderRadius: 'var(--r-sm)', fontFamily: 'var(--body)', fontSize: 14 }} />
          </label>
          <button type="submit" disabled={loading}
            style={{ background: 'var(--ink)', color: '#fff', border: 'none', borderRadius: 'var(--r-sm)', padding: '11px', fontWeight: 600, fontSize: 15, marginTop: 6 }}>
            {loading ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
        <p style={{ textAlign: 'center', marginTop: 16, fontSize: 13 }}>
          <Link to="/forgot-password" style={{ color: 'var(--ink-soft)' }}>Forgot your password?</Link>
        </p>
        <p style={{ textAlign: 'center', marginTop: 8, fontSize: 13, color: 'var(--ink-soft)' }}>
          No account? <Link to="/register" style={{ color: 'var(--aloe)', fontWeight: 600 }}>Register</Link>
        </p>
      </div>
    </div>
  )
}

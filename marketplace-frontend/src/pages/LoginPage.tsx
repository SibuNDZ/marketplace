import React, { FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { ApiError } from '../lib/api'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [loading, setLoading] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setLoading(true); setError(undefined)
    try {
      await login(email, password)
      navigate('/')
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.title : 'Something went wrong')
    } finally {
      setLoading(false)
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
          {error && (
            <p style={{ background: 'var(--clay-tint)', color: 'var(--clay)', padding: '10px 14px', borderRadius: 'var(--r-sm)', fontSize: 13 }}>{error}</p>
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
        <p style={{ textAlign: 'center', marginTop: 20, fontSize: 13, color: 'var(--ink-soft)' }}>
          No account? <Link to="/register" style={{ color: 'var(--aloe)', fontWeight: 600 }}>Register</Link>
        </p>
      </div>
    </div>
  )
}

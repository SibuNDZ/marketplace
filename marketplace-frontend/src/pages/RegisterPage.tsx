import React, { FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { ApiError } from '../lib/api'

export function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const [role, setRole] = useState<'CUSTOMER' | 'VENDOR'>('CUSTOMER')
  const [form, setForm] = useState({ email: '', password: '', firstName: '', lastName: '' })
  const [error, setError] = useState<string>()
  const [loading, setLoading] = useState(false)

  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }))

  const submit = async (e: FormEvent) => {
    e.preventDefault(); setLoading(true); setError(undefined)
    try {
      await register({ ...form, role })
      navigate('/')
    } catch (err) {
      setError(err instanceof ApiError ? err.detail || err.title : 'Something went wrong')
    } finally { setLoading(false) }
  }

  const card = (r: 'CUSTOMER' | 'VENDOR', icon: string, title: string, sub: string) => (
    <button type="button" onClick={() => setRole(r)} style={{
      flex: 1, padding: '18px 16px', border: `2px solid ${role === r ? 'var(--aloe)' : 'var(--line)'}`,
      borderRadius: 'var(--r)', background: role === r ? 'var(--aloe-tint)' : 'var(--card)',
      textAlign: 'left', cursor: 'pointer',
    }}>
      <div style={{ fontSize: 22, marginBottom: 6 }}>{icon}</div>
      <div style={{ fontWeight: 700, fontSize: 15 }}>{title}</div>
      <div style={{ fontSize: 12, color: 'var(--ink-soft)', marginTop: 2 }}>{sub}</div>
    </button>
  )

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--paper)', padding: 24 }}>
      <div style={{ background: 'var(--card)', borderRadius: 'var(--r)', padding: '40px 36px', width: '100%', maxWidth: 440, boxShadow: 'var(--shadow)' }}>
        <div style={{ textAlign: 'center', marginBottom: 28 }}>
          <div style={{
            fontFamily: 'var(--display)', fontWeight: 800, fontSize: 28, letterSpacing: '-0.03em', marginBottom: 6,
            background: 'var(--flame-gradient)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent',
            backgroundClip: 'text', display: 'inline-block',
          }}>
            eRestyu
          </div>
          <p style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 18, color: 'var(--ink)' }}>Create an account</p>
        </div>

        <div style={{ display: 'flex', gap: 12, marginBottom: 24 }}>
          {card('CUSTOMER', '🛍️', "I'm buying", 'Browse and shop')}
          {card('VENDOR', '🏪', "I'm selling", 'List your products')}
        </div>

        <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {error && (
            <p style={{ background: 'var(--clay-tint)', color: 'var(--clay)', padding: '10px 14px', borderRadius: 'var(--r-sm)', fontSize: 13 }}>{error}</p>
          )}
          <div style={{ display: 'flex', gap: 12 }}>
            {['firstName', 'lastName'].map((k) => (
              <label key={k} style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
                {k === 'firstName' ? 'First name' : 'Last name'}
                <input required value={(form as any)[k]} onChange={e => set(k, e.target.value)}
                  style={{ padding: '9px 12px', border: '1.5px solid var(--line)', borderRadius: 'var(--r-sm)', fontFamily: 'var(--body)', fontSize: 14 }} />
              </label>
            ))}
          </div>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
            Email
            <input type="email" required value={form.email} onChange={e => set('email', e.target.value)}
              style={{ padding: '9px 12px', border: '1.5px solid var(--line)', borderRadius: 'var(--r-sm)', fontFamily: 'var(--body)', fontSize: 14 }} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
            Password
            <input type="password" required minLength={8} value={form.password} onChange={e => set('password', e.target.value)}
              style={{ padding: '9px 12px', border: '1.5px solid var(--line)', borderRadius: 'var(--r-sm)', fontFamily: 'var(--body)', fontSize: 14 }} />
          </label>
          <button type="submit" disabled={loading}
            style={{ background: 'var(--ink)', color: '#fff', border: 'none', borderRadius: 'var(--r-sm)', padding: '11px', fontWeight: 600, fontSize: 15, marginTop: 6 }}>
            {loading ? 'Creating account…' : 'Create account'}
          </button>
        </form>
        <p style={{ textAlign: 'center', marginTop: 20, fontSize: 13, color: 'var(--ink-soft)' }}>
          Already have an account? <Link to="/login" style={{ color: 'var(--aloe)', fontWeight: 600 }}>Sign in</Link>
        </p>
      </div>
    </div>
  )
}

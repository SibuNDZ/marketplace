import React, { FormEvent, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { ApiError, auth as authApi, fieldErrorsFrom } from '../lib/api'

const USERNAME_RE = /^[a-zA-Z0-9_]{3,30}$/

type UsernameState =
  | { kind: 'idle' }
  | { kind: 'invalid'; message: string }
  | { kind: 'checking' }
  | { kind: 'taken' }
  | { kind: 'free' }

export function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const [role, setRole] = useState<'CUSTOMER' | 'VENDOR'>('CUSTOMER')
  const [form, setForm] = useState({
    email: '', password: '', firstName: '', lastName: '', username: '',
  })
  const [error, setError] = useState<string>()
  const [fieldErrors, setFieldErrors] = useState<Record<string, string[]>>({})
  const [loading, setLoading] = useState(false)
  const [usernameState, setUsernameState] = useState<UsernameState>({ kind: 'idle' })

  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }))

  // Debounced availability check. 400ms is long enough that typing a name
  // does not fire a request per keystroke, short enough to resolve before
  // the user reaches the submit button. Shape is validated locally first so
  // an obviously bad username never costs a round trip.
  useEffect(() => {
    const username = form.username.trim()

    if (!username) { setUsernameState({ kind: 'idle' }); return }
    if (!USERNAME_RE.test(username)) {
      setUsernameState({
        kind: 'invalid',
        message: '3-30 characters, letters, numbers or underscore',
      })
      return
    }

    setUsernameState({ kind: 'checking' })
    let cancelled = false
    const timer = setTimeout(async () => {
      try {
        const r = await authApi.usernameAvailable(username)
        // Guard against an earlier slower request overwriting a later
        // answer — without this, fast typing can land a stale verdict.
        if (!cancelled) setUsernameState({ kind: r.available ? 'free' : 'taken' })
      } catch {
        // Availability is a convenience; the server rejects duplicates on
        // submit regardless, so a failed check must not block the form.
        if (!cancelled) setUsernameState({ kind: 'idle' })
      }
    }, 400)

    return () => { cancelled = true; clearTimeout(timer) }
  }, [form.username])

  const submit = async (e: FormEvent) => {
    e.preventDefault(); setLoading(true); setError(undefined); setFieldErrors({})
    try {
      const result = await register({ ...form, role })
      // Never lands in the app: the account cannot sign in until confirmed.
      navigate('/check-email', {
        state: { email: result.email, emailSent: result.emailSent },
        replace: true,
      })
    } catch (err) {
      if (err instanceof ApiError) {
        setFieldErrors(fieldErrorsFrom(err))
        setError(err.detail || err.title)
      } else {
        setError('Something went wrong')
      }
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

  const inputStyle: React.CSSProperties = {
    padding: '9px 12px', border: '1.5px solid var(--line)',
    borderRadius: 'var(--r-sm)', fontFamily: 'var(--body)', fontSize: 14,
  }

  const usernameHint = () => {
    switch (usernameState.kind) {
      case 'checking': return <span style={{ color: 'var(--ink-soft)' }}>Checking…</span>
      case 'free':     return <span style={{ color: 'var(--aloe)' }}>✓ Available</span>
      case 'taken':    return <span style={{ color: 'var(--clay)' }}>Already taken</span>
      case 'invalid':  return <span style={{ color: 'var(--clay)' }}>{usernameState.message}</span>
      default:         return <span style={{ color: 'var(--ink-soft)' }}>Your public name on eRestyu</span>
    }
  }

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
            <label style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
              First name
              <input required value={form.firstName} onChange={e => set('firstName', e.target.value)}
                style={inputStyle} />
            </label>
            <label style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
              {/* Not required: mononyms are common, and the API accepts an
                  absent surname. */}
              Last name <span style={{ fontWeight: 400, color: 'var(--ink-soft)' }}>(optional)</span>
              <input value={form.lastName} onChange={e => set('lastName', e.target.value)}
                style={inputStyle} />
            </label>
          </div>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
            Username
            <input required value={form.username} onChange={e => set('username', e.target.value)}
              autoCapitalize="none" autoCorrect="off" spellCheck={false}
              style={{
                ...inputStyle,
                borderColor: usernameState.kind === 'taken' || usernameState.kind === 'invalid'
                  ? 'var(--clay)' : 'var(--line)',
              }} />
            <span style={{ fontSize: 12, fontWeight: 400 }}>
              {fieldErrors.username?.[0] ?? usernameHint()}
            </span>
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
            Email
            <input type="email" required value={form.email} onChange={e => set('email', e.target.value)}
              autoCapitalize="none" autoCorrect="off"
              style={inputStyle} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
            Password
            <input type="password" required minLength={8} value={form.password} onChange={e => set('password', e.target.value)}
              style={inputStyle} />
          </label>
          <button type="submit" disabled={loading || usernameState.kind === 'taken'}
            style={{
              background: 'var(--ink)', color: '#fff', border: 'none', borderRadius: 'var(--r-sm)',
              padding: '11px', fontWeight: 600, fontSize: 15, marginTop: 6,
              opacity: loading || usernameState.kind === 'taken' ? 0.6 : 1,
            }}>
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

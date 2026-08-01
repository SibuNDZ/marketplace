import React, { useEffect, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'

interface AccountProfile {
  email: string
  username: string
  firstName: string
  lastName: string
  phoneNumber?: string | null
  role: string
}

const inputStyle: React.CSSProperties = {
  padding: '10px 12px', border: '1px solid var(--line)', borderRadius: 'var(--r-sm)',
  fontSize: 14, background: 'var(--card)',
}
const fieldStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500,
}

/**
 * Self-service profile editing. Email, username, and role are shown but not
 * editable here: email is the verified login identifier (changing it needs a
 * re-verification flow), username is the unique public handle, and role
 * changes are an admin decision. Password changes go through the existing
 * reset flow.
 */
export function AccountSettingsPage() {
  const qc = useQueryClient()
  const { data: profile } = useQuery<AccountProfile>({
    queryKey: ['account'],
    queryFn: () => api('/api/v1/account'),
  })

  const [form, setForm] = useState({ firstName: '', lastName: '', phoneNumber: '' })
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string>()
  useEffect(() => {
    if (profile) {
      setForm({
        firstName: profile.firstName ?? '',
        lastName: profile.lastName ?? '',
        phoneNumber: profile.phoneNumber ?? '',
      })
    }
  }, [profile])

  const save = useMutation({
    mutationFn: () => api('/api/v1/account', { method: 'PUT', body: form }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['account'] })
      setError(undefined)
      setSaved(true)
      setTimeout(() => setSaved(false), 2500)
    },
    onError: (e) => setError(e instanceof ApiError ? e.detail : 'Could not save. Check the fields and try again.'),
  })

  if (!profile) return <><Topbar /><div className="page-shell no-catrail">Loading…</div></>

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail" style={{ maxWidth: 560 }}>
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28, marginBottom: 24 }}>
          Account settings
        </h1>

        <div style={{
          background: 'var(--card)', borderRadius: 'var(--r)', padding: 24,
          boxShadow: 'var(--shadow)', display: 'flex', flexDirection: 'column', gap: 16,
        }}>
          <div style={{ display: 'flex', gap: 12 }}>
            <label style={{ ...fieldStyle, flex: 1 }}>
              Email
              <input value={profile.email} disabled style={{ ...inputStyle, color: 'var(--ink-soft)', background: 'var(--paper)' }} />
            </label>
            <label style={{ ...fieldStyle, flex: 1 }}>
              Username
              <input value={profile.username} disabled style={{ ...inputStyle, color: 'var(--ink-soft)', background: 'var(--paper)' }} />
            </label>
          </div>
          <p style={{ fontSize: 12, color: 'var(--ink-soft)', marginTop: -8 }}>
            Email and username cannot be changed here. To change your password, use
            the reset link on the sign-in page.
          </p>

          <div style={{ display: 'flex', gap: 12 }}>
            <label style={{ ...fieldStyle, flex: 1 }}>
              First name
              <input required value={form.firstName}
                onChange={e => setForm(f => ({ ...f, firstName: e.target.value }))} style={inputStyle} />
            </label>
            <label style={{ ...fieldStyle, flex: 1 }}>
              Last name
              <input value={form.lastName} placeholder="Optional"
                onChange={e => setForm(f => ({ ...f, lastName: e.target.value }))} style={inputStyle} />
            </label>
          </div>

          <label style={fieldStyle}>
            Phone number
            <input value={form.phoneNumber} placeholder="Optional"
              onChange={e => setForm(f => ({ ...f, phoneNumber: e.target.value }))} style={inputStyle} />
          </label>

          {error && <p style={{ fontSize: 13, color: 'var(--clay)' }}>{error}</p>}

          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <button
              onClick={() => save.mutate()}
              disabled={save.isPending || !form.firstName.trim()}
              style={{
                padding: '10px 22px', background: 'var(--flame-gradient)', color: '#fff',
                border: 'none', borderRadius: 'var(--r-sm)', fontWeight: 700, cursor: 'pointer',
                opacity: save.isPending || !form.firstName.trim() ? 0.6 : 1,
              }}>
              {save.isPending ? 'Saving…' : 'Save changes'}
            </button>
            {saved && <span style={{ fontSize: 13, color: 'var(--aloe)' }}>Saved</span>}
          </div>
        </div>
      </main>
    </>
  )
}

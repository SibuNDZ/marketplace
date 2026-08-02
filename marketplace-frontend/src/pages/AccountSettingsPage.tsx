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
  /** Null for buyers; the public storefront name for vendors. */
  businessName?: string | null
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

  const [form, setForm] = useState({ firstName: '', lastName: '', phoneNumber: '', businessName: '' })
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string>()
  useEffect(() => {
    if (profile) {
      setForm({
        firstName: profile.firstName ?? '',
        lastName: profile.lastName ?? '',
        phoneNumber: profile.phoneNumber ?? '',
        businessName: profile.businessName ?? '',
      })
    }
  }, [profile])
  const isVendor = profile?.role === 'VENDOR'

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

          {isVendor && (
            <label style={fieldStyle}>
              Business name
              <input required value={form.businessName}
                onChange={e => setForm(f => ({ ...f, businessName: e.target.value }))}
                style={inputStyle} />
              <span style={{ fontSize: 12, color: 'var(--ink-soft)', fontWeight: 400 }}>
                Shown on every one of your listings, in place of your personal name.
              </span>
            </label>
          )}

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

        {profile.role === 'CUSTOMER' && <BecomeSeller />}
      </main>
    </>
  )
}

/**
 * Buyer -> seller, in place. Registration already lets anyone choose "I'm
 * selling" with no gate, so this asks for the same two things a seller
 * signup does and nothing more: no application, no waiting, no implied
 * vetting that does not exist.
 */
function BecomeSeller() {
  const qc = useQueryClient()
  const [open, setOpen] = useState(false)
  const [businessName, setBusinessName] = useState('')
  const [lastName, setLastName] = useState('')
  const [error, setError] = useState<string>()

  const upgrade = useMutation({
    mutationFn: () =>
      api('/api/v1/account/become-vendor', { method: 'POST', body: { businessName, lastName } }),
    onSuccess: () => {
      // The role is live on the next request (the API reloads the user each
      // time), so refreshing cached identity is all that is needed.
      qc.invalidateQueries({ queryKey: ['account'] })
      qc.invalidateQueries({ queryKey: ['auth'] })
      window.location.assign('/vendor')
    },
    onError: (e) => setError(e instanceof ApiError ? e.detail : 'Could not switch your account. Try again.'),
  })

  return (
    <section style={{
      marginTop: 24, padding: 20, borderRadius: 'var(--r)',
      border: '1px solid var(--aloe)', background: 'var(--aloe-tint)',
    }}>
      <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 18, marginBottom: 6 }}>
        Want to sell on eRestyu?
      </h2>
      <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--ink-soft)', marginBottom: open ? 16 : 0 }}>
        Turn this account into a seller account. You keep your orders and your
        details; you gain a stall where you can list products.
      </p>

      {!open ? (
        <button onClick={() => setOpen(true)} style={{
          marginTop: 14, padding: '10px 20px', background: 'var(--aloe)', color: '#fff',
          border: 'none', borderRadius: 'var(--r-sm)', fontWeight: 700, cursor: 'pointer', minHeight: 44,
        }}>
          Start selling
        </button>
      ) : (
        <form onSubmit={e => { e.preventDefault(); setError(undefined); upgrade.mutate() }}
          style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <label style={fieldStyle}>
            Business name
            <input required value={businessName} onChange={e => setBusinessName(e.target.value)}
              placeholder="e.g. Morning Star Essentials" style={inputStyle} />
            <span style={{ fontSize: 12, color: 'var(--ink-soft)', fontWeight: 400 }}>
              Buyers see this on your listings, not your personal name.
            </span>
          </label>
          <label style={fieldStyle}>
            Last name
            <input required value={lastName} onChange={e => setLastName(e.target.value)}
              style={inputStyle} />
            <span style={{ fontSize: 12, color: 'var(--ink-soft)', fontWeight: 400 }}>
              Required for seller accounts.
            </span>
          </label>

          {error && <p style={{ fontSize: 13, color: 'var(--clay)' }}>{error}</p>}

          <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
            <button type="submit" disabled={upgrade.isPending || !businessName.trim() || !lastName.trim()}
              style={{
                padding: '10px 20px', background: 'var(--aloe)', color: '#fff', border: 'none',
                borderRadius: 'var(--r-sm)', fontWeight: 700, cursor: 'pointer', minHeight: 44,
                opacity: upgrade.isPending || !businessName.trim() || !lastName.trim() ? 0.6 : 1,
              }}>
              {upgrade.isPending ? 'Switching…' : 'Become a seller'}
            </button>
            <button type="button" onClick={() => setOpen(false)} style={{
              background: 'none', border: 'none', color: 'var(--ink-soft)',
              fontSize: 13, fontWeight: 600, cursor: 'pointer', minHeight: 44,
            }}>
              Not now
            </button>
          </div>
        </form>
      )}
    </section>
  )
}

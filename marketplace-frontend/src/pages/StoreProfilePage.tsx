import React, { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, OwnStoreProfile, uploadStoreAvatar } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { StoreAvatar } from '../components/ui/StoreAvatar'
import { useAuth } from '../context/AuthContext'

const BIO_MAX = 500
const ACCEPT = 'image/jpeg,image/png,image/webp'

/**
 * The vendor's public store profile: the picture and bio buyers see in the
 * home page spotlight and at the top of their shop page.
 *
 * Only sellers have one: it describes a storefront, and a buyer has no public
 * page for it to appear on. A buyer who lands here is pointed at becoming a
 * seller rather than shown a form the API would refuse.
 */
export function StoreProfilePage() {
  const { user } = useAuth()
  const isVendor = user?.role === 'VENDOR'

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail" style={{ maxWidth: 640 }}>
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28, marginBottom: 6 }}>
          Store profile
        </h1>
        {isVendor ? <ProfileForm /> : (
          <p style={{ fontSize: 15, lineHeight: 1.6, color: 'var(--ink-soft)' }}>
            A store profile is the picture and short bio buyers see on your shop page. It
            belongs to seller accounts. <Link to="/account#start-selling">Start selling</Link> to
            set one up.
          </p>
        )}
      </main>
    </>
  )
}

function ProfileForm() {
  const qc = useQueryClient()
  const fileInput = useRef<HTMLInputElement>(null)
  const [bio, setBio] = useState('')
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string>()

  const { data: profile } = useQuery<OwnStoreProfile>({
    queryKey: ['store-profile'],
    queryFn: () => api('/api/v1/account/profile'),
  })

  // Seed the textarea once the profile arrives; after that it is the
  // vendor's draft and a background refetch must not overwrite their typing.
  const seeded = useRef(false)
  useEffect(() => {
    if (profile && !seeded.current) {
      setBio(profile.bio ?? '')
      seeded.current = true
    }
  }, [profile])

  const onSaved = (p: OwnStoreProfile) => {
    qc.setQueryData(['store-profile'], p)
    // The spotlight and shop page read the public profile; show the change there too.
    qc.invalidateQueries({ queryKey: ['vendors'] })
  }
  const fail = (e: unknown, fallback: string) =>
    setError(e instanceof ApiError ? e.detail || e.title : fallback)

  const saveBio = useMutation({
    mutationFn: () => api<OwnStoreProfile>('/api/v1/account/profile', { method: 'PUT', body: { bio } }),
    onSuccess: p => { onSaved(p); setSaved(true) },
    onError: e => fail(e, 'Could not save your bio. Try again.'),
  })

  const upload = useMutation({
    mutationFn: (file: File) => uploadStoreAvatar(file),
    onSuccess: onSaved,
    onError: e => fail(e, 'Could not upload that picture. Try again.'),
  })

  const remove = useMutation({
    mutationFn: () => api<OwnStoreProfile>('/api/v1/account/profile/avatar', { method: 'DELETE' }),
    onSuccess: onSaved,
    onError: e => fail(e, 'Could not remove the picture. Try again.'),
  })

  const pickFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    e.target.value = '' // so choosing the same file again still fires
    if (!file) return
    setError(undefined)
    // Checked here for a quick answer; the server applies the same rules.
    if (!ACCEPT.split(',').includes(file.type)) {
      setError('Use a JPG, PNG or WebP picture.')
      return
    }
    if (file.size > 5 * 1024 * 1024) {
      setError('That picture is over 5MB. Try a smaller one.')
      return
    }
    upload.mutate(file)
  }

  if (!profile) return <p style={{ color: 'var(--ink-soft)' }}>Loading…</p>

  const remaining = BIO_MAX - bio.length
  const busy = upload.isPending || remove.isPending

  return (
    <>
      <p style={{ fontSize: 14.5, lineHeight: 1.6, color: 'var(--ink-soft)', marginBottom: 24 }}>
        This is what buyers see about <strong style={{ color: 'var(--ink)' }}>{profile.name}</strong> in
        the home page spotlight and at the top of your shop page.
      </p>

      {error && <p role="alert" style={errorStyle}>{error}</p>}

      <section style={card}>
        <h2 style={h2}>Picture</h2>
        <div style={{ display: 'flex', alignItems: 'center', gap: 18, flexWrap: 'wrap' }}>
          <StoreAvatar name={profile.name} url={profile.avatarUrl} size={88} />
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <button type="button" disabled={busy} onClick={() => fileInput.current?.click()} style={primary}>
                {upload.isPending ? 'Uploading…' : profile.avatarUrl ? 'Change picture' : 'Upload picture'}
              </button>
              {profile.avatarUrl && (
                <button type="button" disabled={busy} onClick={() => remove.mutate()} style={secondary}>
                  {remove.isPending ? 'Removing…' : 'Remove'}
                </button>
              )}
            </div>
            <span style={{ fontSize: 12.5, color: 'var(--ink-soft)' }}>
              A logo or a photo of you or your products. JPG, PNG or WebP, up to 5MB. It shows as a circle.
            </span>
          </div>
          <input ref={fileInput} type="file" accept={ACCEPT} onChange={pickFile} hidden />
        </div>
      </section>

      <section style={card}>
        <h2 style={h2}>About your store</h2>
        <form onSubmit={e => { e.preventDefault(); setError(undefined); setSaved(false); saveBio.mutate() }}
          style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <textarea value={bio} maxLength={BIO_MAX} rows={5}
            onChange={e => { setBio(e.target.value); setSaved(false) }}
            placeholder="Who you are, what you make, and what makes it yours. A few sentences is plenty."
            aria-describedby="bio-count"
            style={{
              padding: '10px 12px', border: '1.5px solid var(--line)', borderRadius: 'var(--r-sm)',
              fontFamily: 'var(--body)', fontSize: 14.5, lineHeight: 1.55, resize: 'vertical',
              background: 'var(--card)', color: 'var(--ink)',
            }} />
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
            <span id="bio-count" style={{ fontSize: 12.5, color: remaining < 40 ? 'var(--clay)' : 'var(--ink-soft)' }}>
              {remaining} characters left
            </span>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              {saved && <span role="status" style={{ fontSize: 13, color: 'var(--aloe)' }}>Saved</span>}
              <button type="submit" disabled={saveBio.isPending || bio === (profile.bio ?? '')} style={primary}>
                {saveBio.isPending ? 'Saving…' : 'Save bio'}
              </button>
            </div>
          </div>
        </form>
      </section>
    </>
  )
}

const card: React.CSSProperties = {
  padding: 20, borderRadius: 'var(--r)', border: '1px solid var(--line)',
  background: 'var(--card)', marginBottom: 18,
}
const h2: React.CSSProperties = { fontFamily: 'var(--display)', fontWeight: 700, fontSize: 17, marginBottom: 14 }
const primary: React.CSSProperties = {
  padding: '9px 18px', borderRadius: 'var(--r-sm)', border: 'none', minHeight: 40,
  background: 'var(--ink)', color: 'var(--paper)', fontWeight: 700, fontSize: 13.5, cursor: 'pointer',
}
const secondary: React.CSSProperties = {
  ...primary, background: 'var(--card)', color: 'var(--ink)', border: '1.5px solid var(--line)',
}
const errorStyle: React.CSSProperties = {
  background: 'var(--clay-tint)', color: 'var(--clay)', padding: '10px 14px',
  borderRadius: 'var(--r-sm)', fontSize: 13.5, marginBottom: 16,
}

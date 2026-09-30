import React, { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, fieldErrorsFrom, OwnStoreProfile, SocialKey, uploadStoreAvatar } from '../lib/api'
import { SocialIcon } from '../components/layout/SocialIcon'
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

      <SocialLinksSection profile={profile} onSaved={onSaved} />
      <ShareShopSection />
    </>
  )
}

const PLATFORMS: { key: SocialKey; label: string; example: string }[] = [
  { key: 'instagram', label: 'Instagram', example: 'instagram.com/yourstore or @yourstore' },
  { key: 'tiktok', label: 'TikTok', example: 'tiktok.com/@yourstore or @yourstore' },
  { key: 'facebook', label: 'Facebook', example: 'facebook.com/yourstore' },
  { key: 'x', label: 'X', example: 'x.com/yourstore or @yourstore' },
]

/**
 * The store's own social profiles, shown on its shop page
 * (seller-social-links.md). Paste a link or a username; the server keeps only
 * the username and builds the link itself, so what shows on the shop page is
 * always a proper profile link on that platform. Saving is all-or-nothing and
 * reports every field that needs fixing at once.
 */
function SocialLinksSection({ profile, onSaved }: {
  profile: OwnStoreProfile
  onSaved: (p: OwnStoreProfile) => void
}) {
  const initial = () => Object.fromEntries(
    PLATFORMS.map(p => [p.key, profile.socialLinks?.[p.key] ?? ''])) as Record<SocialKey, string>
  const [values, setValues] = useState<Record<SocialKey, string>>(initial)
  const [errors, setErrors] = useState<Partial<Record<SocialKey, string>>>({})
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string>()

  const save = useMutation({
    mutationFn: () => api<OwnStoreProfile>('/api/v1/account/profile/social-links', {
      method: 'PUT', body: values,
    }),
    onSuccess: p => {
      onSaved(p)
      // Show what was actually saved: the canonical link, not what was typed.
      setValues(Object.fromEntries(
        PLATFORMS.map(pl => [pl.key, p.socialLinks?.[pl.key] ?? ''])) as Record<SocialKey, string>)
      setSaved(true)
    },
    onError: e => {
      if (e instanceof ApiError) {
        const fields = fieldErrorsFrom(e)
        setErrors(Object.fromEntries(Object.entries(fields).map(([k, v]) => [k, v[0]])))
        if (Object.keys(fields).length === 0) setError(e.detail || e.title)
      } else {
        setError('Could not save your links. Try again.')
      }
    },
  })

  return (
    <section style={card}>
      <h2 style={h2}>Social links</h2>
      <p style={{ fontSize: 13.5, lineHeight: 1.6, color: 'var(--ink-soft)', marginBottom: 14 }}>
        Links to your store's own profiles, shown as icons on your shop page so buyers can see
        you're a real, active business. These are public. Only link accounts that belong to your
        store.
      </p>
      <form onSubmit={e => { e.preventDefault(); setErrors({}); setError(undefined); setSaved(false); save.mutate() }}
        style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {PLATFORMS.map(p => (
          <label key={p.key} style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 600 }}>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
              <SocialIcon icon={p.key} size={16} /> {p.label}
            </span>
            <input value={values[p.key]} maxLength={300} placeholder={p.example}
              autoCapitalize="none" autoCorrect="off" spellCheck={false}
              aria-invalid={!!errors[p.key]}
              onChange={e => { setValues(v => ({ ...v, [p.key]: e.target.value })); setSaved(false) }}
              style={{
                padding: '9px 12px', borderRadius: 'var(--r-sm)', fontSize: 14,
                border: `1.5px solid ${errors[p.key] ? 'var(--clay)' : 'var(--line)'}`,
                background: 'var(--card)', color: 'var(--ink)', fontWeight: 400,
              }} />
            {errors[p.key] && (
              <span style={{ fontSize: 12.5, fontWeight: 400, color: 'var(--clay)' }}>{errors[p.key]}</span>
            )}
          </label>
        ))}
        {error && <p role="alert" style={errorStyle}>{error}</p>}
        <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 12 }}>
          {saved && <span role="status" style={{ fontSize: 13, color: 'var(--aloe)' }}>Saved</span>}
          <button type="submit" disabled={save.isPending} style={primary}>
            {save.isPending ? 'Saving…' : 'Save links'}
          </button>
        </div>
      </form>
    </section>
  )
}

/**
 * The other half of social links: getting followers to eRestyu. A seller who
 * puts this link in their Instagram or TikTok bio sends their own audience to
 * their shop here, which is the effect the feature is really for.
 */
function ShareShopSection() {
  const { user } = useAuth()
  const [copied, setCopied] = useState(false)
  if (!user) return null
  const shopUrl = `${window.location.origin}/shop/${user.userId}`

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(shopUrl)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2500)
    } catch {
      // Clipboard blocked (older browser, or not a secure context): the link
      // is shown in full beside the button, so it can still be copied by hand.
      setCopied(false)
    }
  }

  return (
    <section style={card}>
      <h2 style={h2}>Your shop link</h2>
      <p style={{ fontSize: 13.5, lineHeight: 1.6, color: 'var(--ink-soft)', marginBottom: 12 }}>
        Put this in your Instagram and TikTok bio, and share it in your stories and WhatsApp
        status, so your followers can buy from your eRestyu shop.
      </p>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        <code style={{
          padding: '9px 12px', borderRadius: 'var(--r-sm)', border: '1px solid var(--line)',
          fontSize: 13.5, wordBreak: 'break-all', flex: '1 1 220px', color: 'var(--ink)',
        }}>
          {shopUrl}
        </code>
        <button type="button" onClick={copy} style={primary}>{copied ? 'Copied' : 'Copy your shop link'}</button>
      </div>
    </section>
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

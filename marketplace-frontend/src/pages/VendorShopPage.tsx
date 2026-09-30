import React from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, Page, ProductResponse, SocialKey, VendorProfile } from '../lib/api'
import { SocialIcon } from '../components/layout/SocialIcon'
import { useAuth } from '../context/AuthContext'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { ProductCard } from '../components/product/ProductCard'
import { vendorHue } from '../lib/vendorHue'

const PAGE_SIZE = 40

/**
 * A vendor's public storefront: one link that shows everything they sell.
 *
 * This exists because vendors here are recruited in person at markets and
 * sell to people they already know. Before this page the only shareable
 * link was a single product, so a trader with five items had five links and
 * no way to say "here is my stall" on a WhatsApp status. That was the first
 * thing a real vendor asked for once she listed.
 *
 * Keyed by vendor id rather than a name slug: business_name has no
 * uniqueness constraint and no slug column, so a slug would need a
 * migration, a backfill, and collision handling on rename. An id in the URL
 * is uglier and correct today; a slug can be added later without breaking
 * this route.
 *
 * The store's name, picture and bio come from its public profile
 * (GET /api/v1/vendors/{id}), so even a stall with no listings yet is named.
 * The first product's vendorName remains a fallback while that loads.
 */
export function VendorShopPage() {
  const { vendorId } = useParams()

  const { data, isLoading } = useQuery<Page<ProductResponse>>({
    queryKey: ['products', 'vendor', vendorId],
    queryFn: () => api(`/api/v1/products?vendorId=${vendorId}&page=0&size=${PAGE_SIZE}&sort=createdAt,desc`),
    enabled: !!vendorId,
  })

  const { data: store } = useQuery<VendorProfile>({
    queryKey: ['vendors', 'profile', vendorId],
    queryFn: () => api(`/api/v1/vendors/${vendorId}`, { auth: false }),
    enabled: !!vendorId,
    retry: false, // 404 means "not a store"; retrying will not change that
  })

  const products = data?.content ?? []
  const vendorName = store?.name ?? products[0]?.vendorName
  const stripe = vendorHue(Number(vendorId) || 1)
  const initial = (vendorName ?? '?').trim().charAt(0).toUpperCase()

  return (
    <>
      <Topbar />
      <main className="page-shell">
        <nav aria-label="Breadcrumb" className="shop-breadcrumb">
          <Link to="/">Home</Link>
          <span aria-hidden>/</span>
          <span>{vendorName ?? 'Stall'}</span>
        </nav>

        <header className="shop-header" style={{ borderTopColor: stripe }}>
          {store?.avatarUrl
            ? <img className="shop-header__avatar" src={store.avatarUrl} alt=""
                style={{ objectFit: 'cover', background: 'var(--card)' }} />
            : <div className="shop-header__avatar" style={{ background: stripe }} aria-hidden>{initial}</div>}
          <div style={{ minWidth: 0 }}>
            <h1>{vendorName ?? (isLoading ? 'Loading…' : 'This stall')}</h1>
            <p>
              {isLoading
                ? 'Fetching listings'
                : products.length === 0
                  ? 'No listings yet'
                  : <>
                      <span className="num">{data?.totalElements ?? products.length}</span>
                      {' '}{(data?.totalElements ?? products.length) === 1 ? 'listing' : 'listings'} on eRestyu
                    </>}
            </p>
            {store?.bio && (
              <p style={{ marginTop: 10, maxWidth: '62ch', fontSize: 15, lineHeight: 1.6, color: 'var(--ink)' }}>
                {store.bio}
              </p>
            )}
            {store && store.socialLinks.length > 0 && (
              <StoreSocialLinks storeId={store.id} storeName={store.name} links={store.socialLinks} />
            )}
          </div>
        </header>

        {isLoading ? (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16 }}>
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} style={{ background: 'var(--line)', borderRadius: 'var(--r)', height: 320, animation: 'pulse 1.5s infinite' }} />
            ))}
          </div>
        ) : products.length === 0 ? (
          // Truthful empty state. A stall with nothing in it is a real
          // situation (a vendor who signed up but has not listed), and
          // saying so is better than a spinner that never resolves.
          <p style={{ color: 'var(--ink-soft)', fontSize: 14, padding: '24px 0' }}>
            This stall has no products listed right now.{' '}
            <Link to="/" style={{ color: 'var(--aloe-deep)', fontWeight: 600 }}>Browse everything on eRestyu</Link>
          </p>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16 }}>
            {products.map(p => <ProductCard key={p.id} product={p} />)}
          </div>
        )}
      </main>
    </>
  )
}

const PLATFORM_LABEL: Record<SocialKey, string> = {
  instagram: 'Instagram', tiktok: 'TikTok', facebook: 'Facebook', x: 'X',
}

/**
 * The store's social profiles (seller-social-links.md). Every URL here was
 * built by the server from a stored username, so it can only be a profile on
 * that platform.
 *
 * rel: noopener and noreferrer so the opened page cannot reach back into this
 * one or see which shop sent it; nofollow and ugc so search engines treat these
 * as seller-supplied links and eRestyu's reputation is not lent to them.
 *
 * Admins also get a way to remove all of a store's links, for a store linking
 * an account that is not theirs (there is no cheap way to prove ownership).
 */
function StoreSocialLinks({ storeId, storeName, links }: {
  storeId: number
  storeName: string
  links: { platform: SocialKey; url: string }[]
}) {
  const { user } = useAuth()
  const qc = useQueryClient()
  const clear = useMutation({
    mutationFn: (reason: string) => api(`/api/v1/admin/vendors/${storeId}/social-links/clear`, {
      method: 'POST', body: { reason },
    }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['vendors', 'profile', String(storeId)] }),
    onError: e => window.alert(e instanceof ApiError ? e.detail || e.title : 'Could not remove the links'),
  })

  const askAndClear = () => {
    const reason = window.prompt(`Remove all of ${storeName}'s social links? Say why (kept in the server log):`)
    if (reason && reason.trim()) clear.mutate(reason.trim())
  }

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
      {links.map(l => (
        <a key={l.platform} href={l.url} target="_blank" rel="nofollow noopener noreferrer ugc"
          aria-label={`${storeName} on ${PLATFORM_LABEL[l.platform]}`} title={PLATFORM_LABEL[l.platform]}
          style={{
            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
            width: 38, height: 38, borderRadius: 'var(--r-sm)',
            border: '1px solid var(--line)', color: 'var(--ink)',
          }}>
          <SocialIcon icon={l.platform} size={18} />
        </a>
      ))}
      {user?.role === 'ADMIN' && (
        <button type="button" onClick={askAndClear} disabled={clear.isPending} style={{
          marginLeft: 4, padding: '6px 10px', fontSize: 12, fontWeight: 600, cursor: 'pointer',
          borderRadius: 'var(--r-sm)', border: '1px solid var(--clay)', color: 'var(--clay)',
          background: 'transparent',
        }}>
          {clear.isPending ? 'Removing…' : 'Remove links (admin)'}
        </button>
      )}
    </div>
  )
}

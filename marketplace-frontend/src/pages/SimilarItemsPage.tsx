import React from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, ProductResponse } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { ProductCard } from '../components/product/ProductCard'

/**
 * The full "Similar items" list, the destination behind See more on the
 * product page.
 *
 * A net-new route. The spec described repurposing an earlier preview route,
 * but no such route was ever built — the router, the git history and the
 * sibling project all came back empty.
 *
 * Uses the FULL ProductCard rather than the rail's compact tile. On a
 * dedicated page these products are the content, not a footnote to
 * something else, so the merchandising the card carries (rating, sold count,
 * stock, add to cart) is wanted here and deliberately absent in the rail.
 *
 * Asks for 24, the API's own ceiling. On a catalogue this size it will
 * usually return far fewer, and the count in the heading tells the truth
 * about that rather than implying a longer list scrolled off.
 */
export function SimilarItemsPage() {
  const { id } = useParams()

  const { data: source } = useQuery<ProductResponse>({
    queryKey: ['product', id],
    queryFn: () => api(`/api/v1/products/${id}`),
    enabled: !!id,
  })

  const { data: items, isLoading } = useQuery<ProductResponse[]>({
    queryKey: ['product', id, 'similar', 24],
    queryFn: () => api(`/api/v1/products/${id}/similar?limit=24`),
    enabled: !!id,
  })

  return (
    <>
      <Topbar />
      {/* No inline padding override. .page-shell.no-catrail already derives
          it from --header-height, which is the only value that counts all
          three header bars. Hand-summing --trustbar-h + --topbar-h misses
          the 44px category bar and tucks the first element under the header
          — the bug this page had until it was looked at. */}
      <main className="page-shell no-catrail">
        <div style={{ maxWidth: 1280, margin: '0 auto' }}>
          <nav aria-label="Breadcrumb" style={{ display: 'flex', gap: 8, fontSize: 13, color: 'var(--ink-soft)', marginBottom: 16, flexWrap: 'wrap' }}>
            <Link to="/" style={{ color: 'var(--ink-soft)' }}>Home</Link>
            <span aria-hidden style={{ color: 'var(--line)' }}>›</span>
            {source
              ? <Link to={`/products/${id}`} style={{ color: 'var(--ink-soft)' }}>{source.name}</Link>
              : <span>Product</span>}
            <span aria-hidden style={{ color: 'var(--line)' }}>›</span>
            <span style={{ color: 'var(--ink)' }}>Similar items</span>
          </nav>

          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 22, marginBottom: 6 }}>
            Similar items
          </h1>
          {source && (
            <p style={{ fontSize: 13, color: 'var(--ink-soft)', marginBottom: 24 }}>
              Related to <Link to={`/products/${id}`} style={{ color: 'var(--trust-blue)' }}>{source.name}</Link>
              {items && items.length > 0 && <> · <span className="num">{items.length}</span> found</>}
            </p>
          )}

          {isLoading && <p style={{ color: 'var(--ink-soft)' }}>Loading…</p>}

          {/* The honest empty state. The backend returns nothing rather than
              padding a shelf with whatever is newest, so this page has to say
              so plainly instead of rendering an empty grid. */}
          {!isLoading && (!items || items.length === 0) && (
            <div style={{ padding: '32px 0', color: 'var(--ink-soft)', fontSize: 14, lineHeight: 1.6 }}>
              <p>Nothing in the catalogue is closely related to this item yet.</p>
              <p style={{ marginTop: 8 }}>
                <Link to="/" style={{ color: 'var(--trust-blue)' }}>Browse everything →</Link>
              </p>
            </div>
          )}

          {items && items.length > 0 && (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16 }}>
              {items.map(p => <ProductCard key={p.id} product={p} />)}
            </div>
          )}
        </div>
      </main>
    </>
  )
}

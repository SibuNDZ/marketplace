import React, { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ProductResponse, ReviewSummary, ApiError } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { StockBadge } from '../components/ui/StockBadge'
import { vendorHue } from '../lib/vendorHue'
import { ErrorSurface } from '../components/ui/ErrorSurface'
import { productImageUrl, productImageSrcSet, IMAGE_WIDTHS, IMAGE_SIZES } from '../lib/productImage'
import { ProductReviews } from '../components/product/ProductReviews'
import { SimilarProducts } from '../components/product/SimilarProducts'

export function ProductDetailPage() {
  const { id } = useParams()
  const qc = useQueryClient()
  const [qty, setQty] = useState(1)
  const [cartError, setCartError] = useState<ApiError>()
  // A signed-out visitor is not an error condition. Kept separate from
  // cartError so a 401 never renders through ErrorSurface, which is built
  // for genuine failures and shows a request id.
  const [needsSignIn, setNeedsSignIn] = useState(false)

  const { data: product, isLoading } = useQuery<ProductResponse>({
    queryKey: ['product', id],
    queryFn: () => api(`/api/v1/products/${id}`),
    enabled: !!id,
  })

  // Deliberate freshness split: catalog cards show the hourly popularity
  // aggregates; the page someone actually reads before buying calls the
  // LIVE summary endpoint, so a review posted a minute ago shows here now
  // and reaches the cards within the hour.
  const { data: summary } = useQuery<ReviewSummary>({
    queryKey: ['review-summary', id],
    queryFn: () => api(`/api/v1/products/${id}/reviews/summary`),
    enabled: !!id,
  })

  // Trailing-24h buyer count. Returns null far more often than not — the
  // backend withholds it below a threshold, because "1 person bought this"
  // is an urgency badge advertising an empty shop. Today it is null for
  // every product in production and no line renders at all; this is wired
  // up so it starts working on its own once real orders arrive.
  const { data: demand } = useQuery<{ recentBuyers: number | null }>({
    queryKey: ['demand', id],
    queryFn: () => api(`/api/v1/products/${id}/demand`),
    enabled: !!id,
    staleTime: 5 * 60 * 1000,
  })

  const addToCart = useMutation({
    mutationFn: () => api('/api/v1/cart/items', {
      method: 'POST', body: { productId: Number(id), quantity: qty },
    }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['cart'] }) },
    onError: (e) => {
      if (!(e instanceof ApiError)) return
      // 401 means "we don't know who you are", not "something broke".
      // Showing an Unauthorized banner with a request id to a shopper who
      // simply is not signed in reads as a site fault and loses the sale.
      if (e.status === 401) { setNeedsSignIn(true); setCartError(undefined) }
      else { setCartError(e); setNeedsSignIn(false) }
    },
  })

  if (isLoading) return <><Topbar /><div className="page-shell no-catrail">Loading…</div></>
  if (!product) return <><Topbar /><div className="page-shell no-catrail">Product not found.</div></>

  const stripe = vendorHue(product.vendorId ?? 1)
  const canAdd = product.stock > 0

  return (
    <>
      <Topbar />
      {/* Vendor stripe band */}
      <div style={{ position: 'fixed', top: 'calc(var(--trustbar-h) + var(--topbar-h))', left: 0, right: 0, height: 5, background: stripe, zIndex: 99 }} />
      <main className="page-shell no-catrail" style={{ paddingTop: 'calc(var(--trustbar-h) + var(--topbar-h) + 5px + 32px)' }}>
        <Link to="/" style={{ fontSize: 13, color: 'var(--ink-soft)', display: 'inline-flex', alignItems: 'center', gap: 4, marginBottom: 24 }}>
          ← Back to catalog
        </Link>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 420px', gap: 48, alignItems: 'start' }}>
          {/* Media */}
          <div style={{ borderRadius: 'var(--r)', aspectRatio: '4/3', overflow: 'hidden', background: '#EAEEED' }}>
            <img
              src={productImageUrl(product, 1280, 960)}
              srcSet={productImageSrcSet(product, IMAGE_WIDTHS.hero)}
              sizes={IMAGE_SIZES.hero}
              alt={product.name}
              // This is the page's LCP element. fetchPriority high stops the
              // browser from queueing it behind scripts, and decoding async
              // keeps a large AVIF off the main thread.
              fetchPriority="high"
              decoding="async"
              style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
            />
          </div>

          {/* Buy panel */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {/* Demand line. Renders ONLY when the count is genuinely above
                the backend's threshold, which today is never — so on the
                live site this element does not exist. It is not padded,
                bucketed or softened into "many people": if it says four,
                four different people bought it in the last 24 hours. */}
            {demand?.recentBuyers != null && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, fontWeight: 600, color: 'var(--flame)' }}>
                <span aria-hidden>🔥</span>
                <span>
                  In demand. <span className="num">{demand.recentBuyers}</span> people bought this in the last 24 hours.
                </span>
              </div>
            )}
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <div style={{ width: 9, height: 9, borderRadius: '50%', background: stripe }} />
              <span style={{ fontSize: 13, color: 'var(--ink-soft)' }}>{product.vendorName}</span>
            </div>
            <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 26, lineHeight: 1.2, color: 'var(--ink)' }}>{product.name}</h1>

            {/* Live review summary — renders only once reviews exist.
                Clicking jumps to the section rather than opening a modal. */}
            {summary && summary.reviewCount > 0 && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                <span style={{ color: 'var(--marigold)', fontSize: 14 }}>
                  {'★'.repeat(Math.round(summary.averageRating))}{'☆'.repeat(5 - Math.round(summary.averageRating))}
                </span>
                <a href="#reviews" style={{ fontSize: 13, color: 'var(--trust-blue)' }}>
                  <span className="num">{summary.averageRating.toFixed(1)}</span> (<span className="num">{summary.reviewCount.toLocaleString()}</span> review{summary.reviewCount !== 1 ? 's' : ''})
                </a>
                {product.soldCount > 0 && (
                  <span className="num" style={{ fontSize: 13, color: 'var(--ink-soft)' }}>· {product.soldCount} sold</span>
                )}
              </div>
            )}

            {product.description && <p style={{ color: 'var(--ink-soft)', lineHeight: 1.6 }}>{product.description}</p>}
            <StockBadge product={product} />
            <div style={{ fontSize: 32, fontWeight: 700 }}>
              <span style={{ fontFamily: 'var(--mono)', fontSize: 16, color: 'var(--ink-soft)', fontWeight: 400 }}>R</span>
              <span className="num">{Number(product.price).toFixed(2)}</span>
            </div>

            {needsSignIn && (
              <div style={{
                background: 'var(--flame-tint)', border: '1px solid var(--flame)',
                borderRadius: 'var(--r-sm)', padding: '12px 14px',
                fontSize: 13, lineHeight: 1.5, marginBottom: 12,
              }}>
                <Link
                  to="/login"
                  state={{ from: `/products/${id}` }}
                  style={{ color: 'var(--flame-deep)', fontWeight: 700 }}
                >
                  Sign in
                </Link>{' '}
                to add this to your cart. We will bring you straight back here.
              </div>
            )}

            {cartError && <ErrorSurface error={cartError} onDismiss={() => setCartError(undefined)} />}

            <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
              <div style={{ display: 'flex', alignItems: 'center', border: '1.5px solid var(--line)', borderRadius: 'var(--r-sm)', overflow: 'hidden' }}>
                <button onClick={() => setQty(q => Math.max(1, q - 1))} style={{ padding: '8px 14px', background: 'none', border: 'none', fontSize: 18 }}>−</button>
                <span className="num" style={{ padding: '0 12px', minWidth: 32, textAlign: 'center' }}>{qty}</span>
                <button onClick={() => setQty(q => Math.min(product.stock, q + 1))} style={{ padding: '8px 14px', background: 'none', border: 'none', fontSize: 18 }}>+</button>
              </div>
              <button disabled={!canAdd || addToCart.isPending} onClick={() => addToCart.mutate()} style={{
                flex: 1, padding: '11px 20px', background: canAdd ? 'var(--flame-gradient)' : 'var(--line)',
                color: canAdd ? '#fff' : 'var(--ink-soft)', border: 'none',
                borderRadius: 'var(--r-sm)', fontWeight: 700, fontSize: 15,
              }}>
                {addToCart.isPending ? 'Adding…' : addToCart.isSuccess ? '✓ Added' : 'Add to cart'}
              </button>
            </div>
            <p style={{ fontSize: 12, color: 'var(--ink-soft)' }}>SKU: <span className="num">{product.sku ?? '-'}</span></p>

            {/* Who you are buying from. Vendors trade under a business name
                (V19), so this is a storefront, not a person — and now a real
                link to that storefront, so a shopper who likes one item can
                see the rest of the stall. */}
            <Link
              to={`/shop/${product.vendorId}`}
              aria-label={`See all products from ${product.vendorName ?? 'this vendor'}`}
              style={{
                marginTop: 4, padding: 14, borderRadius: 'var(--r-sm)',
                border: '1px solid var(--line)', display: 'flex', gap: 12, alignItems: 'center',
              }}>
              <div aria-hidden style={{
                width: 38, height: 38, borderRadius: '50%', background: stripe,
                display: 'grid', placeItems: 'center', color: '#fff', fontWeight: 800, fontSize: 15,
                flexShrink: 0,
              }}>
                {(product.vendorName ?? '?').trim().charAt(0).toUpperCase()}
              </div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 11, color: 'var(--ink-soft)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                  Sold by
                </div>
                <div style={{ fontWeight: 700, fontSize: 14 }}>{product.vendorName ?? 'Vendor'}</div>
                {product.categoryName && (
                  <div style={{ fontSize: 12, color: 'var(--ink-soft)' }}>
                    Listed in {product.categoryName}{product.handmade ? ' · Handmade' : ''}
                  </div>
                )}
              </div>
              <span aria-hidden style={{ marginLeft: 'auto', color: 'var(--ink-soft)', fontSize: 18 }}>›</span>
            </Link>
          </div>
        </div>

        <div id="reviews">
          <ProductReviews productId={String(id)} summary={summary} />
        </div>

        <SimilarProducts productId={String(id)} />
      </main>
    </>
  )
}

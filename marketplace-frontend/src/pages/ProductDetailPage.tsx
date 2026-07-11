import React, { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ProductResponse, ReviewSummary, ApiError } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { StockBadge } from '../components/ui/StockBadge'
import { vendorHue } from '../lib/vendorHue'
import { ErrorSurface } from '../components/ui/ErrorSurface'
import { getMarketplaceSignals } from '../lib/marketplaceSignals'

export function ProductDetailPage() {
  const { id } = useParams()
  const qc = useQueryClient()
  const [qty, setQty] = useState(1)
  const [cartError, setCartError] = useState<ApiError>()

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

  const addToCart = useMutation({
    mutationFn: () => api('/api/v1/cart/items', {
      method: 'POST', body: { productId: Number(id), quantity: qty },
    }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['cart'] }) },
    onError: (e) => { if (e instanceof ApiError) setCartError(e) },
  })

  if (isLoading) return <><Topbar /><div className="page-shell no-catrail">Loading…</div></>
  if (!product) return <><Topbar /><div className="page-shell no-catrail">Product not found.</div></>

  const stripe = vendorHue(product.vendorId ?? 1)
  const canAdd = product.stock > 0
  const signals = getMarketplaceSignals(product.id)

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
              src={`https://picsum.photos/seed/${signals.imageSeed}/800/600`}
              alt={product.name}
              style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
            />
          </div>

          {/* Buy panel */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <div style={{ width: 9, height: 9, borderRadius: '50%', background: stripe }} />
              <span style={{ fontSize: 13, color: 'var(--ink-soft)' }}>{product.vendorName}</span>
            </div>
            <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 26, lineHeight: 1.2, color: 'var(--ink)' }}>{product.name}</h1>

            {/* Live review summary — renders only once reviews exist */}
            {summary && summary.reviewCount > 0 && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{ color: 'var(--marigold)', fontSize: 14 }}>
                  {'★'.repeat(Math.round(summary.averageRating))}{'☆'.repeat(5 - Math.round(summary.averageRating))}
                </span>
                <span style={{ fontSize: 13, color: 'var(--ink-soft)' }}>
                  <span className="num">{summary.averageRating.toFixed(1)}</span> (<span className="num">{summary.reviewCount.toLocaleString()}</span> review{summary.reviewCount !== 1 ? 's' : ''})
                </span>
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
            <p style={{ fontSize: 12, color: 'var(--ink-soft)' }}>SKU: <span className="num">{product.sku ?? '—'}</span></p>
          </div>
        </div>
      </main>
    </>
  )
}

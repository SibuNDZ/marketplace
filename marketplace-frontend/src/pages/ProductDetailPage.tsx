import React, { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ProductResponse, ApiError } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { StockBadge } from '../components/ui/StockBadge'
import { vendorHue } from '../lib/vendorHue'
import { ErrorSurface } from '../components/ui/ErrorSurface'

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

  const addToCart = useMutation({
    mutationFn: () => api('/api/v1/cart/items', {
      method: 'POST', body: { productId: Number(id), quantity: qty },
    }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['cart'] }) },
    onError: (e) => { if (e instanceof ApiError) setCartError(e) },
  })

  if (isLoading) return <><Topbar /><div className="page-shell">Loading…</div></>
  if (!product) return <><Topbar /><div className="page-shell">Product not found.</div></>

  const stripe = vendorHue(product.vendorId ?? 1)
  const canAdd = product.stock > 0

  return (
    <>
      <Topbar />
      {/* Vendor stripe band */}
      <div style={{ position: 'fixed', top: 'var(--topbar-h)', left: 0, right: 0, height: 5, background: stripe, zIndex: 99 }} />
      <main className="page-shell" style={{ paddingTop: 'calc(var(--topbar-h) + 5px + 32px)' }}>
        <Link to="/" style={{ fontSize: 13, color: 'var(--ink-soft)', display: 'inline-flex', alignItems: 'center', gap: 4, marginBottom: 24 }}>
          ← Back to catalog
        </Link>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 420px', gap: 48, alignItems: 'start' }}>
          {/* Media */}
          <div style={{ background: '#EAEEED', borderRadius: 'var(--r)', aspectRatio: '4/3', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <svg width={80} height={80} viewBox="0 0 24 24" fill="none" stroke="#B0BBB5" strokeWidth={1.2}>
              <path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z"/><line x1="3" y1="6" x2="21" y2="6"/><path d="M16 10a4 4 0 01-8 0"/>
            </svg>
          </div>

          {/* Buy panel */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <div style={{ width: 9, height: 9, borderRadius: '50%', background: stripe }} />
              <span style={{ fontSize: 13, color: 'var(--ink-soft)' }}>{product.vendorName}</span>
            </div>
            <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 26, lineHeight: 1.2, color: 'var(--ink)' }}>{product.name}</h1>
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
                flex: 1, padding: '11px 20px', background: canAdd ? 'var(--ink)' : 'var(--line)',
                color: canAdd ? '#fff' : 'var(--ink-soft)', border: 'none',
                borderRadius: 'var(--r-sm)', fontWeight: 600, fontSize: 15,
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

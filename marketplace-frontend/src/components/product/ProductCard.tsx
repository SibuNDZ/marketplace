import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ProductResponse } from '../../lib/api'
import { StockBadge } from '../ui/StockBadge'
import { getImageSeed } from '../../lib/marketplaceSignals'

interface Props {
  product: ProductResponse
}

// Real aggregates only: no reviews → no stars. The empty state IS the
// honest state — resist any urge to fill the gap.
function Stars({ rating, reviewCount }: { rating: number; reviewCount: number }) {
  const full = Math.round(rating)
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
      <span style={{ color: 'var(--marigold)', fontSize: 12 }}>
        {'★'.repeat(full)}{'☆'.repeat(5 - full)}
      </span>
      <span style={{ fontSize: 11, color: 'var(--ink-soft)' }}>
        <span className="num">{rating.toFixed(1)}</span> (<span className="num">{reviewCount.toLocaleString()}</span>)
      </span>
    </div>
  )
}

function formatSold(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(1)}K+ sold` : `${n} sold`
}

const NEW_IN_DAYS = 14

/** Honest recency: young product, no sales yet — real createdAt, real soldCount. */
function isNewIn(p: ProductResponse): boolean {
  const ageMs = Date.now() - new Date(p.createdAt).getTime()
  return ageMs < NEW_IN_DAYS * 86400_000 && p.soldCount === 0
}

export function ProductCard({ product }: Props) {
  const qc = useQueryClient()
  const [added, setAdded] = useState(false)
  const imageSeed = getImageSeed(product.id)

  const addToCart = useMutation({
    mutationFn: () => api('/api/v1/cart/items', {
      method: 'POST',
      body: { productId: product.id, quantity: 1 },
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['cart'] })
      setAdded(true)
      setTimeout(() => setAdded(false), 1500)
    },
  })

  const canAdd = product.stock > 0 && !product.deletedAt
  const rating = Number(product.avgRating)

  return (
    <div style={{
      background: 'var(--card)',
      borderRadius: 'var(--r)',
      boxShadow: 'var(--shadow)',
      overflow: 'hidden',
      display: 'flex',
      flexDirection: 'column',
      transition: 'box-shadow 0.2s, transform 0.2s',
    }}
      onMouseEnter={e => { (e.currentTarget as HTMLDivElement).style.boxShadow = 'var(--shadow-lift)'; (e.currentTarget as HTMLDivElement).style.transform = 'translateY(-2px)' }}
      onMouseLeave={e => { (e.currentTarget as HTMLDivElement).style.boxShadow = 'var(--shadow)'; (e.currentTarget as HTMLDivElement).style.transform = '' }}
    >
      {/* Product image */}
      <Link to={`/products/${product.id}`} style={{ position: 'relative', display: 'block', height: 180, flexShrink: 0, background: '#EAEEED' }}>
        <img
          src={product.imageUrl ?? `https://picsum.photos/seed/${imageSeed}/400/300`}
          alt={product.name}
          loading="lazy"
          style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
        />
        {isNewIn(product) && (
          <span style={{
            position: 'absolute', top: 8, left: 8,
            background: 'var(--aloe)', color: '#fff',
            fontSize: 11, fontWeight: 700, padding: '3px 8px', borderRadius: 'var(--r-sm)',
          }}>
            New in
          </span>
        )}
      </Link>

      <div style={{ padding: '12px 14px 14px', display: 'flex', flexDirection: 'column', gap: 5, flex: 1 }}>
        {/* Vendor */}
        <span style={{ fontSize: 11, color: 'var(--ink-soft)' }}>{product.vendorName ?? 'Vendor'}</span>

        {/* Name */}
        <Link to={`/products/${product.id}`} style={{ fontWeight: 600, fontSize: 14, color: 'var(--ink)', lineHeight: 1.3, minHeight: 36 }}>
          {product.name}
        </Link>

        {product.reviewCount > 0 && <Stars rating={rating} reviewCount={product.reviewCount} />}

        {product.soldCount > 0 && (
          <span className="num" style={{ fontSize: 11, color: 'var(--ink-soft)' }}>
            {formatSold(product.soldCount)}
          </span>
        )}

        {/* Urgency — real stock only */}
        <StockBadge product={product} />

        {/* Price + Add */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 'auto', paddingTop: 6 }}>
          <span style={{ fontSize: 18, fontWeight: 800, color: 'var(--ink)' }}>
            <span style={{ fontFamily: 'var(--mono)', fontSize: 12, fontWeight: 400, color: 'var(--ink-soft)' }}>R</span>
            <span className="num">{Number(product.price).toFixed(2)}</span>
          </span>
          <button
            disabled={!canAdd || addToCart.isPending}
            onClick={() => addToCart.mutate()}
            style={{
              padding: '7px 14px',
              background: canAdd ? 'var(--flame-gradient)' : 'var(--line)',
              color: canAdd ? '#fff' : 'var(--ink-soft)',
              border: 'none',
              borderRadius: 'var(--r-pill)',
              fontSize: 12, fontWeight: 700,
              cursor: canAdd ? 'pointer' : 'not-allowed',
            }}
          >
            {added ? '✓ Added' : 'Add to cart'}
          </button>
        </div>
      </div>
    </div>
  )
}

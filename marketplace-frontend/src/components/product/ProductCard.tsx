import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ProductResponse } from '../../lib/api'
import { vendorHue } from '../../lib/vendorHue'
import { StockBadge } from '../ui/StockBadge'

interface Props {
  product: ProductResponse
}

function Stars({ rating }: { rating: number }) {
  return (
    <span style={{ color: 'var(--marigold)', fontSize: 13 }}>
      {'★'.repeat(Math.round(rating))}{'☆'.repeat(5 - Math.round(rating))}
    </span>
  )
}

export function ProductCard({ product }: Props) {
  const qc = useQueryClient()
  const [added, setAdded] = useState(false)
  const stripeColor = vendorHue(product.vendorId ?? 1)

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
      {/* Vendor stripe */}
      <div style={{ height: 5, background: stripeColor, flexShrink: 0 }} />

      {/* Product image */}
      <Link to={`/products/${product.id}`} style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: '#EAEEED', height: 180, flexShrink: 0,
      }}>
        <svg width={48} height={48} viewBox="0 0 24 24" fill="none" stroke="#B0BBB5" strokeWidth={1.5}>
          <path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z"/><line x1="3" y1="6" x2="21" y2="6"/>
          <path d="M16 10a4 4 0 01-8 0"/>
        </svg>
      </Link>

      <div style={{ padding: '14px 16px 16px', display: 'flex', flexDirection: 'column', gap: 6, flex: 1 }}>
        {/* Vendor */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ width: 8, height: 8, borderRadius: '50%', background: stripeColor, flexShrink: 0 }} />
          <span style={{ fontSize: 12, color: 'var(--ink-soft)' }}>{product.vendorName ?? 'Vendor'}</span>
        </div>

        {/* Name */}
        <Link to={`/products/${product.id}`} style={{ fontWeight: 600, fontSize: 15, color: 'var(--ink)', lineHeight: 1.3 }}>
          {product.name}
        </Link>

        {/* Stars placeholder — would come from popularity endpoint */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <Stars rating={4} />
        </div>

        <StockBadge product={product} />

        {/* Price + Add */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 'auto', paddingTop: 8 }}>
          <span style={{ fontSize: 18, fontWeight: 700 }}>
            <span style={{ fontFamily: 'var(--mono)', fontSize: 13, color: 'var(--ink-soft)', fontWeight: 400 }}>R</span>
            <span className="num" style={{ fontSize: 20 }}>{Number(product.price).toFixed(2)}</span>
          </span>
          <button
            disabled={!canAdd || addToCart.isPending}
            onClick={() => addToCart.mutate()}
            style={{
              padding: '7px 14px',
              background: canAdd ? 'var(--ink)' : 'var(--line)',
              color: canAdd ? '#fff' : 'var(--ink-soft)',
              border: 'none',
              borderRadius: 'var(--r-pill)',
              fontSize: 13, fontWeight: 600,
              cursor: canAdd ? 'pointer' : 'not-allowed',
            }}
          >
            {added ? 'Added' : 'Add to cart'}
          </button>
        </div>
      </div>
    </div>
  )
}

import React, { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError, api, ProductResponse } from '../../lib/api'
import { StockBadge, OutOfStockOverlay } from '../ui/StockBadge'
import { productImageUrl, productImageSrcSet, IMAGE_WIDTHS, IMAGE_SIZES } from '../../lib/productImage'
import { RatingLine } from './RatingLine'
import { PriceBlock } from './PriceBlock'

interface Props {
  product: ProductResponse
}

// Star rendering moved to RatingLine, which every card surface shares so
// the rating slot looks identical on the grid, the carousel and the panel.
// Real aggregates only: no reviews means "New", never an invented score.

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
  const navigate = useNavigate()
  const location = useLocation()
  const [added, setAdded] = useState(false)

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
    onError: (e) => {
      // There was NO error handler here, so a signed-out shopper clicking
      // Add to cart got nothing at all — the click looked broken. A grid
      // card has no room for an inline banner, so send them to sign-in and
      // bring them back to the exact catalogue view they were browsing,
      // filters and all.
      if (e instanceof ApiError && e.status === 401) {
        navigate('/login', { state: { from: location.pathname + location.search } })
      }
    },
  })

  // A product with options cannot be added from a grid: the card has no
  // room to choose one, and the backend rightly refuses a line that does not
  // name an option. The button becomes a link to the page where the choice
  // lives, rather than a button that 400s.
  const needsOptions = (product.variants?.length ?? 0) > 0
  const canAdd = product.stock > 0 && !product.deletedAt && !needsOptions
  const isOutOfStock = product.stock === 0 && !product.deletedAt
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
      {/* Product image. Same tab: Back restores the catalogue's offset
          (ScrollToTop leaves POP alone), so a new tab is not needed to keep
          the shopper's place, filters or scroll. */}
      <Link
        to={`/products/${product.id}`}
        style={{ position: 'relative', display: 'block', height: 180, flexShrink: 0, background: '#EAEEED' }}
      >
        <img
          src={productImageUrl(product, 640, 480)}
          srcSet={productImageSrcSet(product, IMAGE_WIDTHS.card)}
          sizes={IMAGE_SIZES.card}
          alt={product.name}
          width={640}
          height={480}
          loading="lazy"
          decoding="async"
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
        {isOutOfStock && <OutOfStockOverlay />}
      </Link>

      <div style={{ padding: '12px 14px 14px', display: 'flex', flexDirection: 'column', gap: 5, flex: 1 }}>
        {/* Vendor */}
        <span style={{ fontSize: 11, color: 'var(--ink-soft)' }}>{product.vendorName ?? 'Vendor'}</span>

        {/* Name */}
        <Link
          to={`/products/${product.id}`}
          style={{ fontWeight: 600, fontSize: 14, color: 'var(--ink)', lineHeight: 1.3, minHeight: 36 }}
        >
          {product.name}
        </Link>

        {/* Always present, never blank: a rated product shows its score, an
            unrated one says "New" rather than leaving a gap that reads as a
            missing element. */}
        <RatingLine product={product} />

        {product.soldCount > 0 && (
          <span className="num" style={{ fontSize: 11, color: 'var(--ink-soft)' }}>
            {formatSold(product.soldCount)}
          </span>
        )}

        {/* Urgency — real stock only. The true out-of-stock case moved to
            the image overlay above; this inline row still carries "No
            longer available" (soft-deleted) and the low/in-stock states. */}
        {!isOutOfStock && <StockBadge product={product} />}

        {/* Price + Add */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 'auto', paddingTop: 6 }}>
          <PriceBlock price={product.price} originalPrice={product.originalPrice} size={18} />
          {needsOptions ? (
            <Link
              to={`/products/${product.id}`}
              style={{
                padding: '7px 14px', background: 'var(--card)', color: 'var(--ink)',
                border: '1.5px solid var(--line)', borderRadius: 'var(--r-pill)',
                fontSize: 12, fontWeight: 700, whiteSpace: 'nowrap',
              }}
            >
              Choose options
            </Link>
          ) : (
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
          )}
          {/* TODO(notify-me): "Notify me when available" for isOutOfStock
              products belongs here, gated behind the transactional-email
              slice (no email infra to fire the notification yet). Deferred,
              not rejected — see UI polish pass spec. */}
        </div>
      </div>
    </div>
  )
}

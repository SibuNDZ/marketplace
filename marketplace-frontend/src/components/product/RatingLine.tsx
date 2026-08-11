import React from 'react'
import { ProductResponse } from '../../lib/api'

interface Props {
  product: Pick<ProductResponse, 'avgRating' | 'reviewCount'>
  /** compact drops the star glyphs, for the narrow right-panel rows. */
  compact?: boolean
}

/**
 * The rating slot every product card carries, so a shopper never has to open
 * a listing to find out whether anyone has rated it.
 *
 * Unreviewed products show "New" rather than an empty or zeroed star row.
 * That is the honest reading of reviewCount === 0: it means nobody has
 * reviewed this yet, NOT that it scored zero, and a greyed-out five-star
 * outline reads as the latter to most people.
 *
 * Ratings come from the product_popularity read model (hourly) via
 * ProductResponse. Nothing here invents a score.
 */
export function RatingLine({ product, compact = false }: Props) {
  const rating = Number(product.avgRating)

  if (product.reviewCount === 0) {
    return (
      <span style={{
        alignSelf: 'flex-start',
        padding: compact ? '1px 6px' : '2px 8px',
        borderRadius: 'var(--r-pill)',
        background: 'var(--aloe-tint)',
        color: 'var(--aloe-deep)',
        fontSize: compact ? 10 : 11,
        fontWeight: 700,
      }}>
        New
      </span>
    )
  }

  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: compact ? 11 : 12 }}>
      <span style={{ color: 'var(--marigold)' }} aria-hidden>★</span>
      <span className="num" style={{ fontWeight: 700 }}>{rating.toFixed(1)}</span>
      <span className="num" style={{ color: 'var(--ink-soft)' }}>
        ({product.reviewCount.toLocaleString()})
      </span>
    </span>
  )
}

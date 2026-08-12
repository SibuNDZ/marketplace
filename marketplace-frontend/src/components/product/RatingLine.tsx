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
 * Unreviewed products show a muted "No reviews yet" rather than a zeroed
 * star row. That is the honest reading of reviewCount === 0: it means nobody
 * has reviewed this yet, NOT that it scored zero, and a greyed-out five-star
 * outline reads as the latter to most people.
 *
 * Ratings come from the product_popularity read model (hourly) via
 * ProductResponse. Nothing here invents a score.
 */
export function RatingLine({ product, compact = false }: Props) {
  const rating = Number(product.avgRating)

  if (product.reviewCount === 0) {
    // Plain muted text, NOT a badge. This started life as a green "New" pill
    // and that was wrong twice over: it said "new" when it meant "unrated",
    // and because nothing in the catalogue has a review yet it appeared on
    // every single card — a marker on 100% of items carries no information,
    // and it competed with the genuine age-based "New in" badge sitting
    // directly above it on the same card.
    //
    // Still not a zeroed star row: an empty five-star outline reads as
    // "scored zero" rather than "nobody has rated this yet".
    return (
      <span style={{
        alignSelf: 'flex-start',
        color: 'var(--ink-soft)',
        fontSize: compact ? 10 : 11,
      }}>
        No reviews yet
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

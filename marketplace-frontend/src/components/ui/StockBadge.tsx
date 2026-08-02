import React from 'react'

type Stock = { stock: number; deletedAt?: string | null }

interface Props { product: Stock; className?: string }

export function StockBadge({ product, className = '' }: Props) {
  if (product.deletedAt) {
    return <span className={`stock gone ${className}`}>No longer available</span>
  }
  if (product.stock === 0) {
    return <span className={`stock gone ${className}`}>Out of stock</span>
  }
  if (product.stock <= 5) {
    return (
      <span className={`stock low ${className}`}>
        Only <span className="num">{product.stock}</span> left
      </span>
    )
  }
  return <span className={`stock ok ${className}`}>In stock</span>
}

/**
 * Card-image overlay for the true out-of-stock case (stock === 0, product
 * still listed). Ink on a translucent paper chip, deliberately NOT red —
 * out of stock is a fact, not an alarm. Positioned by the parent
 * (image wrapper needs position: relative); top-right so it never collides
 * with the "New in" badge, which owns top-left.
 *
 * "No longer available" (soft-deleted) is a different, rarer lifecycle
 * state and keeps its existing inline-text treatment — out of scope here.
 */
export function OutOfStockOverlay() {
  return (
    <span style={{
      position: 'absolute', top: 8, right: 8,
      background: 'rgba(245, 247, 243, 0.92)', // --paper, translucent
      color: 'var(--ink)',
      fontSize: 11, fontWeight: 700, letterSpacing: '0.02em',
      padding: '4px 10px',
      borderRadius: 'var(--r-sm)',
    }}>
      Out of stock
    </span>
  )
}

import React from 'react'
import { Link } from 'react-router-dom'
import { ProductResponse } from '../../lib/api'
import { CompactProductCard } from './CompactProductCard'

interface Props {
  title: string
  products: ProductResponse[]
  cardWidth: number
  /** Same-tab destination for "See more". Omitted means no link. */
  seeMoreTo?: string
  seeMoreLabel?: string
}

/**
 * Same outer size as a loaded ProductRail of compact tiles, used while the
 * similar-items query is in flight. The product page puts that rail ABOVE
 * the gallery; without a reserved block the gallery jumps down the moment
 * the shelf arrives.
 */
export function ProductRailSkeleton({ cardWidth, count = 6 }: { cardWidth: number; count?: number }) {
  return (
    <section style={{ marginTop: 32 }} aria-hidden>
      <div className="skeleton" style={{ height: 16, width: 140, borderRadius: 4, marginBottom: 12 }} />
      <div style={{ display: 'flex', gap: 12, overflow: 'hidden', padding: '2px 2px 8px' }}>
        {Array.from({ length: count }).map((_, i) => (
          <div key={i} style={{ flex: `0 0 ${cardWidth}px`, width: cardWidth, display: 'flex', flexDirection: 'column', gap: 6 }}>
            <div className="skeleton" style={{ width: '100%', aspectRatio: '1/1', borderRadius: 'var(--r-sm)' }} />
            <div className="skeleton" style={{ height: 16, borderRadius: 4 }} />
            <div className="skeleton" style={{ height: 16, width: '45%', borderRadius: 4 }} />
          </div>
        ))}
      </div>
    </section>
  )
}

/**
 * A horizontal, scroll-snapping row of compact product tiles.
 *
 * Renders NOTHING when there is nothing to show. Every rail on this page
 * follows that rule, so a thin catalogue produces a short page rather than a
 * page of empty headings — the same reason Top Selling hides itself and
 * cards say "No reviews yet".
 *
 * Horizontal rather than a wrapping grid: a rail is secondary content, and a
 * grid of twelve suggestions under a product page buries the reviews. A row
 * that scrolls keeps the page's vertical rhythm no matter how many items
 * come back.
 *
 * The "See more" link navigates in the SAME tab, unlike the cards. Clicking
 * a specific product is a side-quest worth preserving this page for;
 * choosing to browse the full list is a decision to leave.
 */
export function ProductRail({ title, products, cardWidth, seeMoreTo, seeMoreLabel = 'See more' }: Props) {
  if (products.length === 0) return null

  return (
    <section style={{ marginTop: 32 }} aria-label={title}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 12 }}>
        <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 16 }}>{title}</h2>
        <div style={{ flex: 1, height: 1, background: 'var(--line)' }} />
        {seeMoreTo && (
          <Link to={seeMoreTo} style={{ fontSize: 13, color: 'var(--trust-blue)', whiteSpace: 'nowrap' }}>
            {seeMoreLabel} →
          </Link>
        )}
      </div>
      <div
        style={{
          display: 'flex', gap: 12, overflowX: 'auto', scrollSnapType: 'x mandatory',
          // Room for the focus ring on keyboard tabbing, which would
          // otherwise be clipped by overflow on the first and last card.
          padding: '2px 2px 8px',
          scrollbarWidth: 'thin',
        }}
      >
        {products.map(p => <CompactProductCard key={p.id} product={p} width={cardWidth} />)}
      </div>
    </section>
  )
}

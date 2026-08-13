import React from 'react'

interface Props {
  price: string
  originalPrice?: string | null
  /** Font size of the main price in px. The rest scales off it. */
  size?: number
}

/**
 * Price, with a strikethrough and a saving when the vendor set a genuine
 * markdown.
 *
 * ONE component for every surface, because a discount rendered two different
 * ways in two places is how a marketplace ends up with a card claiming 40%
 * off next to a product page claiming 35%.
 *
 * The percentage is computed here rather than sent by the API, deliberately:
 * it is a pure function of two numbers that are already on the wire, and a
 * stored copy is a third thing that can drift out of step with them.
 *
 * Rounds DOWN. A 39.6% saving shown as "40% off" overstates the discount by
 * a hair, and on a legal representation the direction of the rounding error
 * should always favour the shopper.
 */
export function PriceBlock({ price, originalPrice, size = 18 }: Props) {
  const now = Number(price)
  const was = originalPrice == null ? null : Number(originalPrice)

  // Guard the render as well as the API. The backend rejects a "was" price
  // at or below the selling price, but this component is also handed data
  // from caches and older responses, and a negative saving must never paint.
  const onSale = was != null && Number.isFinite(was) && was > now
  const percentOff = onSale ? Math.floor(((was - now) / was) * 100) : 0

  return (
    <span style={{ display: 'inline-flex', alignItems: 'baseline', gap: 8, flexWrap: 'wrap' }}>
      <span style={{ fontSize: size, fontWeight: 800, color: onSale ? 'var(--flame-deep)' : 'var(--ink)' }}>
        <span style={{ fontFamily: 'var(--mono)', fontSize: size * 0.66, fontWeight: 400, color: 'var(--ink-soft)' }}>R</span>
        <span className="num">{now.toFixed(2)}</span>
      </span>

      {onSale && (
        <>
          <span
            style={{
              fontSize: size * 0.72, color: 'var(--ink-soft)', textDecoration: 'line-through',
            }}
          >
            <span style={{ fontFamily: 'var(--mono)', fontSize: size * 0.55 }}>R</span>
            <span className="num">{was.toFixed(2)}</span>
          </span>
          {/* Only shown when it rounds to at least 1%. "0% off" next to a
              struck-through price reads as broken, and the backend permits
              a markdown small enough to floor to zero. */}
          {percentOff >= 1 && (
            <span style={{
              fontSize: size * 0.66, fontWeight: 700, color: 'var(--aloe-deep)',
              background: 'var(--aloe-tint)', padding: '2px 7px', borderRadius: 'var(--r-pill)',
            }}>
              <span className="num">{percentOff}</span>% off
            </span>
          )}
        </>
      )}
    </span>
  )
}

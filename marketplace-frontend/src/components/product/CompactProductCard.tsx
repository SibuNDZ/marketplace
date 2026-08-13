import React from 'react'
import { ProductResponse } from '../../lib/api'
import { productImageUrl, productImageSrcSet, IMAGE_WIDTHS, IMAGE_SIZES } from '../../lib/productImage'

interface Props {
  product: ProductResponse
  /** Rendered card width. See RAIL_CARD_WIDTH for the sanctioned sizes. */
  width: number
}

/**
 * The small tile used by every recommendation rail.
 *
 * Deliberately NOT ProductCard. That card is a full merchandising unit —
 * vendor line, rating, sold count, stock badge, add-to-cart button — and at
 * 220px+ it dominates whatever it sits under. A rail below the thing you are
 * already reading needs to stay subordinate to it, so this is image, one
 * line of title, and a price. Nothing else.
 *
 * Opens in a NEW TAB. The whole point of a rail is that you are part-way
 * through evaluating the product you are on; sending that page away to show
 * a maybe is the wrong trade. rel="noopener" is mandatory with target blank:
 * without it the opened page gets a window.opener handle back to this one.
 */
export function CompactProductCard({ product, width }: Props) {
  return (
    <a
      href={`/products/${product.id}`}
      target="_blank"
      rel="noopener"
      style={{
        flex: `0 0 ${width}px`,
        width,
        scrollSnapAlign: 'start',
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
      }}
    >
      <div style={{
        width: '100%', aspectRatio: '1/1', borderRadius: 'var(--r-sm)',
        overflow: 'hidden', background: '#EAEEED',
      }}>
        <img
          src={productImageUrl(product, width * 2, width * 2)}
          srcSet={productImageSrcSet(product, IMAGE_WIDTHS.card)}
          // The rail is a fixed pixel width at every breakpoint, so `sizes`
          // can state it exactly rather than guessing from the viewport.
          sizes={`${width}px`}
          alt={product.name}
          loading="lazy"
          decoding="async"
          style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
        />
      </div>
      <span style={{
        fontSize: 12, lineHeight: 1.35, color: 'var(--ink)',
        // One line, clipped. A rail's rhythm depends on every card being the
        // same height, and a two-line title on one card staggers the row.
        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
      }}>
        {product.name}
      </span>
      <span style={{ fontSize: 13, fontWeight: 800, color: 'var(--ink)' }}>
        <span style={{ fontFamily: 'var(--mono)', fontSize: 11, fontWeight: 400, color: 'var(--ink-soft)' }}>R</span>
        <span className="num">{Number(product.price).toFixed(2)}</span>
      </span>
    </a>
  )
}

/**
 * The only card widths any rail may use. Capped at 240 on purpose: past that
 * a "you might also like" tile competes with the product being viewed, which
 * is the failure the restructure set out to fix.
 */
export const RAIL_CARD_WIDTH = {
  /** Similar items, More from this shop. */
  tight: 160,
  /** You may also like — the broadest, least targeted rail, so slightly larger. */
  wide: 220,
} as const

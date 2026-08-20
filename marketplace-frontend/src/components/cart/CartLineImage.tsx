import React from 'react'
import { CartLine } from '../../lib/api'
import { imageUrlAt, imageSrcSetAt } from '../../lib/productImage'

interface Props {
  line: CartLine
  /** Rendered square size in CSS pixels. */
  size: number
}

/**
 * The thumbnail on a cart row.
 *
 * Shared by the right panel and the drawer because they had the same bug in
 * two places: both rendered `picsum.photos/seed/mk-{productId}`, a stock
 * photo keyed on the product id. Every cart row showed a confident,
 * completely unrelated image, which is worse than showing none — a shopper
 * checking what they are about to pay for was being shown someone else's
 * scenery.
 *
 * When a vendor has uploaded no photo this renders the empty well and stops.
 * An unfilled square reads as "no picture"; a random one reads as "wrong
 * order".
 *
 * Requests 2x for the retina case. These are 44-56px, so even doubled they
 * are among the cheapest transformations on the site.
 */
export function CartLineImage({ line, size }: Props) {
  const src = imageUrlAt(line.imageUrl, size * 2)

  if (!src) {
    return (
      <span
        aria-hidden
        style={{
          width: size, height: size, flexShrink: 0,
          borderRadius: 'var(--r-sm)', background: '#14141c', display: 'block',
        }}
      />
    )
  }

  return (
    <img
      src={src}
      srcSet={imageSrcSetAt(line.imageUrl, [size, size * 2])}
      sizes={`${size}px`}
      alt=""
      width={size}
      height={size}
      loading="lazy"
      decoding="async"
      style={{ borderRadius: 'var(--r-sm)', objectFit: 'cover', flexShrink: 0 }}
    />
  )
}

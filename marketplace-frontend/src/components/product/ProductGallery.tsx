import React, { useEffect, useRef, useState } from 'react'
import { ProductResponse } from '../../lib/api'
import {
  productImageUrl, productImageSrcSet, imageUrlAt, imageSrcSetAt,
  IMAGE_WIDTHS, IMAGE_SIZES,
} from '../../lib/productImage'

/**
 * The product page's photo gallery: a vertical thumbnail rail beside the
 * main image.
 *
 * The rail renders ONLY when there is more than one photo. A rail containing
 * a single thumbnail is a control that does nothing, and it takes horizontal
 * space away from the image it is supposedly helping you inspect. Most of
 * this catalogue still has exactly one photo per product, so most product
 * pages look exactly as they did.
 *
 * Selection lives here rather than in the page: nothing outside the gallery
 * needs to know which photo is showing.
 */
export function ProductGallery({ product }: { product: ProductResponse }) {
  const images = product.images ?? []
  const [selected, setSelected] = useState(0)
  const tablistRef = useRef<HTMLDivElement>(null)

  // A different product means a different gallery. Without this, navigating
  // between two product pages that share a route keeps the old index, so
  // landing on a one-photo product while index 2 is selected shows nothing.
  useEffect(() => { setSelected(0) }, [product.id])

  const active = images[selected]
  const hasRail = images.length > 1

  // No images at all: fall through to productImageUrl, which owns the
  // placeholder chain for products a vendor has not photographed.
  const mainSrc = active
    ? imageUrlAt(active.url, 1280)
    : productImageUrl(product, 1280, 960)
  const mainSrcSet = active
    ? imageSrcSetAt(active.url, IMAGE_WIDTHS.hero)
    : productImageSrcSet(product, IMAGE_WIDTHS.hero)

  const mainId = 'pdp-gallery-main'

  return (
    <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
      {hasRail && (
        <div
          role="tablist"
          aria-label="Product photos"
          aria-controls={mainId}
          ref={tablistRef}
          onKeyDown={event => {
            if (!hasRail) return
            const last = images.length - 1
            let next = selected
            if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
              event.preventDefault()
              next = (selected + 1) % images.length
            } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
              event.preventDefault()
              next = (selected - 1 + images.length) % images.length
            } else if (event.key === 'Home') {
              event.preventDefault()
              next = 0
            } else if (event.key === 'End') {
              event.preventDefault()
              next = last
            } else {
              return
            }
            setSelected(next)
            requestAnimationFrame(() => {
              tablistRef.current?.querySelectorAll<HTMLElement>('[role="tab"]')[next]?.focus()
            })
          }}
          style={{
            display: 'flex', flexDirection: 'column', gap: 8,
            maxHeight: 600, overflowY: 'auto', flexShrink: 0,
          }}
        >
          {images.map((img, i) => (
            <button
              key={img.id}
              role="tab"
              aria-selected={i === selected}
              aria-controls={mainId}
              aria-label={`Photo ${i + 1} of ${images.length}`}
              tabIndex={i === selected ? 0 : -1}
              onClick={() => setSelected(i)}
              onFocus={() => setSelected(i)}
              style={{
                width: 60, height: 60, padding: 0, flexShrink: 0,
                borderRadius: 'var(--r-sm)', overflow: 'hidden', cursor: 'pointer',
                border: i === selected ? '2px solid var(--ink)' : '1px solid var(--line)',
                background: '#EAEEED',
              }}
            >
              <img
                src={imageUrlAt(img.url, 120)}
                alt=""
                width={60}
                height={60}
                loading="lazy"
                decoding="async"
                style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
              />
            </button>
          ))}
        </div>
      )}

      <div style={{
        flex: 1, minWidth: 0, borderRadius: 'var(--r)', aspectRatio: '4/3',
        maxHeight: 600, overflow: 'hidden', background: '#EAEEED',
      }}>
        <img
          id={mainId}
          src={mainSrc}
          srcSet={mainSrcSet}
          sizes={IMAGE_SIZES.hero}
          alt={product.name}
          fetchPriority="high"
          decoding="async"
          style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
        />
      </div>
    </div>
  )
}

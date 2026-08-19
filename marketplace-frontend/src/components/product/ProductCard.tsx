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

export const ProductCard = React.memo(function ProductCard({ product }: Props) {
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
  const imgSrc = productImageUrl(product, 640, 480)

  return (
    <div className="product-card">
      {/* Product image. Same tab: Back restores the catalogue's offset
          (ScrollToTop leaves POP alone), so a new tab is not needed to keep
          the shopper's place, filters or scroll. */}
      <Link
        to={`/products/${product.id}`}
        className="product-card__media"
      >
        {imgSrc ? (
          <img
            src={imgSrc}
            srcSet={productImageSrcSet(product, IMAGE_WIDTHS.card)}
            sizes={IMAGE_SIZES.card}
            alt={product.name}
            width={640}
            height={480}
            loading="lazy"
            decoding="async"
          />
        ) : (
          <span className="image-well" aria-hidden />
        )}
        {isNewIn(product) && <span className="product-card__new">New in</span>}
        {isOutOfStock && <OutOfStockOverlay />}
      </Link>

      <div className="product-card__body">
        <span className="product-card__vendor">{product.vendorName ?? 'Vendor'}</span>

        <Link
          to={`/products/${product.id}`}
          className="product-card__name"
        >
          {product.name}
        </Link>

        <RatingLine product={product} />

        {product.soldCount > 0 && (
          <span className="num product-card__sold">{formatSold(product.soldCount)}</span>
        )}

        {!isOutOfStock && <StockBadge product={product} />}

        <div className="product-card__row">
          <PriceBlock price={product.price} originalPrice={product.originalPrice} size={18} />
          {needsOptions ? (
            <Link
              to={`/products/${product.id}`}
              className="product-card__cta product-card__cta--options"
            >
              Choose options
            </Link>
          ) : (
            <button
              disabled={!canAdd || addToCart.isPending}
              onClick={() => addToCart.mutate()}
              className="product-card__cta product-card__cta--add"
            >
              {added ? '✓ Added' : 'Add to cart'}
            </button>
          )}
          {/* TODO(notify-me): waitlist button — docs/tickets/notify-me-waitlist.md.
              Gated behind transactional email; do not render a dead control. */}
        </div>
      </div>
    </div>
  )
})

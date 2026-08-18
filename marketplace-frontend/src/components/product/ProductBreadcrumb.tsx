import React from 'react'
import { Link } from 'react-router-dom'
import { ProductResponse } from '../../lib/api'

/**
 * Home > [Parent] > Category, above the product.
 *
 * Replaces a "← Back to catalog" link that always pointed at "/", throwing
 * away whatever category or search the shopper arrived from. A trail is
 * strictly better: it says where you are, and every step is somewhere you
 * can actually go.
 *
 * The parent step only renders for a nested category, because
 * parentCategorySlug is null on top-level ones — no invented hierarchy.
 *
 * Same-tab links. A breadcrumb is a deliberate "take me back out", and
 * opening that in a tab would leave the shopper with two tabs and no sense
 * of having moved.
 */
export function ProductBreadcrumb({ product }: { product: ProductResponse }) {
  const sep = <span aria-hidden style={{ color: 'var(--line)' }}>›</span>

  return (
    <nav
      aria-label="Breadcrumb"
      style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        gap: 8, fontSize: 13, color: 'var(--ink-soft)', marginBottom: 20,
        flexWrap: 'wrap',
      }}
    >
      <Link to="/" style={{ color: 'var(--ink-soft)' }}>Home</Link>
      {product.parentCategorySlug && (
        <>
          {sep}
          <Link to={`/?category=${product.parentCategorySlug}`} style={{ color: 'var(--ink-soft)' }}>
            {product.parentCategorySlug.replace(/-/g, ' ')}
          </Link>
        </>
      )}
      {product.categorySlug && (
        <>
          {sep}
          <Link to={`/?category=${product.categorySlug}`} style={{ color: 'var(--ink)' }}>
            {product.categoryName ?? product.categorySlug}
          </Link>
        </>
      )}
    </nav>
  )
}

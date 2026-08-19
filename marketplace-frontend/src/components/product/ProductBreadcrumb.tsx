import React from 'react'
import { Link } from 'react-router-dom'
import { ProductResponse } from '../../lib/api'
import { findBySlug, useCategoryTree } from '../../hooks/useCategoryTree'

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
  const { data: tree = [] } = useCategoryTree(true)
  const parent = product.parentCategorySlug
    ? findBySlug(tree, product.parentCategorySlug)?.node
    : undefined
  const parentLabel = parent?.name
    ?? (product.parentCategorySlug ? product.parentCategorySlug.replace(/-/g, ' ') : undefined)

  const sep = <span aria-hidden className="crumb__sep">›</span>

  return (
    <nav aria-label="Breadcrumb" className="crumb">
      <Link to="/">Home</Link>
      {parentLabel && product.parentCategorySlug && (
        <>
          {sep}
          <Link to={`/?category=${product.parentCategorySlug}`}>
            {parentLabel}
          </Link>
        </>
      )}
      {product.categorySlug && (
        <>
          {sep}
          <Link to={`/?category=${product.categorySlug}`} className="crumb__here">
            {product.categoryName ?? product.categorySlug}
          </Link>
        </>
      )}
    </nav>
  )
}

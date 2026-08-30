import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { favorites, Page, ProductResponse } from '../lib/api'
import { SiteHeader } from '../components/layout/SiteHeader'
import { ProductCard } from '../components/product/ProductCard'

const PAGE_SIZE = 24

/**
 * The wishlist: every product whose heart is filled. Cards are the standard
 * ProductCard, so the heart on each one is also the remove control — the
 * toggle's onSettled invalidates ['favorites'], which refetches this list
 * and drops the card.
 *
 * Pagination is a GROWING WINDOW (always page 0, size grows), not
 * accumulated pages: removing a heart shifts every later item forward, and
 * stitched page-N snapshots would then duplicate or skip items. Refetching
 * the whole window costs a little more and is simply correct, which at
 * wishlist scale is the right trade.
 *
 * Soft-deleted products never appear (server contract): a heart on a ghost
 * helps nobody, and the Favorite row's survival means relisting restores it.
 */
export function WishlistPage() {
  const [size, setSize] = useState(PAGE_SIZE)

  const { data, isLoading } = useQuery<Page<ProductResponse>>({
    queryKey: ['favorites', 'list', size],
    queryFn: () => favorites.list(0, size),
  })
  const products = data?.content ?? []

  return (
    <>
      <SiteHeader />
      <main className="page-shell no-catrail">
        <div className="wishlist-head">
          <h1>Wishlist</h1>
          {data && data.totalElements > 0 && (
            <span className="num wishlist-count">{data.totalElements} saved</span>
          )}
        </div>

        {isLoading && products.length === 0 ? (
          <div className="product-grid product-grid--skeletons">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="skeleton-card" />
            ))}
          </div>
        ) : products.length === 0 ? (
          <div className="wishlist-empty">
            <p>Nothing saved yet.</p>
            <p className="muted-copy">
              Tap the heart on any product and it will wait for you here.
            </p>
            <Link to="/?shop=all" className="neon-cta wishlist-empty__cta">
              Browse the catalogue
            </Link>
          </div>
        ) : (
          <>
            <div className="product-grid">
              {products.map(p => <ProductCard key={p.id} product={p} />)}
            </div>
            {data && products.length < data.totalElements && (
              <div className="load-more">
                <button onClick={() => setSize(s => s + PAGE_SIZE)} className="btn-outline">
                  Load more · <span className="num">{products.length}</span> of{' '}
                  <span className="num">{data.totalElements}</span>
                </button>
              </div>
            )}
          </>
        )}
      </main>
    </>
  )
}

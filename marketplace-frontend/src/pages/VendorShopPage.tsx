import React from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, Page, ProductResponse } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { ProductCard } from '../components/product/ProductCard'
import { vendorHue } from '../lib/vendorHue'

const PAGE_SIZE = 40

/**
 * A vendor's public storefront: one link that shows everything they sell.
 *
 * This exists because vendors here are recruited in person at markets and
 * sell to people they already know. Before this page the only shareable
 * link was a single product, so a trader with five items had five links and
 * no way to say "here is my stall" on a WhatsApp status. That was the first
 * thing a real vendor asked for once she listed.
 *
 * Keyed by vendor id rather than a name slug: business_name has no
 * uniqueness constraint and no slug column, so a slug would need a
 * migration, a backfill, and collision handling on rename. An id in the URL
 * is uglier and correct today; a slug can be added later without breaking
 * this route.
 *
 * The vendor's display name is read off their products rather than a
 * separate lookup, which keeps this to zero new endpoints. The cost is that
 * a stall with no live listings cannot be named, handled below.
 */
export function VendorShopPage() {
  const { vendorId } = useParams()

  const { data, isLoading } = useQuery<Page<ProductResponse>>({
    queryKey: ['products', 'vendor', vendorId],
    queryFn: () => api(`/api/v1/products?vendorId=${vendorId}&page=0&size=${PAGE_SIZE}&sort=createdAt,desc`),
    enabled: !!vendorId,
  })

  const products = data?.content ?? []
  const vendorName = products[0]?.vendorName
  const stripe = vendorHue(Number(vendorId) || 1)
  const initial = (vendorName ?? '?').trim().charAt(0).toUpperCase()

  return (
    <>
      <Topbar />
      <main className="page-shell">
        <nav aria-label="Breadcrumb" className="shop-breadcrumb">
          <Link to="/">Home</Link>
          <span aria-hidden>/</span>
          <span>{vendorName ?? 'Stall'}</span>
        </nav>

        <header className="shop-header" style={{ borderTopColor: stripe }}>
          <div className="shop-header__avatar" style={{ background: stripe }} aria-hidden>{initial}</div>
          <div style={{ minWidth: 0 }}>
            <h1>{vendorName ?? (isLoading ? 'Loading…' : 'This stall')}</h1>
            <p>
              {isLoading
                ? 'Fetching listings'
                : products.length === 0
                  ? 'No listings yet'
                  : <>
                      <span className="num">{data?.totalElements ?? products.length}</span>
                      {' '}{(data?.totalElements ?? products.length) === 1 ? 'listing' : 'listings'} on eRestyu
                    </>}
            </p>
          </div>
        </header>

        {isLoading ? (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16 }}>
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} style={{ background: 'var(--line)', borderRadius: 'var(--r)', height: 320, animation: 'pulse 1.5s infinite' }} />
            ))}
          </div>
        ) : products.length === 0 ? (
          // Truthful empty state. A stall with nothing in it is a real
          // situation (a vendor who signed up but has not listed), and
          // saying so is better than a spinner that never resolves.
          <p style={{ color: 'var(--ink-soft)', fontSize: 14, padding: '24px 0' }}>
            This stall has no products listed right now.{' '}
            <Link to="/" style={{ color: 'var(--aloe-deep)', fontWeight: 600 }}>Browse everything on eRestyu</Link>
          </p>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16 }}>
            {products.map(p => <ProductCard key={p.id} product={p} />)}
          </div>
        )}
      </main>
    </>
  )
}

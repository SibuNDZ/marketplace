import React, { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { api, Page, ProductResponse } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { ProductCard } from '../components/product/ProductCard'
import { CategoryPane } from '../components/product/CategoryPane'
import { PromoCarousel } from '../components/promo/PromoCarousel'
import { ALL_SLUG } from '../data/categories'
import { useCategoryTree, findBySlug } from '../hooks/useCategoryTree'

// bestSelling and fiveStar run on REAL aggregates from the popularity read
// model. On a fresh catalog with no activity they simply match nothing —
// the truthful result, not a bug.
//
// handmade is different in kind and that difference is deliberate: it is a
// SERVER-side filter on a product column, so it goes in the query rather
// than filtering the current page client-side. Filtering handmade in the
// browser would only ever search the 20 products already loaded, which
// silently lies once there is more than one page.
const QUICK_FILTERS = [
  { key: 'handmade', label: '🧵 Handmade' },
  { key: 'bestSelling', label: '🔥 Best-Selling' },
  { key: 'fiveStar', label: '⭐ Top Rated' },
] as const
type QuickFilterKey = typeof QUICK_FILTERS[number]['key']

function SectionDivider({ icon, label }: { icon: string; label: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '32px 0 16px' }}>
      <span style={{ fontSize: 18 }} aria-hidden>{icon}</span>
      <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 18 }}>{label}</h2>
      <div style={{ flex: 1, height: 1, background: 'var(--line)' }} />
    </div>
  )
}

export function CatalogPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const category = searchParams.get('category') ?? ALL_SLUG
  const name = searchParams.get('name')?.trim() ?? ''
  const [activeFilters, setActiveFilters] = useState<Set<QuickFilterKey>>(new Set())
  const [page, setPage] = useState(0)
  const PAGE_SIZE = 20

  // Counts arrive on the tree itself, so there is no second request and no
  // way for a category and the number beside it to disagree.
  // includeEmpty=true keeps zero-stock departments available to the
  // header's More panel without making them primary navigation targets.
  const { data: tree } = useCategoryTree(true)
  const categoryTree = tree ?? []

  const selectCategory = (slug: string) => {
    const next = new URLSearchParams(searchParams)
    if (slug === ALL_SLUG) next.delete('category')
    else next.set('category', slug)
    setSearchParams(next)
  }

  const handmadeOnly = activeFilters.has('handmade')

  useEffect(() => {
    setPage(0)
  }, [category, name])

  // Category AND handmade are both server-side. Category is a slug now, and
  // a top-level slug also matches its subcategories on the backend, so
  // selecting Fashion returns the jewellery filed one level down.
  const { data, isLoading } = useQuery<Page<ProductResponse>>({
    queryKey: ['products', category, name, handmadeOnly, page, PAGE_SIZE],
    queryFn: () => api(
      `/api/v1/products?page=${page}&size=${PAGE_SIZE}&sort=createdAt,desc`
      + (category === ALL_SLUG ? '' : `&category=${category}`)
      + (name ? `&name=${encodeURIComponent(name)}` : '')
      + (handmadeOnly ? '&handmade=true' : ''),
    ),
  })

  const products = data?.content ?? []

  // Real signal: rated well by actual reviewers. Hidden entirely until
  // review data exists — an empty shelf is not filled with guesses.
  const recommended = products.filter(p => p.reviewCount > 0 && Number(p.avgRating) >= 4.0)

  const toggleFilter = (key: QuickFilterKey) => {
    setActiveFilters(prev => {
      const next = new Set(prev)
      next.has(key) ? next.delete(key) : next.add(key)
      return next
    })
    // handmade changes the QUERY, not just the client-side view, so the
    // current page number no longer means anything. The other two filter
    // what is already loaded and can leave paging alone.
    if (key === 'handmade') setPage(0)
  }

  let mainList = products
  if (activeFilters.has('fiveStar')) mainList = mainList.filter(p => p.reviewCount > 0 && Number(p.avgRating) >= 4.5)
  if (activeFilters.has('bestSelling')) mainList = [...mainList].filter(p => p.soldCount > 0).sort((a, b) => b.soldCount - a.soldCount)

  const found = category === ALL_SLUG ? undefined : findBySlug(categoryTree, category)
  const categoryLabel = category === ALL_SLUG
    ? 'All products'
    : found
      // "Fashion / Jewellery" rather than a bare "Jewellery": at two levels
      // the subcategory name alone loses the context the user just clicked
      // through, and several names (Shoes, Bags) read as ambiguous without it.
      ? (found.parent ? `${found.parent.name} / ${found.node.name}` : found.node.name)
      : 'Products'

  return (
    <>
      <Topbar />
      <main className="page-shell">
        <PromoCarousel />

        {/* Mobile-only seller strip (hidden on desktop via .seller-strip).
            One line, below the hero, nothing louder: vendor acquisition is
            in-person, and this is the tap a market vendor gets pointed at. */}
        <Link to="/register?role=vendor" className="seller-strip">
          🏪 Sell on eRestyu <span aria-hidden>→</span>
        </Link>

        <div className="catalog-layout">
          <CategoryPane tree={categoryTree} active={category} onSelect={selectCategory} />

          <div className="catalog-results">
            {/* Quick filter chips */}
            <div className="scroll-rail" style={{ display: 'flex', gap: 8, whiteSpace: 'nowrap' }}>
              {QUICK_FILTERS.map(f => {
                const isActive = activeFilters.has(f.key)
                return (
                  <button key={f.key} onClick={() => toggleFilter(f.key)} style={{
                    flexShrink: 0, padding: '7px 14px', borderRadius: 'var(--r-pill)',
                    border: isActive ? '1.5px solid var(--flame)' : '1.5px solid var(--line)',
                    background: isActive ? 'var(--flame-tint)' : 'var(--card)',
                    color: isActive ? 'var(--flame-deep)' : 'var(--ink)',
                    fontWeight: isActive ? 700 : 500, fontSize: 13,
                  }}>
                    {f.label}
                  </button>
                )
              })}
            </div>

            {isLoading ? (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16, marginTop: 24 }}>
                {Array.from({ length: 8 }).map((_, i) => (
                  <div key={i} style={{ background: 'var(--line)', borderRadius: 'var(--r)', height: 320, animation: 'pulse 1.5s infinite' }} />
                ))}
              </div>
            ) : (
              <>
                {category === ALL_SLUG && recommended.length > 0 && (
                  <>
                    <SectionDivider icon="⭐" label="Highly rated" />
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16 }}>
                      {recommended.map(p => <ProductCard key={`rec-${p.id}`} product={p} />)}
                    </div>
                  </>
                )}

                <SectionDivider icon="🛍️" label={name ? `Results for “${name}”` : categoryLabel} />
                {mainList.length === 0 ? (
                  <p style={{ color: 'var(--ink-soft)', fontSize: 14, padding: '20px 0' }}>No products match right now. Try a different category or filter.</p>
                ) : (
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16 }}>
                    {mainList.map(p => <ProductCard key={p.id} product={p} />)}
                  </div>
                )}

                {data && page + 1 < data.totalPages && (
                  <div style={{ textAlign: 'center', marginTop: 32 }}>
                    <button onClick={() => setPage(p => p + 1)} style={{
                      padding: '11px 28px', border: '1.5px solid var(--ink)',
                      borderRadius: 'var(--r-pill)', background: 'transparent',
                      fontWeight: 600, fontSize: 14,
                    }}>
                      Load more · <span className="num">{products.length}</span> of <span className="num">{data.totalElements}</span>
                    </button>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </main>
    </>
  )
}

import React, { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, Page, ProductResponse } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { CategoryPills } from '../components/layout/CategoryPills'
import { CategorySidebar } from '../components/layout/CategorySidebar'
import { ProductCard } from '../components/product/ProductCard'
import { PromoCarousel } from '../components/promo/PromoCarousel'
import { CATEGORIES } from '../data/categories'
import { getMarketplaceSignals } from '../lib/marketplaceSignals'

// Both filters run on REAL aggregates from the popularity read model.
// On a fresh catalog with no activity they simply match nothing — which
// is the truthful result, not a bug.
const QUICK_FILTERS = [
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
  const [category, setCategory] = useState('all')
  const [activeFilters, setActiveFilters] = useState<Set<QuickFilterKey>>(new Set())
  const [page, setPage] = useState(0)
  const PAGE_SIZE = 20

  const { data, isLoading } = useQuery<Page<ProductResponse>>({
    queryKey: ['products', page, PAGE_SIZE],
    queryFn: () => api(`/api/v1/products?page=${page}&size=${PAGE_SIZE}&sort=createdAt,desc`),
  })

  const products = data?.content ?? []

  // Category assignment is the one remaining fabrication (see
  // marketplaceSignals.ts) — counts are real counts of arbitrary groupings.
  const categoryCounts = useMemo(() => {
    const counts: Record<string, number> = {}
    products.forEach(p => {
      const key = getMarketplaceSignals(p.id).categoryKey
      counts[key] = (counts[key] ?? 0) + 1
    })
    return counts
  }, [products])

  // Real signal: rated well by actual reviewers. Hidden entirely until
  // review data exists — an empty shelf is not filled with guesses.
  const recommended = products.filter(p => p.reviewCount > 0 && Number(p.avgRating) >= 4.0)

  const toggleFilter = (key: QuickFilterKey) => {
    setActiveFilters(prev => {
      const next = new Set(prev)
      next.has(key) ? next.delete(key) : next.add(key)
      return next
    })
  }

  let mainList = category === 'all'
    ? products
    : products.filter(p => getMarketplaceSignals(p.id).categoryKey === category)
  if (activeFilters.has('fiveStar')) mainList = mainList.filter(p => p.reviewCount > 0 && Number(p.avgRating) >= 4.5)
  if (activeFilters.has('bestSelling')) mainList = [...mainList].filter(p => p.soldCount > 0).sort((a, b) => b.soldCount - a.soldCount)

  const categoryLabel = category === 'all' ? 'All products' : CATEGORIES.find(c => c.key === category)?.label ?? 'Products'

  return (
    <>
      <Topbar />
      <CategoryPills active={category} onSelect={setCategory} />
      <main className="page-shell">
        <PromoCarousel />

        <div style={{ display: 'flex', gap: 24, alignItems: 'flex-start' }}>
          <CategorySidebar active={category} onSelect={setCategory} counts={categoryCounts} />

          <div style={{ flex: 1, minWidth: 0 }}>
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
                {category === 'all' && recommended.length > 0 && (
                  <>
                    <SectionDivider icon="⭐" label="Highly rated" />
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16 }}>
                      {recommended.map(p => <ProductCard key={`rec-${p.id}`} product={p} />)}
                    </div>
                  </>
                )}

                <SectionDivider icon="🛍️" label={categoryLabel} />
                {mainList.length === 0 ? (
                  <p style={{ color: 'var(--ink-soft)', fontSize: 14, padding: '20px 0' }}>No products match right now — try a different category or filter.</p>
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

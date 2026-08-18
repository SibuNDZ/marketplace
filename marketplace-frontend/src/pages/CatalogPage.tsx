import React, { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { api, Page, ProductResponse } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { ProductCard } from '../components/product/ProductCard'
import { CategoryPane } from '../components/product/CategoryPane'
import { PromoCarousel } from '../components/promo/PromoCarousel'
import { CategoryBannerRow } from '../components/catalog/CategoryBannerRow'
import { ExpandedCategories } from '../components/catalog/ExpandedCategories'
import { FeaturedCarousel } from '../components/catalog/FeaturedCarousel'
import { RightCartPanel } from '../components/cart/RightCartPanel'
import { ALL_SLUG } from '../data/categories'
import { QUICK_FILTERS, QuickFilterKey, isQuickFilterKey } from '../data/quickFilters'
import { useCategoryTree, findBySlug } from '../hooks/useCategoryTree'
import { useSellerEntry } from '../hooks/useSellerEntry'

function SectionDivider({ icon, label }: { icon: string; label: string }) {
  return (
    <div className="section-divider">
      <span className="section-divider__icon" aria-hidden>{icon}</span>
      <h2>{label}</h2>
      <div className="section-divider__rule" />
    </div>
  )
}

export function CatalogPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const category = searchParams.get('category') ?? ALL_SLUG
  const name = searchParams.get('name')?.trim() ?? ''
  const [page, setPage] = useState(0)
  const PAGE_SIZE = 20
  const [loaded, setLoaded] = useState<ProductResponse[]>([])

  // Quick filters live in the URL (?filters=handmade,bestSelling) so the
  // chip row and the right panel's Highlights chips share one source of
  // truth, the same way ?category= keeps the sidebar and nav in sync.
  const filtersParam = searchParams.get('filters') ?? ''
  const activeFilters = useMemo(
    () => new Set(filtersParam.split(',').filter(isQuickFilterKey)),
    [filtersParam],
  )

  // Counts arrive on the tree itself, so there is no second request and no
  // way for a category and the number beside it to disagree.
  // includeEmpty=true keeps zero-stock departments available to the
  // header's More panel without making them primary navigation targets.
  const { data: tree } = useCategoryTree(true)
  const categoryTree = tree ?? []
  const sellerEntry = useSellerEntry()

  const selectCategory = (slug: string) => {
    const next = new URLSearchParams(searchParams)
    if (slug === ALL_SLUG) next.delete('category')
    else next.set('category', slug)
    setSearchParams(next)
  }

  const scrollToGrid = () => {
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    document.getElementById('all-products')
      ?.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' })
  }

  // Banner tiles and expanded-category chips filter the grid AND bring it
  // into view — clicking a tile far above the grid must show its effect.
  const browseCategory = (slug: string) => {
    selectCategory(slug)
    scrollToGrid()
  }

  const handmadeOnly = activeFilters.has('handmade')
  const fiveStar = activeFilters.has('fiveStar')
  const bestSelling = activeFilters.has('bestSelling')

  useEffect(() => {
    setPage(0)
    setLoaded([])
  }, [category, name, handmadeOnly, fiveStar, bestSelling])

  // Category, handmade, rating floor and sales rank are all server-side.
  // Client-side filter of the loaded page would only search the 20 products
  // already fetched, which silently lies once there is more than one page.
  const { data, isLoading } = useQuery<Page<ProductResponse>>({
    queryKey: ['products', category, name, handmadeOnly, fiveStar, bestSelling, page, PAGE_SIZE],
    queryFn: () => {
      let q = `/api/v1/products?page=${page}&size=${PAGE_SIZE}`
        + (category === ALL_SLUG ? '' : `&category=${category}`)
        + (name ? `&name=${encodeURIComponent(name)}` : '')
        + (handmadeOnly ? '&handmade=true' : '')
      if (bestSelling) q += '&minSold=1&rank=sales'
      else if (fiveStar) q += '&minRating=4.5&rank=rating'
      else q += '&sort=createdAt,desc'
      return api(q)
    },
  })

  useEffect(() => {
    if (!data) return
    if (page === 0) {
      setLoaded(data.content)
      return
    }
    setLoaded(prev => {
      const seen = new Set(prev.map(p => p.id))
      return [...prev, ...data.content.filter(p => !seen.has(p.id))]
    })
  }, [data, page])

  const products = loaded

  const { data: recommendedPage } = useQuery<Page<ProductResponse>>({
    queryKey: ['products', 'recommended'],
    queryFn: () => api('/api/v1/products?page=0&size=8&minRating=4.0&rank=rating'),
    enabled: category === ALL_SLUG && !name && !fiveStar && !bestSelling,
    staleTime: 5 * 60 * 1000,
  })
  const recommended = recommendedPage?.content ?? []

  const toggleFilter = (key: QuickFilterKey) => {
    const nextFilters = new Set(activeFilters)
    nextFilters.has(key) ? nextFilters.delete(key) : nextFilters.add(key)
    const next = new URLSearchParams(searchParams)
    if (nextFilters.size > 0) next.set('filters', [...nextFilters].join(','))
    else next.delete('filters')
    setSearchParams(next)
  }

  const mainList = products

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
        <CategoryBannerRow onSelect={browseCategory} />

        {/* Mobile-only seller strip (hidden on desktop via .seller-strip).
            One line, below the hero, nothing louder: vendor acquisition is
            in-person, and this is the tap a market vendor gets pointed at.
            Destination and wording follow the signed-in role — a vendor sees
            "List a product" pointing at their stall, not a signup pitch. */}
        {sellerEntry && (
          <Link to={sellerEntry.to} className="seller-strip">
            🏪 {sellerEntry.label} <span aria-hidden>→</span>
          </Link>
        )}

        <ExpandedCategories tree={categoryTree} active={category} onSelect={browseCategory} />

        <FeaturedCarousel />

        <div className="catalog-layout" id="all-products">
          <CategoryPane tree={categoryTree} active={category} onSelect={selectCategory} />

          <div className="catalog-results">
            {/* Quick filter chips */}
            <div className="scroll-rail filter-row">
              {QUICK_FILTERS.map(f => {
                const isActive = activeFilters.has(f.key)
                return (
                  <button key={f.key} onClick={() => toggleFilter(f.key)}
                    className={`filter-chip${isActive ? ' is-active' : ''}`}>
                    {f.label}
                  </button>
                )
              })}
            </div>

            {isLoading && loaded.length === 0 ? (
              <div className="product-grid product-grid--skeletons">
                {Array.from({ length: 8 }).map((_, i) => (
                  <div key={i} className="skeleton-card" />
                ))}
              </div>
            ) : (
              <>
                {category === ALL_SLUG && recommended.length > 0 && (
                  <>
                    <SectionDivider icon="⭐" label="Highly rated" />
                    <div className="product-grid">
                      {recommended.map(p => <ProductCard key={`rec-${p.id}`} product={p} />)}
                    </div>
                  </>
                )}

                <SectionDivider icon="🛍️" label={name ? `Results for “${name}”` : categoryLabel} />
                {mainList.length === 0 ? (
                  <p className="muted-copy">No products match right now. Try a different category or filter.</p>
                ) : (
                  <div className="product-grid">
                    {mainList.map(p => <ProductCard key={p.id} product={p} />)}
                  </div>
                )}

                {data && page + 1 < data.totalPages && (
                  <div className="load-more">
                    <button onClick={() => setPage(p => p + 1)} className="btn-outline">
                      Load more · <span className="num">{products.length}</span> of <span className="num">{data.totalElements}</span>
                    </button>
                  </div>
                )}
              </>
            )}
          </div>

          <RightCartPanel
            activeFilters={activeFilters}
            onHighlight={key => { toggleFilter(key); scrollToGrid() }}
          />
        </div>
      </main>
    </>
  )
}

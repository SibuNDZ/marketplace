import React, { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ProductResponse, ReviewSummary, ApiError } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { StockBadge } from '../components/ui/StockBadge'
import { vendorHue } from '../lib/vendorHue'
import { ErrorSurface } from '../components/ui/ErrorSurface'
import { productImageUrl, productImageSrcSet, IMAGE_WIDTHS, IMAGE_SIZES } from '../lib/productImage'
import { ProductReviews } from '../components/product/ProductReviews'
import { ProductBreadcrumb } from '../components/product/ProductBreadcrumb'
import { ProductRail } from '../components/product/ProductRail'
import { RAIL_CARD_WIDTH } from '../components/product/CompactProductCard'
import { RelatedSearches } from '../components/product/RelatedSearches'
import { PriceBlock } from '../components/product/PriceBlock'
import { ProductGallery } from '../components/product/ProductGallery'
import { VariantSelector } from '../components/product/VariantSelector'

export function ProductDetailPage() {
  const { id } = useParams()
  const qc = useQueryClient()
  const [qty, setQty] = useState(1)
  // Null until the shopper picks. Never defaulted to the first option — see
  // VariantSelector for why that would be buying on their behalf.
  const [variantId, setVariantId] = useState<number | null>(null)
  const [cartError, setCartError] = useState<ApiError>()
  // A signed-out visitor is not an error condition. Kept separate from
  // cartError so a 401 never renders through ErrorSurface, which is built
  // for genuine failures and shows a request id.
  const [needsSignIn, setNeedsSignIn] = useState(false)

  const { data: product, isLoading } = useQuery<ProductResponse>({
    queryKey: ['product', id],
    queryFn: () => api(`/api/v1/products/${id}`),
    enabled: !!id,
  })

  // Deliberate freshness split: catalog cards show the hourly popularity
  // aggregates; the page someone actually reads before buying calls the
  // LIVE summary endpoint, so a review posted a minute ago shows here now
  // and reaches the cards within the hour.
  const { data: summary } = useQuery<ReviewSummary>({
    queryKey: ['review-summary', id],
    queryFn: () => api(`/api/v1/products/${id}/reviews/summary`),
    enabled: !!id,
  })

  // Trailing-24h buyer count. Returns null far more often than not — the
  // backend withholds it below a threshold, because "1 person bought this"
  // is an urgency badge advertising an empty shop. Today it is null for
  // every product in production and no line renders at all; this is wired
  // up so it starts working on its own once real orders arrive.
  const { data: demand } = useQuery<{ recentBuyers: number | null }>({
    queryKey: ['demand', id],
    queryFn: () => api(`/api/v1/products/${id}/demand`),
    enabled: !!id,
    staleTime: 5 * 60 * 1000,
  })

  // The three rails. Each is independent and each hides itself when empty,
  // so a failure or a thin catalogue costs a section rather than the page.
  const { data: similar = [] } = useQuery<ProductResponse[]>({
    queryKey: ['product', id, 'similar', 12],
    queryFn: () => api(`/api/v1/products/${id}/similar?limit=12`),
    enabled: !!id,
    staleTime: 5 * 60 * 1000,
  })

  const { data: shopPage } = useQuery<{ content: ProductResponse[] }>({
    queryKey: ['product', id, 'from-shop', product?.vendorId],
    queryFn: () => api(`/api/v1/products?vendorId=${product!.vendorId}&size=12`),
    enabled: !!product?.vendorId,
    staleTime: 5 * 60 * 1000,
  })

  // Popularity, NOT similarity — this rail has to be different from the one
  // at the top of the page or it is the same shelf printed twice. Sorted by
  // views because sales are still too sparse to rank anything.
  const { data: popular = [] } = useQuery<ProductResponse[]>({
    queryKey: ['popular', 'views'],
    queryFn: () => api('/api/v1/products/popular?by=views'),
    staleTime: 5 * 60 * 1000,
  })

  const addToCart = useMutation({
    mutationFn: () => api('/api/v1/cart/items', {
      method: 'POST',
      body: { productId: Number(id), variantId, quantity: qty },
    }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['cart'] }) },
    onError: (e) => {
      if (!(e instanceof ApiError)) return
      // 401 means "we don't know who you are", not "something broke".
      // Showing an Unauthorized banner with a request id to a shopper who
      // simply is not signed in reads as a site fault and loses the sale.
      if (e.status === 401) { setNeedsSignIn(true); setCartError(undefined) }
      else { setCartError(e); setNeedsSignIn(false) }
    },
  })

  if (isLoading) return <><Topbar /><div className="page-shell no-catrail">Loading…</div></>
  if (!product) return <><Topbar /><div className="page-shell no-catrail">Product not found.</div></>

  const stripe = vendorHue(product.vendorId ?? 1)

  // Everything the buy box shows comes from the chosen option once there is
  // one: its price, its stock, and whether the button works. Showing the
  // product's aggregate price beside a selected option would be quoting a
  // number the shopper is not about to pay.
  const variants = product.variants ?? []
  const hasVariants = variants.length > 0
  const selected = variants.find(v => v.id === variantId) ?? null
  const shownPrice = selected ? selected.price : product.price
  const shownStock = selected ? selected.stock : product.stock

  // A variant product cannot be added until an option is chosen. The backend
  // enforces this too; disabling the button is so the shopper is told before
  // they click rather than after.
  const canAdd = hasVariants
    ? selected !== null && selected.stock > 0
    : product.stock > 0

  // Never show the product you are already looking at inside its own
  // recommendations. Applied here rather than in each rail so the rule is
  // stated once and cannot be forgotten by the next rail added.
  const notThisOne = (p: ProductResponse) => p.id !== product.id
  const fromShop = (shopPage?.content ?? []).filter(notThisOne)
  const alsoLike = popular
    .filter(notThisOne)
    // Also drop anything already shown in the Similar rail above: two rails
    // recommending the same product reads as a bug, not as emphasis.
    .filter(p => !similar.some(s => s.id === p.id))
    .slice(0, 12)

  return (
    <>
      <Topbar />
      {/* Vendor stripe band.
          Pinned to --header-height, the ONE variable that means "bottom of
          the fixed header". It previously used --trustbar-h + --topbar-h,
          which is 90px and counts only two of the header's three bars —
          the 44px category bar is missing from that sum, so the stripe was
          rendering underneath the header and was invisible. */}
      <div style={{ position: 'fixed', top: 'var(--header-height)', left: 0, right: 0, height: 5, background: stripe, zIndex: 99 }} />
      {/* Same arithmetic, same bug: the old padding of 127px sat 9px UNDER a
          136px header, so the first element on the page was always clipped.
          It went unnoticed while that element was a small back-link. */}
      <main className="page-shell no-catrail" style={{ paddingTop: 'calc(var(--header-height) + 5px + 28px)' }}>
        {/* Everything on this page lives inside one measured column. The
            page shell itself is full-bleed (--content-max: 100%), which is
            right for a catalogue grid and wrong for a reading surface: at
            1920px the old layout stretched the image column to ~1400px and
            the product photo swallowed the page. */}
        <div className="pdp" style={{ maxWidth: 1280, margin: '0 auto' }}>
          <ProductBreadcrumb product={product} />

          {/* Similar items FIRST, above the product itself. Counter-intuitive
              until you consider the shopper who followed a link to something
              nearly right: the fastest correction is a row of neighbours, and
              burying it below the reviews means they leave instead. Compact
              on purpose so it introduces the page rather than competing with
              it. */}
          <ProductRail
            title="Similar items"
            products={similar}
            cardWidth={RAIL_CARD_WIDTH.tight}
            seeMoreTo={`/products/${id}/similar`}
          />

        <div className="pdp-main">
          {/* Media. The thumbnail rail inside appears only for products with
              more than one photo, which is still most of this catalogue's
              exception rather than its rule. */}
          <ProductGallery product={product} />

          {/* Buy panel */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {/* Demand line. Renders ONLY when the count is genuinely above
                the backend's threshold, which today is never — so on the
                live site this element does not exist. It is not padded,
                bucketed or softened into "many people": if it says four,
                four different people bought it in the last 24 hours. */}
            {demand?.recentBuyers != null && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, fontWeight: 600, color: 'var(--flame)' }}>
                <span aria-hidden>🔥</span>
                <span>
                  In demand. <span className="num">{demand.recentBuyers}</span> people bought this in the last 24 hours.
                </span>
              </div>
            )}
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <div style={{ width: 9, height: 9, borderRadius: '50%', background: stripe }} />
              <span style={{ fontSize: 13, color: 'var(--ink-soft)' }}>{product.vendorName}</span>
            </div>
            <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 26, lineHeight: 1.2, color: 'var(--ink)' }}>{product.name}</h1>

            {/* Live review summary — renders only once reviews exist.
                Clicking jumps to the section rather than opening a modal. */}
            {summary && summary.reviewCount > 0 && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                <span style={{ color: 'var(--marigold)', fontSize: 14 }}>
                  {'★'.repeat(Math.round(summary.averageRating))}{'☆'.repeat(5 - Math.round(summary.averageRating))}
                </span>
                <a href="#reviews" style={{ fontSize: 13, color: 'var(--trust-blue)' }}>
                  <span className="num">{summary.averageRating.toFixed(1)}</span> (<span className="num">{summary.reviewCount.toLocaleString()}</span> review{summary.reviewCount !== 1 ? 's' : ''})
                </a>
                {product.soldCount > 0 && (
                  <span className="num" style={{ fontSize: 13, color: 'var(--ink-soft)' }}>· {product.soldCount} sold</span>
                )}
              </div>
            )}

            {product.description && <p style={{ color: 'var(--ink-soft)', lineHeight: 1.6 }}>{product.description}</p>}
            <StockBadge product={product} />
            <PriceBlock
              price={shownPrice}
              // An option's price has no "was" to compare against, so the
              // backend suppresses originalPrice entirely for variant
              // products. Passing it here would strike through a number that
              // was never this option's price.
              originalPrice={hasVariants ? null : product.originalPrice}
              size={32}
            />

            {hasVariants && (
              <VariantSelector
                variants={variants}
                selectedId={variantId}
                onSelect={setVariantId}
              />
            )}

            {needsSignIn && (
              <div style={{
                background: 'var(--flame-tint)', border: '1px solid var(--flame)',
                borderRadius: 'var(--r-sm)', padding: '12px 14px',
                fontSize: 13, lineHeight: 1.5, marginBottom: 12,
              }}>
                <Link
                  to="/login"
                  state={{ from: `/products/${id}` }}
                  style={{ color: 'var(--flame-deep)', fontWeight: 700 }}
                >
                  Sign in
                </Link>{' '}
                to add this to your cart. We will bring you straight back here.
              </div>
            )}

            {cartError && <ErrorSurface error={cartError} onDismiss={() => setCartError(undefined)} />}

            <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
              <div style={{ display: 'flex', alignItems: 'center', border: '1.5px solid var(--line)', borderRadius: 'var(--r-sm)', overflow: 'hidden' }}>
                <button onClick={() => setQty(q => Math.max(1, q - 1))} style={{ padding: '8px 14px', background: 'none', border: 'none', fontSize: 18 }}>−</button>
                <span className="num" style={{ padding: '0 12px', minWidth: 32, textAlign: 'center' }}>{qty}</span>
                <button onClick={() => setQty(q => Math.min(shownStock, q + 1))} style={{ padding: '8px 14px', background: 'none', border: 'none', fontSize: 18 }}>+</button>
              </div>
              <button disabled={!canAdd || addToCart.isPending} onClick={() => addToCart.mutate()} style={{
                flex: 1, padding: '11px 20px', background: canAdd ? 'var(--flame-gradient)' : 'var(--line)',
                color: canAdd ? '#fff' : 'var(--ink-soft)', border: 'none',
                borderRadius: 'var(--r-sm)', fontWeight: 700, fontSize: 15,
              }}>
                {addToCart.isPending ? 'Adding…'
                  : addToCart.isSuccess ? '✓ Added'
                  : hasVariants && !selected ? 'Choose an option'
                  : 'Add to cart'}
              </button>
            </div>
            <p style={{ fontSize: 12, color: 'var(--ink-soft)' }}>SKU: <span className="num">{product.sku ?? '-'}</span></p>

            {/* Who you are buying from. Vendors trade under a business name
                (V19), so this is a storefront, not a person — and now a real
                link to that storefront, so a shopper who likes one item can
                see the rest of the stall. */}
            <Link
              to={`/shop/${product.vendorId}`}
              aria-label={`See all products from ${product.vendorName ?? 'this vendor'}`}
              style={{
                marginTop: 4, padding: 14, borderRadius: 'var(--r-sm)',
                border: '1px solid var(--line)', display: 'flex', gap: 12, alignItems: 'center',
              }}>
              <div aria-hidden style={{
                width: 38, height: 38, borderRadius: '50%', background: stripe,
                display: 'grid', placeItems: 'center', color: '#fff', fontWeight: 800, fontSize: 15,
                flexShrink: 0,
              }}>
                {(product.vendorName ?? '?').trim().charAt(0).toUpperCase()}
              </div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 11, color: 'var(--ink-soft)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                  Sold by
                </div>
                <div style={{ fontWeight: 700, fontSize: 14 }}>{product.vendorName ?? 'Vendor'}</div>
                {product.categoryName && (
                  <div style={{ fontSize: 12, color: 'var(--ink-soft)' }}>
                    Listed in {product.categoryName}{product.handmade ? ' · Handmade' : ''}
                  </div>
                )}
              </div>
              <span aria-hidden style={{ marginLeft: 'auto', color: 'var(--ink-soft)', fontSize: 18 }}>›</span>
            </Link>
          </div>
        </div>

        <div id="reviews">
          <ProductReviews productId={String(id)} summary={summary} />
        </div>

        {/* Ordered narrowest to broadest: the same stall, then the wider
            catalogue, then plain search terms. Each rail hides itself when
            empty, so a thin catalogue produces a short page rather than a
            column of empty headings. */}
        <ProductRail
          title={`More from ${product.vendorName ?? 'this shop'}`}
          products={fromShop}
          cardWidth={RAIL_CARD_WIDTH.tight}
          seeMoreTo={`/shop/${product.vendorId}`}
          seeMoreLabel="Visit shop"
        />

        <ProductRail
          title="You may also like"
          products={alsoLike}
          cardWidth={RAIL_CARD_WIDTH.wide}
        />

        <RelatedSearches product={product} />
        </div>
      </main>
    </>
  )
}

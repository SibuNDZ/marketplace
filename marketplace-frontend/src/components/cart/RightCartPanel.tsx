import React, { useEffect, useRef } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, ShoppingCart, X } from 'lucide-react'
import { ApiError, api, CartResponse, ProductResponse } from '../../lib/api'
import { useAuth } from '../../context/AuthContext'
import { useRightPanel } from '../../context/RightPanelContext'
import { bargains, topSelling, useProductPool } from '../../hooks/useProductPool'
import { productImageUrl, productImageSrcSet, IMAGE_WIDTHS, IMAGE_SIZES } from '../../lib/productImage'
import { RatingLine } from '../product/RatingLine'
import { QUICK_FILTERS, QuickFilterKey } from '../../data/quickFilters'
import { CartLineImage } from './CartLineImage'

/**
 * Minimum-order indicator only — the backend enforces no minimum, so this
 * must never DISABLE checkout. It shows progress toward the amount where an
 * order stops being mostly delivery overhead for a stall vendor.
 */
export const CHECKOUT_MIN_RAND = 200

const QTY_CAP = 10

interface Props {
  activeFilters: Set<QuickFilterKey>
  /** Toggles a quick filter on the main grid and scrolls to it. */
  onHighlight: (key: QuickFilterKey) => void
}

/**
 * The persistent cart-plus-discovery column: sticky third column of the
 * catalogue grid on ≥1280px screens, and a right-hand slide-in drawer
 * behind a floating cart button below that. One component, both modes —
 * CSS switches the presentation, the context keeps state across routes.
 */
export function RightCartPanel({ activeFilters, onHighlight }: Props) {
  const { user } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const qc = useQueryClient()
  const {
    collapsed, setCollapsed, drawerOpen, openDrawer, closeDrawer,
    deselected, toggleItem, selectAll, deselectAll,
  } = useRightPanel()

  const panelRef = useRef<HTMLElement>(null)
  const fabRef = useRef<HTMLButtonElement>(null)

  const { data: cart } = useQuery<CartResponse>({
    queryKey: ['cart'],
    queryFn: () => api('/api/v1/cart'),
    enabled: !!user,
  })
  const { data: pool } = useProductPool()

  const items = cart?.items ?? []
  const itemCount = items.reduce((n, l) => n + l.quantity, 0)
  const selected = items.filter(l => !deselected.has(l.productId))
  const subtotal = selected.reduce((sum, l) => sum + Number(l.lineTotal), 0)
  const allSelected = items.length > 0 && selected.length === items.length
  const progress = Math.min(subtotal / CHECKOUT_MIN_RAND, 1)

  const updateQty = useMutation({
    mutationFn: ({ productId, variantId, quantity }: { productId: number; variantId?: number | null; quantity: number }) =>
      // variantId identifies WHICH line: a product can now appear twice in
      // one cart under different options.
      api(`/api/v1/cart/items/${productId}${variantId != null ? `?variantId=${variantId}` : ''}`,
        { method: 'PUT', body: { quantity } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cart'] }),
  })

  const addToCart = useMutation({
    mutationFn: (productId: number) => api('/api/v1/cart/items', {
      method: 'POST',
      body: { productId, quantity: 1 },
    }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cart'] }),
    onError: (e) => {
      // Same contract as ProductCard: a signed-out click routes to sign-in
      // and returns to the exact catalogue view, filters and all.
      if (e instanceof ApiError && e.status === 401) {
        navigate('/login', { state: { from: location.pathname + location.search } })
      }
    },
  })

  // Drawer mode: ESC closes, body scroll locks, focus moves in on open and
  // back to the floating button on close.
  useEffect(() => {
    if (!drawerOpen) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    panelRef.current?.querySelector<HTMLElement>('.right-cart-panel__close')?.focus()
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeDrawer()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
      fabRef.current?.focus()
    }
  }, [drawerOpen, closeDrawer])

  const sellers = topSelling(pool?.content ?? [], 4)
  const deals = bargains(pool?.content ?? [], 4)

  const miniRow = (p: ProductResponse) => (
    <div key={p.id} className="mini-product">
      {/* Same tab as the catalogue tiles. Cart line items below were already
          same-tab; the suggestion rows match them now. */}
      <Link to={`/products/${p.id}`} className="mini-product__main" onClick={closeDrawer}>
        <img
          src={productImageUrl(p, 88, 88)}
          srcSet={productImageSrcSet(p, IMAGE_WIDTHS.thumb)}
          sizes={IMAGE_SIZES.thumb}
          alt="" width={44} height={44} loading="lazy" decoding="async"
        />
        <span className="mini-product__text">
          <span className="mini-product__name">{p.name}</span>
          <RatingLine product={p} compact />
        </span>
        <span className="num mini-product__price">R{Number(p.price).toFixed(2)}</span>
      </Link>
      {/* Same rule as ProductCard: a product with options has to be chosen
          on its own page, so the quick-add becomes a link rather than a
          button that the backend would refuse. */}
      {(p.variants?.length ?? 0) > 0 ? (
        <Link
          className="mini-product__add"
          to={`/products/${p.id}`}
          aria-label={`Choose options for ${p.name}`}
        >›</Link>
      ) : (
        <button
          className="mini-product__add"
          aria-label={`Add ${p.name} to cart`}
          disabled={addToCart.isPending}
          onClick={() => addToCart.mutate(p.id)}
        >+</button>
      )}
    </div>
  )

  return (
    <>
      {/* Floating toggle — small screens only (CSS hides it at ≥1280px). */}
      <button
        ref={fabRef}
        className="right-panel-fab"
        aria-label={`Open cart panel${itemCount > 0 ? `, ${itemCount} items` : ''}`}
        onClick={openDrawer}
      >
        <span className="cart-action__icon">
          <ShoppingCart size={22} strokeWidth={1.75} />
          {itemCount > 0 && <span className="cart-count num">{itemCount}</span>}
        </span>
      </button>

      {drawerOpen && (
        <button className="right-panel-backdrop" aria-label="Close cart panel" onClick={closeDrawer} />
      )}

      <aside
        ref={panelRef}
        className={`right-cart-panel${collapsed ? ' is-collapsed' : ''}${drawerOpen ? ' is-open' : ''}`}
        aria-label="Cart and picks"
      >
        <div className="right-cart-panel__bar">
          {/* Collapse handle — desktop column mode only. */}
          <button
            className="right-cart-panel__collapse"
            aria-expanded={!collapsed}
            aria-label={collapsed ? 'Expand cart panel' : 'Collapse cart panel'}
            onClick={() => setCollapsed(!collapsed)}
          >
            {collapsed ? <ChevronLeft size={17} strokeWidth={1.75} /> : <ChevronRight size={17} strokeWidth={1.75} />}
          </button>
          {!collapsed && <span className="right-cart-panel__title">Your picks</span>}
          {collapsed && (
            <span className="cart-action__icon right-cart-panel__mini-count" aria-hidden>
              <ShoppingCart size={19} strokeWidth={1.75} />
              {itemCount > 0 && <span className="cart-count num">{itemCount}</span>}
            </span>
          )}
          {/* Close — drawer mode only. */}
          <button className="right-cart-panel__close" aria-label="Close cart panel" onClick={closeDrawer}>
            <X size={19} strokeWidth={1.75} />
          </button>
        </div>

        {!collapsed && (
          <div className="right-cart-panel__body">
            {/* -- cart summary -- */}
            <div className="rc-subtotal">
              <span>Subtotal</span>
              <span className="num">R{subtotal.toFixed(2)}</span>
            </div>

            <div className="rc-signals">
              <span className="rc-freeship">✓ Free shipping</span>
              <span className="rc-min-pill num">R{CHECKOUT_MIN_RAND} Min. to checkout</span>
            </div>
            <div
              className="rc-progress"
              role="progressbar"
              aria-label={`Progress toward R${CHECKOUT_MIN_RAND} minimum`}
              aria-valuemin={0}
              aria-valuemax={CHECKOUT_MIN_RAND}
              aria-valuenow={Math.round(Math.min(subtotal, CHECKOUT_MIN_RAND))}
            >
              <span style={{ width: `${progress * 100}%` }} />
            </div>

            <Link to="/cart" className="rc-go-to-cart" onClick={closeDrawer}>
              Go to cart <span aria-hidden>→</span>
            </Link>

            {/* -- cart lines -- */}
            {!user ? (
              <p className="rc-empty">
                <Link to="/login" state={{ from: location.pathname + location.search }}>Sign in</Link> to see your cart
              </p>
            ) : items.length === 0 ? (
              <p className="rc-empty">Your cart is empty</p>
            ) : (
              <>
                <label className="rc-select-all">
                  <input
                    type="checkbox"
                    checked={allSelected}
                    onChange={() => allSelected ? deselectAll(items.map(l => l.productId)) : selectAll()}
                  />
                  Select all (<span className="num">{items.length}</span>)
                </label>
                <div className="rc-items">
                  {items.map(line => (
                    <div key={line.productId} className="rc-item">
                      <input
                        type="checkbox"
                        checked={!deselected.has(line.productId)}
                        aria-label={`Select ${line.productName}`}
                        onChange={() => toggleItem(line.productId)}
                      />
                      <Link to={`/products/${line.productId}`} className="rc-item__main" onClick={closeDrawer}>
                        {/* The vendor's real photo. This used to be a picsum
                            stock image keyed on product id, so a shopper
                            reviewing their own cart saw pictures of things
                            they had not chosen. A missing photo now renders
                            the empty well rather than a confident wrong
                            image. */}
                        <CartLineImage line={line} size={44} />
                        {/* Without the option, two lines of the same product
                            read as a duplicate bug rather than as a small and
                            a large. */}
                        <span className="rc-item__name">
                          {line.productName}
                          {line.variantLabel && (
                            <span style={{ color: 'var(--ink-soft)' }}> · {line.variantLabel}</span>
                          )}
                        </span>
                        <span className="num rc-item__price">est. R{Number(line.lineTotal).toFixed(2)}</span>
                      </Link>
                      <select
                        className="rc-item__qty num"
                        value={line.quantity}
                        aria-label={`Quantity for ${line.productName}`}
                        onChange={e => updateQty.mutate({ productId: line.productId, variantId: line.variantId, quantity: Number(e.target.value) })}
                      >
                        {Array.from(
                          { length: Math.max(line.quantity, Math.min(QTY_CAP, line.availableStock)) },
                          (_, i) => <option key={i + 1} value={i + 1}>{i + 1}</option>,
                        )}
                      </select>
                    </div>
                  ))}
                </div>
              </>
            )}

            {/* -- discovery -- */}
            {sellers.length > 0 && (
              <section className="rc-section" aria-label="Top selling products">
                <h3>🔥 Top Selling</h3>
                <div className="rc-list">{sellers.map(miniRow)}</div>
              </section>
            )}

            {deals.length > 0 && (
              <section className="rc-section" aria-label="Bargain finds">
                {/* Ranks by real lowest price, not by markdown. A discount
                    model exists (V23) but vendor-set "was" prices are paused
                    as of 2026-08-13 pending a price-history-derived version,
                    so there is nothing to rank a genuine saving by. */}
                <h3>💰 Bargain finds</h3>
                <div className="rc-list">{deals.map(miniRow)}</div>
              </section>
            )}

            <section className="rc-section" aria-label="Highlights">
              <h3>✨ Highlights</h3>
              <div className="rc-chips">
                {QUICK_FILTERS.map(f => {
                  const isActive = activeFilters.has(f.key)
                  return (
                    <button
                      key={f.key}
                      className={`cat-chip${isActive ? ' is-active' : ''}`}
                      aria-pressed={isActive}
                      onClick={() => { onHighlight(f.key); closeDrawer() }}
                    >
                      {f.label}
                    </button>
                  )
                })}
              </div>
            </section>
          </div>
        )}
      </aside>
    </>
  )
}

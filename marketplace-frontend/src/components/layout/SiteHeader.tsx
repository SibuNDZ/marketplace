import React, { FormEvent, useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Bell, ChevronDown, Menu, Search, ShoppingCart, UserRound, X } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../context/AuthContext'
import { api, CartResponse } from '../../lib/api'
import { useCategoryTree } from '../../hooks/useCategoryTree'
import { useCartDrawer } from '../../context/CartDrawerContext'
import { ALL_SLUG } from '../../data/categories'
import { CartDrawer } from '../cart/CartDrawer'
import { RetailCategoryNav } from './RetailCategoryNav'
import { LogoMark } from './LogoMark'

export function SiteHeader() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const cartDrawer = useCartDrawer()
  const [accountOpen, setAccountOpen] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [expandedRoot, setExpandedRoot] = useState<string | null>(null)
  const [searchText, setSearchText] = useState(() => new URLSearchParams(location.search).get('name') ?? '')
  const drawerRef = useRef<HTMLDivElement>(null)
  const drawerTriggerRef = useRef<HTMLButtonElement>(null)
  const { data: tree = [] } = useCategoryTree(true)
  const activeCategory = new URLSearchParams(location.search).get('category') ?? ALL_SLUG

  const { data: cart } = useQuery<CartResponse>({
    queryKey: ['cart'],
    queryFn: () => api('/api/v1/cart'),
    enabled: !!user,
  })
  const itemCount = cart?.items?.reduce((total, line) => total + line.quantity, 0) ?? 0

  useEffect(() => {
    setSearchText(new URLSearchParams(location.search).get('name') ?? '')
  }, [location.search])

  useEffect(() => {
    if (!drawerOpen) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const focusable = () => Array.from(drawerRef.current?.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled])',
    ) ?? [])
    focusable()[0]?.focus()

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setDrawerOpen(false)
        return
      }
      if (event.key !== 'Tab') return
      const items = focusable()
      if (items.length === 0) return
      const first = items[0]
      const last = items[items.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      document.body.style.overflow = previousOverflow
      drawerTriggerRef.current?.focus()
    }
  }, [drawerOpen])

  const currentCatalogParams = () => location.pathname === '/'
    ? new URLSearchParams(location.search)
    : new URLSearchParams()

  const selectCategory = (slug: string) => {
    const next = currentCatalogParams()
    if (slug === ALL_SLUG) next.delete('category')
    else next.set('category', slug)
    setDrawerOpen(false)
    navigate(`/${next.size ? `?${next}` : ''}`)
  }

  const submitSearch = (event: FormEvent) => {
    event.preventDefault()
    const next = currentCatalogParams()
    const query = searchText.trim()
    if (query) next.set('name', query)
    else next.delete('name')
    setDrawerOpen(false)
    navigate(`/${next.size ? `?${next}` : ''}`)
  }

  const handleLogout = async () => {
    setAccountOpen(false)
    setDrawerOpen(false)
    await logout()
    navigate('/login')
  }

  const roleDestination = user?.role === 'ADMIN' ? '/admin' : user?.role === 'VENDOR' ? '/vendor' : '/orders'
  const roleLabel = user?.role === 'ADMIN' ? 'Admin' : user?.role === 'VENDOR' ? 'Dashboard' : 'Orders'

  const searchForm = (mobile = false) => (
    <form className={`site-search${mobile ? ' site-search--mobile' : ''}`} onSubmit={submitSearch} role="search">
      <Search size={19} strokeWidth={1.75} aria-hidden />
      <input
        type="search"
        value={searchText}
        onChange={event => setSearchText(event.target.value)}
        placeholder="Search products or vendors"
        aria-label="Search products or vendors"
      />
      <button type="submit" aria-label="Submit search"><Search size={18} strokeWidth={1.75} /></button>
    </form>
  )

  return (
    <>
      <header className="site-header">
        <div className="utility-bar">
          <div className="utility-bar__inner">
            <div className="utility-bar__claims">
              <span>Secure checkout</span><span aria-hidden>·</span><span>Unpaid orders cancel free</span>
            </div>
            <nav aria-label="Utility navigation">{user && <Link to="/orders">Orders</Link>}</nav>
          </div>
        </div>

        <div className="main-bar">
          <div className="main-bar__inner main-bar__desktop">
            <Link to="/" className="wordmark-group"><LogoMark size={26} /><span className="wordmark">eRestyu</span></Link>
            {searchForm()}
            <nav className="main-actions" aria-label="Account and cart">
              <button className="main-action notification-action" aria-label="Notifications" title="Notifications">
                <Bell size={20} strokeWidth={1.75} />
              </button>
              <div className="account-action">
                <button className="main-action" onClick={() => setAccountOpen(open => !open)} aria-expanded={accountOpen}>
                  <UserRound size={20} strokeWidth={1.75} /><span>Account</span><ChevronDown size={14} strokeWidth={1.75} aria-hidden />
                </button>
                {accountOpen && (
                  <div className="account-menu">
                    {user ? (
                      <>
                        <span>{user.email}</span>
                        <Link to="/orders" onClick={() => setAccountOpen(false)}>Orders</Link>
                        {user.role !== 'CUSTOMER' && <Link to={roleDestination} onClick={() => setAccountOpen(false)}>{roleLabel}</Link>}
                        <Link to="/account" onClick={() => setAccountOpen(false)}>Account settings</Link>
                        <Link to="/feedback" onClick={() => setAccountOpen(false)}>Give feedback</Link>
                        <button onClick={handleLogout}>Sign out</button>
                      </>
                    ) : (
                      <>
                        {/* Both doors visible: a new visitor should not have
                            to know that registration hides behind Sign in. */}
                        <Link to="/login" onClick={() => setAccountOpen(false)}>Sign in</Link>
                        <Link to="/register" onClick={() => setAccountOpen(false)}>Create account</Link>
                      </>
                    )}
                  </div>
                )}
              </div>
              <button className="main-action cart-action" onClick={cartDrawer.open}>
                <span className="cart-action__icon"><ShoppingCart size={20} strokeWidth={1.75} />
                  {itemCount > 0 && <span className="cart-count num">{itemCount}</span>}
                </span><span>Cart</span>
              </button>
            </nav>
          </div>

          <div className="main-bar__inner main-bar__mobile">
            <button ref={drawerTriggerRef} className="mobile-icon" onClick={() => setDrawerOpen(true)} aria-label="Open menu"><Menu size={23} strokeWidth={1.75} /></button>
            <Link to="/" className="wordmark-group"><LogoMark size={22} /><span className="wordmark">eRestyu</span></Link>
            <div className="mobile-actions">
              <button className="mobile-icon" aria-label="Notifications" title="Notifications"><Bell size={21} strokeWidth={1.75} /></button>
              {/* Persistent account entry: field-tested gap. A vendor at a
                  market stall could not find where to register because
                  account access only existed inside the hamburger drawer.
                  Signed out this goes straight to sign-in; signed in it
                  opens the same menu as desktop, full-width under the bar. */}
              <button className="mobile-icon" aria-label="Account"
                onClick={() => user ? setAccountOpen(open => !open) : navigate('/login')}
                aria-expanded={user ? accountOpen : undefined}>
                <UserRound size={22} strokeWidth={1.75} />
              </button>
              <button className="mobile-icon" onClick={() => setDrawerOpen(true)} aria-label="Open search"><Search size={22} strokeWidth={1.75} /></button>
              <button className="mobile-icon cart-action__icon" onClick={cartDrawer.open} aria-label="Open cart">
                <ShoppingCart size={22} strokeWidth={1.75} />{itemCount > 0 && <span className="cart-count num">{itemCount}</span>}
              </button>
            </div>
            {accountOpen && user && (
              <div className="account-menu account-menu--mobile">
                <span>{user.email}</span>
                <Link to="/orders" onClick={() => setAccountOpen(false)}>Orders</Link>
                {user.role !== 'CUSTOMER' && <Link to={roleDestination} onClick={() => setAccountOpen(false)}>{roleLabel}</Link>}
                <Link to="/account" onClick={() => setAccountOpen(false)}>Account settings</Link>
                <Link to="/feedback" onClick={() => setAccountOpen(false)}>Give feedback</Link>
                <button onClick={handleLogout}>Sign out</button>
              </div>
            )}
          </div>
        </div>

        <RetailCategoryNav tree={tree} active={activeCategory} onSelect={selectCategory} />
      </header>

      {drawerOpen && (
        <div className="mobile-drawer-layer">
          <button className="mobile-drawer-backdrop" onClick={() => setDrawerOpen(false)} aria-label="Close menu" />
          <div ref={drawerRef} className="mobile-drawer" role="dialog" aria-modal="true" aria-label="Site menu">
            <div className="mobile-drawer__header">
              <span className="wordmark-group"><LogoMark size={22} /><span className="wordmark">eRestyu</span></span>
              <button className="mobile-icon" onClick={() => setDrawerOpen(false)} aria-label="Close menu"><X size={22} /></button>
            </div>
            {searchForm(true)}
            <nav className="mobile-category-tree" aria-label="Product categories">
              <button className={activeCategory === ALL_SLUG ? 'is-active' : ''} onClick={() => selectCategory(ALL_SLUG)}>All products</button>
              {[...tree].sort((a, b) => b.productCount - a.productCount).map(root => (
                <div key={root.slug}>
                  <div className="mobile-category-tree__root">
                    <button onClick={() => root.productCount > 0 && selectCategory(root.slug)} disabled={root.productCount === 0}>
                      <span>{root.name}</span><span className="num">{root.productCount}</span>
                    </button>
                    {root.children.some(child => child.productCount > 0) && (
                      <button className="mobile-category-tree__toggle" onClick={() => setExpandedRoot(current => current === root.slug ? null : root.slug)} aria-expanded={expandedRoot === root.slug} aria-label={`Show ${root.name} subcategories`}><ChevronDown size={17} /></button>
                    )}
                  </div>
                  {expandedRoot === root.slug && (
                    <ul>{root.children.filter(child => child.productCount > 0).map(child => (
                      <li key={child.slug}><button onClick={() => selectCategory(child.slug)}><span>{child.name}</span><span className="num">{child.productCount}</span></button></li>
                    ))}</ul>
                  )}
                </div>
              ))}
            </nav>
            {/* Vendor acquisition happens in person at markets; the seller
                door must be one obvious tap, not a scavenger hunt. */}
            <Link to="/register?role=vendor" className="mobile-drawer__sell" onClick={() => setDrawerOpen(false)}>
              🏪 Sell on eRestyu <span aria-hidden>→</span>
            </Link>
            <nav className="mobile-account-links" aria-label="Account links">
              {user ? (
                <>
                  <Link to="/orders" onClick={() => setDrawerOpen(false)}>Orders</Link>
                  {user.role !== 'CUSTOMER' && <Link to={roleDestination} onClick={() => setDrawerOpen(false)}>{roleLabel}</Link>}
                  <Link to="/account" onClick={() => setDrawerOpen(false)}>Account settings</Link>
                  <Link to="/feedback" onClick={() => setDrawerOpen(false)}>Give feedback</Link>
                  <button onClick={handleLogout}>Sign out</button>
                </>
              ) : (
                <>
                  <Link to="/login" onClick={() => setDrawerOpen(false)}>Sign in</Link>
                  <Link to="/register" onClick={() => setDrawerOpen(false)}>Create account</Link>
                </>
              )}
            </nav>
            <div className="mobile-drawer__trust"><span>Secure checkout</span><span>Unpaid orders cancel free</span></div>
          </div>
        </div>
      )}
      <CartDrawer />
    </>
  )
}

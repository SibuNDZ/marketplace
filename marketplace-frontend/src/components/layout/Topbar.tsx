import React, { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import { useQuery } from '@tanstack/react-query'
import { api, CartResponse } from '../../lib/api'
import { useCartDrawer } from '../../context/CartDrawerContext'
import { TopTrustBar } from './TopTrustBar'
import { CartDrawer } from '../cart/CartDrawer'
import { PILL_CATEGORIES } from '../../data/categories'

function IconButton({ label, children, onClick }: { label: string; children: React.ReactNode; onClick?: () => void }) {
  return (
    <button onClick={onClick} aria-label={label} title={label} style={{
      background: 'none', border: 'none', fontSize: 19, color: 'var(--ink-soft)',
      width: 34, height: 34, display: 'flex', alignItems: 'center', justifyContent: 'center',
      borderRadius: 'var(--r-sm)', position: 'relative',
    }}>
      {children}
    </button>
  )
}

export function Topbar() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const cartDrawer = useCartDrawer()
  const [accountOpen, setAccountOpen] = useState(false)

  const { data: cart } = useQuery<CartResponse>({
    queryKey: ['cart'],
    queryFn: () => api('/api/v1/cart'),
    enabled: !!user,
  })

  const itemCount = cart?.items?.reduce((n, l) => n + l.quantity, 0) ?? 0

  const handleLogout = async () => {
    setAccountOpen(false)
    await logout()
    navigate('/login')
  }

  return (
    <>
      <TopTrustBar />
      <header style={{
        position: 'fixed', top: 'var(--trustbar-h)', left: 0, right: 0, height: 'var(--topbar-h)',
        background: 'var(--card)',
        borderBottom: '1px solid var(--line)',
        zIndex: 100, display: 'flex', alignItems: 'center',
        padding: '0 var(--gutter)',
      }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 20,
          maxWidth: 'var(--content-max)', width: '100%', margin: '0 auto',
        }}>
          {/* Brand */}
          <Link to="/" style={{
            fontFamily: 'var(--display)', fontWeight: 800, fontSize: 24, letterSpacing: '-0.03em', flexShrink: 0,
            background: 'var(--flame-gradient)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent',
            backgroundClip: 'text',
          }}>
            market
          </Link>

          {/* Search with category dropdown */}
          <div style={{
            flex: 1, maxWidth: 560, display: 'flex', alignItems: 'stretch',
            border: '1.5px solid var(--line)', borderRadius: 'var(--r-pill)', overflow: 'hidden', background: 'var(--card)',
          }}>
            <select aria-label="Category" style={{
              border: 'none', borderRight: '1px solid var(--line)', background: 'var(--paper)',
              fontSize: 13, fontWeight: 500, color: 'var(--ink-soft)', padding: '0 10px',
              maxWidth: 150,
            }}>
              {PILL_CATEGORIES.map(c => <option key={c.key} value={c.key}>{c.icon} {c.label}</option>)}
            </select>
            <input
              type="search"
              placeholder="Search products or vendors"
              style={{
                flex: 1, padding: '9px 14px', border: 'none', outline: 'none',
                fontFamily: 'var(--body)', fontSize: 14, color: 'var(--ink)',
              }}
            />
            <button aria-label="Search" style={{
              background: 'var(--flame-gradient)', border: 'none', color: '#fff',
              padding: '0 18px', fontSize: 15,
            }}>🔍</button>
          </div>

          {/* Icon nav */}
          <nav style={{ display: 'flex', alignItems: 'center', gap: 4, marginLeft: 'auto' }}>
            <IconButton label="Notifications">🔔</IconButton>
            <IconButton label="Messages">💬</IconButton>

            <div style={{ position: 'relative' }}>
              <IconButton label="Account" onClick={() => setAccountOpen(o => !o)}>👤</IconButton>
              {accountOpen && (
                <>
                  <div onClick={() => setAccountOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 149 }} />
                  <div style={{
                    position: 'absolute', top: 40, right: 0, minWidth: 180, zIndex: 150,
                    background: 'var(--card)', borderRadius: 'var(--r)', boxShadow: 'var(--shadow-lift)',
                    border: '1px solid var(--line)', padding: 6, display: 'flex', flexDirection: 'column',
                  }}>
                    {user ? (
                      <>
                        <p style={{ fontSize: 12, color: 'var(--ink-soft)', padding: '6px 10px' }}>{user.email}</p>
                        <Link to="/orders" onClick={() => setAccountOpen(false)} style={{ padding: '8px 10px', fontSize: 13, borderRadius: 'var(--r-sm)' }}>Orders</Link>
                        {user.role === 'VENDOR' && (
                          <Link to="/vendor" onClick={() => setAccountOpen(false)} style={{ padding: '8px 10px', fontSize: 13, borderRadius: 'var(--r-sm)' }}>Sell</Link>
                        )}
                        {user.role === 'ADMIN' && (
                          <Link to="/admin" onClick={() => setAccountOpen(false)} style={{ padding: '8px 10px', fontSize: 13, borderRadius: 'var(--r-sm)' }}>Admin</Link>
                        )}
                        <button onClick={handleLogout} style={{ padding: '8px 10px', fontSize: 13, borderRadius: 'var(--r-sm)', textAlign: 'left', background: 'none', border: 'none', color: 'var(--clay)' }}>
                          Sign out
                        </button>
                      </>
                    ) : (
                      <Link to="/login" onClick={() => setAccountOpen(false)} style={{ padding: '8px 10px', fontSize: 13, borderRadius: 'var(--r-sm)' }}>Sign in</Link>
                    )}
                  </div>
                </>
              )}
            </div>

            {/* Cart */}
            <button onClick={() => cartDrawer.open()} style={{
              display: 'flex', alignItems: 'center', gap: 8,
              background: 'var(--flame-gradient)', color: '#fff', border: 'none',
              padding: '8px 16px', borderRadius: 'var(--r-pill)',
              fontWeight: 700, fontSize: 14, marginLeft: 6,
            }}>
              🛒 Cart
              {itemCount > 0 && (
                <span className="num" style={{
                  background: 'rgba(255,255,255,0.28)', color: '#fff',
                  borderRadius: 'var(--r-pill)', padding: '0 7px', fontSize: 12, fontWeight: 700,
                  animation: 'pulse-badge 1.6s ease-in-out infinite',
                }}>
                  {itemCount}
                </span>
              )}
            </button>
          </nav>
        </div>
      </header>
      <CartDrawer />
    </>
  )
}

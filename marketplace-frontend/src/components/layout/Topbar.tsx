import React from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import { useQuery } from '@tanstack/react-query'
import { api, CartResponse } from '../../lib/api'

export function Topbar() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  const { data: cart } = useQuery<CartResponse>({
    queryKey: ['cart'],
    queryFn: () => api('/api/v1/cart'),
    enabled: !!user,
  })

  const itemCount = cart?.items?.reduce((n, l) => n + l.quantity, 0) ?? 0

  const handleLogout = async () => {
    await logout()
    navigate('/login')
  }

  return (
    <header style={{
      position: 'fixed', inset: '0 0 auto 0', height: 'var(--topbar-h)',
      background: 'rgba(245,247,243,0.88)',
      backdropFilter: 'blur(12px)', WebkitBackdropFilter: 'blur(12px)',
      borderBottom: '1px solid var(--line)',
      zIndex: 100, display: 'flex', alignItems: 'center',
      padding: '0 var(--gutter)',
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 24,
        maxWidth: 'var(--content-max)', width: '100%', margin: '0 auto',
      }}>
        {/* Brand */}
        <Link to="/" style={{ fontFamily: 'var(--display)', fontWeight: 800, fontSize: 22, letterSpacing: '-0.03em', flexShrink: 0 }}>
          <span style={{ color: 'var(--ink)' }}>mark</span>
          <span style={{ color: 'var(--aloe)' }}>et</span>
        </Link>

        {/* Search */}
        <div style={{ flex: 1, maxWidth: 400 }}>
          <input
            type="search"
            placeholder="Search products or vendors"
            style={{
              width: '100%', padding: '8px 14px',
              border: '1.5px solid var(--line)', borderRadius: 'var(--r-pill)',
              background: 'var(--card)', fontFamily: 'var(--body)', fontSize: 14,
              color: 'var(--ink)', outline: 'none',
            }}
          />
        </div>

        {/* Nav */}
        <nav style={{ display: 'flex', alignItems: 'center', gap: 20, marginLeft: 'auto' }}>
          {user && (
            <Link to="/orders" style={{ fontSize: 14, fontWeight: 500, color: 'var(--ink-soft)' }}>
              Orders
            </Link>
          )}
          {user?.role === 'VENDOR' && (
            <Link to="/vendor" style={{ fontSize: 14, fontWeight: 500, color: 'var(--ink-soft)' }}>
              Sell
            </Link>
          )}
          {user?.role === 'ADMIN' && (
            <Link to="/admin" style={{ fontSize: 14, fontWeight: 500, color: 'var(--ink-soft)' }}>
              Admin
            </Link>
          )}
          {user ? (
            <button onClick={handleLogout} style={{
              fontSize: 13, color: 'var(--ink-soft)', background: 'none', border: 'none',
            }}>
              Sign out
            </button>
          ) : (
            <Link to="/login" style={{ fontSize: 14, fontWeight: 500, color: 'var(--ink-soft)' }}>
              Sign in
            </Link>
          )}

          {/* Cart button */}
          <Link to="/cart" style={{
            display: 'flex', alignItems: 'center', gap: 8,
            background: 'var(--ink)', color: '#fff',
            padding: '8px 16px', borderRadius: 'var(--r-pill)',
            fontWeight: 600, fontSize: 14,
          }}>
            Cart
            {itemCount > 0 && (
              <span className="num" style={{
                background: 'var(--aloe)', color: '#fff',
                borderRadius: 'var(--r-pill)', padding: '0 7px', fontSize: 12, fontWeight: 700,
              }}>
                {itemCount}
              </span>
            )}
          </Link>
        </nav>
      </div>
    </header>
  )
}

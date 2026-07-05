import React from 'react'
import { Link } from 'react-router-dom'
import { Topbar } from '../components/layout/Topbar'

export function CheckoutCancelledPage() {
  return (
    <>
      <Topbar />
      <main className="page-shell" style={{ textAlign: 'center', paddingTop: 80 }}>
        <div style={{ fontSize: 48, marginBottom: 16 }}>↩️</div>
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28, marginBottom: 8 }}>Payment cancelled</h1>
        <p style={{ color: 'var(--ink-soft)', marginBottom: 28 }}>No charge was made. Your cart is still waiting.</p>
        <Link to="/cart" style={{ padding: '11px 28px', background: 'var(--ink)', color: '#fff', borderRadius: 'var(--r-pill)', fontWeight: 600, fontSize: 15 }}>
          Return to cart
        </Link>
      </main>
    </>
  )
}

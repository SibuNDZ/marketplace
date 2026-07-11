import React from 'react'

// Only claims the system actually backs: Stripe handles checkout, PENDING
// orders are cancellable (OrderService.cancelOrder), location is a locale
// statement. The old "free shipping over R500" / "price adjustment" pills
// promised policies that exist nowhere in the backend.
const PILLS = [
  { icon: '🔒', label: 'Secure checkout via Stripe' },
  { icon: '↩️', label: 'Cancel unpaid orders anytime' },
  { icon: '📍', label: 'Cape Town, Western Cape' },
]

export function TopTrustBar() {
  return (
    <div style={{
      position: 'fixed', top: 0, left: 0, right: 0, zIndex: 101,
      background: 'var(--ink)', color: '#fff',
      height: 'var(--trustbar-h, 30px)',
      display: 'flex', alignItems: 'center', overflow: 'hidden',
    }}>
      <div className="scroll-rail" style={{
        display: 'flex', alignItems: 'center', gap: 20,
        maxWidth: 'var(--content-max)', width: '100%', margin: '0 auto',
        padding: '0 var(--gutter)', whiteSpace: 'nowrap',
      }}>
        {PILLS.map(p => (
          <span key={p.label} style={{ fontSize: 12, fontWeight: 500, display: 'flex', alignItems: 'center', gap: 5, flexShrink: 0 }}>
            <span aria-hidden>{p.icon}</span>{p.label}
            <span style={{ color: 'var(--sun)', marginLeft: 2 }}>›</span>
          </span>
        ))}
      </div>
    </div>
  )
}

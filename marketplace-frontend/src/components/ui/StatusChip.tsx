import React from 'react'

const MAP: Record<string, { label: string; color: string }> = {
  PENDING:   { label: 'Pending',   color: 'var(--ink-soft)' },
  PAID:      { label: 'Paid',      color: 'var(--aloe)' },
  SHIPPED:   { label: 'Shipped',   color: 'var(--ink)' },
  DELIVERED: { label: 'Delivered', color: 'var(--aloe-deep)' },
  CANCELLED: { label: 'Cancelled', color: '#888' },
  REFUNDED:  { label: 'Refunded',  color: '#888' },
}

export function StatusChip({ status }: { status: string }) {
  const { label, color } = MAP[status] ?? { label: status, color: 'var(--ink-soft)' }
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '3px 10px', borderRadius: 'var(--r-pill)',
      border: `1.5px solid ${color}`, color, fontSize: 12, fontWeight: 600,
    }}>
      {label}
    </span>
  )
}

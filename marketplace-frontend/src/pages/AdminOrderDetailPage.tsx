import React from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, OrderResponse } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { StatusChip } from '../components/ui/StatusChip'

/**
 * Read-only — the whole point of this page is to let an admin actually see
 * an order (items, address) before shipping it; the transition buttons stay
 * on AdminPage's table. shippingAddress is rendered whenever the API
 * returns it and hidden whenever it doesn't — the backend's PAID-or-later
 * masking rule (OrderService.shippingFor) is trusted completely here, not
 * re-derived from order.status. That duplication is exactly what the
 * backend note warns against.
 */
export function AdminOrderDetailPage() {
  const { id } = useParams()

  const { data: order } = useQuery<OrderResponse>({
    queryKey: ['admin-order', id],
    queryFn: () => api(`/api/v1/admin/orders/${id}`),
    enabled: !!id,
  })

  if (!order) return <><Topbar /><div className="page-shell no-catrail">Loading…</div></>

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail" style={{ maxWidth: 640 }}>
        <Link to="/admin" style={{ fontSize: 13, color: 'var(--ink-soft)', display: 'inline-flex', alignItems: 'center', gap: 4, marginBottom: 24 }}>
          ← Orders
        </Link>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 28 }}>
          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 26 }} className="num">Order #{order.id}</h1>
          <StatusChip status={order.status} />
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {order.items.map((item, i) => (
            <div key={i} style={{ display: 'flex', justifyContent: 'space-between', padding: '14px 0', borderBottom: '1px solid var(--line)' }}>
              <div>
                <p style={{ fontWeight: 600 }}>{item.productName}</p>
                <p className="num" style={{ fontSize: 13, color: 'var(--ink-soft)' }}>
                  {item.quantity} × R{Number(item.unitPrice).toFixed(2)}
                </p>
              </div>
              <p className="num" style={{ fontWeight: 700 }}>R{Number(item.lineTotal).toFixed(2)}</p>
            </div>
          ))}
          <div style={{ display: 'flex', justifyContent: 'flex-end', paddingTop: 8 }}>
            <span style={{ fontWeight: 700, fontSize: 18 }}>
              Total: <span className="num">R{Number(order.total).toFixed(2)}</span>
            </span>
          </div>
        </div>

        <div style={{ marginTop: 28 }}>
          <h2 style={{ fontSize: 15, fontWeight: 700, marginBottom: 10 }}>Shipping to</h2>
          {order.shippingAddress ? (
            <div style={{ background: 'var(--card)', borderRadius: 'var(--r)', padding: 18, boxShadow: 'var(--shadow)', fontSize: 14, lineHeight: 1.6 }}>
              <p style={{ fontWeight: 600 }}>{order.shippingAddress.recipientName}</p>
              <p>{order.shippingAddress.addressLine1}</p>
              {order.shippingAddress.addressLine2 && <p>{order.shippingAddress.addressLine2}</p>}
              <p>{order.shippingAddress.city}, {order.shippingAddress.province} {order.shippingAddress.postalCode}</p>
              <p style={{ color: 'var(--ink-soft)', marginTop: 4 }} className="num">{order.shippingAddress.phone}</p>
            </div>
          ) : (
            <p style={{ color: 'var(--ink-soft)', fontSize: 13 }}>
              Not available yet — visible once the order is paid.
            </p>
          )}
        </div>
      </main>
    </>
  )
}

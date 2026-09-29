import React from 'react'
import { useParams, Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, OrderResponse } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { StatusChip } from '../components/ui/StatusChip'
import { TestOrderChip } from '../components/ui/TestOrderChip'

/**
 * The whole point of this page is to let an admin actually see an order
 * (items, address) before shipping it; the transition buttons stay on
 * AdminPage's table. The one action here is voiding a test order (VoidPanel),
 * which belongs next to the order's details because the admin should be
 * looking at exactly what they are voiding. shippingAddress is rendered whenever the API
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
          {order.testOrder && <TestOrderChip />}
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
          {(order.deliveryFees ?? []).map((f, i) => (
            <div key={`fee-${i}`} style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 0', borderBottom: '1px solid var(--line)' }}>
              <p style={{ fontSize: 14, color: 'var(--ink-soft)' }}>Delivery: {f.vendorName}</p>
              <p className="num" style={{ fontSize: 14 }}>R{Number(f.fee).toFixed(2)}</p>
            </div>
          ))}
          <div style={{ display: 'flex', justifyContent: 'flex-end', paddingTop: 8 }}>
            <span style={{ fontWeight: 700, fontSize: 18 }}>
              Total: <span className="num">R{Number(order.total).toFixed(2)}</span>
            </span>
          </div>
        </div>

        {order.trackingNumber && (
          <div style={{
            marginTop: 24, display: 'flex', alignItems: 'baseline', gap: 8,
            background: 'var(--sun-tint)', border: '1px solid var(--sun)',
            borderRadius: 'var(--r-sm)', padding: '10px 14px',
          }}>
            <span style={{ fontSize: 13, color: 'var(--ink-soft)' }}>Tracking number</span>
            <strong className="num" style={{ fontSize: 15 }}>{order.trackingNumber}</strong>
          </div>
        )}

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
              Not available yet; visible once the order is paid.
            </p>
          )}
        </div>

        <VoidPanel orderId={order.id} status={order.status} />
      </main>
    </>
  )
}

const VOIDABLE = ['PAID', 'SHIPPED', 'DELIVERED']

/**
 * Voiding a test-mode order: its payment moved no money, so it must stop
 * counting as a sale (see OrderService.voidTestOrder for what that undoes).
 *
 * Shown only while payments run in test mode, for the same reason the API
 * refuses otherwise: with live payments an order may hold real money, and
 * the answer to a real order is a refund. The server enforces every rule;
 * hiding the panel just keeps a button off the page that would only 409.
 */
function VoidPanel({ orderId, status }: { orderId: number; status: string }) {
  const qc = useQueryClient()
  const [reason, setReason] = React.useState('')
  const [error, setError] = React.useState<string>()
  const [result, setResult] = React.useState<VoidResult>()

  const { data: health } = useQuery<{ mode: string }>({
    queryKey: ['payments-health'],
    queryFn: () => api('/api/v1/payments/health', { auth: false }),
    staleTime: 60_000,
  })

  const voidOrder = useMutation({
    mutationFn: () => api<VoidResult>(`/api/v1/admin/orders/${orderId}/void`, {
      method: 'POST', body: { reason: reason.trim() },
    }),
    onSuccess: r => {
      setResult(r)
      qc.invalidateQueries({ queryKey: ['admin-order', String(orderId)] })
      qc.invalidateQueries({ queryKey: ['admin-orders'] })
    },
    onError: e => setError(e instanceof ApiError ? e.detail || e.title : 'Could not void this order'),
  })

  // After a void the order reloads as CANCELLED, which would hide this panel
  // and with it any warning. Keep the result on screen instead.
  if (result) {
    return (
      <section style={panel}>
        <p style={{ fontWeight: 700, marginBottom: 6 }}>Voided. This order no longer counts as a sale.</p>
        <p style={{ fontSize: 13, color: 'var(--ink-soft)', lineHeight: 1.6 }}>
          Stock is back, the vendor's payout entry is cancelled, and the reason is in the
          order history. No email was sent to anyone.
        </p>
        {result.warnings.map(w => (
          <p key={w} style={{ ...warning, marginTop: 10 }}>{w}</p>
        ))}
      </section>
    )
  }

  if (health?.mode !== 'test' || !VOIDABLE.includes(status)) return null

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(undefined)
    if (!window.confirm(
      'Void this order as a test order? It will be cancelled, its stock returned, and the '
      + 'vendor\'s payout entry cancelled. This cannot be undone.',
    )) return
    voidOrder.mutate()
  }

  return (
    <section style={panel}>
      <h2 style={{ fontSize: 15, fontWeight: 700, marginBottom: 6 }}>Test order?</h2>
      <p style={{ fontSize: 13, color: 'var(--ink-soft)', lineHeight: 1.6, marginBottom: 12 }}>
        Payments are in test mode, so this order's payment moved no money. Voiding it cancels
        the order, returns its stock, cancels the vendor's payout entry and removes it from
        the product's sales count. No one is emailed.
      </p>
      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
          Reason (kept in the order history)
          <input required maxLength={300} value={reason} onChange={e => setReason(e.target.value)}
            placeholder="e.g. My own checkout test"
            style={{
              padding: '9px 12px', border: '1.5px solid var(--line)', borderRadius: 'var(--r-sm)',
              fontSize: 14, background: 'var(--card)', color: 'var(--ink)',
            }} />
        </label>
        {error && <p style={warning}>{error}</p>}
        <button type="submit" disabled={voidOrder.isPending || !reason.trim()} style={{
          alignSelf: 'flex-start', padding: '9px 18px', borderRadius: 'var(--r-sm)',
          border: '1.5px solid var(--clay)', background: 'var(--card)', color: 'var(--clay)',
          fontWeight: 700, fontSize: 13, cursor: 'pointer', minHeight: 40,
          opacity: voidOrder.isPending || !reason.trim() ? 0.6 : 1,
        }}>
          {voidOrder.isPending ? 'Voiding…' : 'Void test order'}
        </button>
      </form>
    </section>
  )
}

interface VoidResult {
  orderId: number
  status: string
  warnings: string[]
}

const panel: React.CSSProperties = {
  marginTop: 32, padding: 18, borderRadius: 'var(--r)',
  border: '1px solid var(--line)', background: 'var(--card)',
}

const warning: React.CSSProperties = {
  fontSize: 13, color: 'var(--clay)', background: 'var(--clay-tint)',
  padding: '8px 12px', borderRadius: 'var(--r-sm)', lineHeight: 1.5,
}

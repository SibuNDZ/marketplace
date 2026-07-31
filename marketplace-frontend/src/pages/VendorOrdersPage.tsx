import React from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, Page, VendorOrderResponse } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { StatusChip } from '../components/ui/StatusChip'

/**
 * Orders containing this vendor's items, PAID onward (the backend never
 * returns earlier states to vendors). Each card shows only the vendor's own
 * line items and the dispatch address; "Mark as shipped" appears only when
 * the server says canShip (PAID and single-vendor) — mixed orders show an
 * explanatory note instead of a button that would be rejected.
 */
export function VendorOrdersPage() {
  const qc = useQueryClient()

  const { data, isLoading } = useQuery<Page<VendorOrderResponse>>({
    queryKey: ['vendor-orders'],
    queryFn: () => api('/api/v1/vendor/orders?size=50'),
  })

  const markShipped = useMutation({
    mutationFn: (orderId: number) =>
      api(`/api/v1/vendor/orders/${orderId}/ship`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['vendor-orders'] }),
  })

  const orders = data?.content ?? []

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28 }}>Orders to fulfil</h1>
          <Link to="/vendor" style={{ fontSize: 13, color: 'var(--trust-blue)' }}>← Your stall</Link>
        </div>

        {isLoading ? <p>Loading…</p> : orders.length === 0 ? (
          <p style={{ color: 'var(--ink-soft)' }}>
            No paid orders with your items yet. Orders appear here the moment a customer pays.
          </p>
        ) : (
          <div style={{ display: 'grid', gap: 16 }}>
            {orders.map(o => (
              <section key={o.orderId} style={{
                border: '1px solid var(--line)', borderRadius: 'var(--r-md)', padding: 20,
              }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
                  <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
                    <strong style={{ fontSize: 15 }}>{o.orderNumber}</strong>
                    <span style={{ fontSize: 12, color: 'var(--ink-soft)' }}>
                      {new Date(o.createdAt).toLocaleDateString()}
                    </span>
                  </div>
                  <StatusChip status={o.status} />
                </div>

                <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 12 }}>
                  <thead>
                    <tr style={{ textAlign: 'left', borderBottom: '1px solid var(--line)' }}>
                      {['Your item', 'Qty', 'Unit', 'Line total'].map(h => (
                        <th key={h} style={{ padding: '6px 12px 6px 0', fontSize: 12, fontWeight: 600, color: 'var(--ink-soft)' }}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {o.items.map((it, idx) => (
                      <tr key={idx} style={{ borderBottom: '1px solid var(--line)' }}>
                        <td style={{ padding: '8px 12px 8px 0', fontWeight: 600, fontSize: 14 }}>{it.productName}</td>
                        <td className="num" style={{ padding: '8px 12px 8px 0' }}>{it.quantity}</td>
                        <td className="num" style={{ padding: '8px 12px 8px 0' }}>R{Number(it.unitPrice).toFixed(2)}</td>
                        <td className="num" style={{ padding: '8px 12px 8px 0' }}>R{Number(it.lineTotal).toFixed(2)}</td>
                      </tr>
                    ))}
                    <tr>
                      <td colSpan={3} style={{ padding: '8px 12px 8px 0', fontSize: 13, color: 'var(--ink-soft)', textAlign: 'right' }}>
                        Your items total
                      </td>
                      <td className="num" style={{ padding: '8px 12px 8px 0', fontWeight: 700 }}>
                        R{Number(o.itemsTotal).toFixed(2)}
                      </td>
                    </tr>
                    {o.deliveryFee != null && (
                      <tr>
                        <td colSpan={3} style={{ padding: '4px 12px 8px 0', fontSize: 13, color: 'var(--ink-soft)', textAlign: 'right' }}>
                          Your delivery fee
                        </td>
                        <td className="num" style={{ padding: '4px 12px 8px 0' }}>
                          R{Number(o.deliveryFee).toFixed(2)}
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>

                <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16 }}>
                  {o.shipTo ? (
                    <address style={{
                      fontStyle: 'normal', fontSize: 13, lineHeight: 1.6,
                      background: 'var(--paper-warm, #f7f7f5)', borderRadius: 'var(--r-sm)', padding: '10px 14px',
                    }}>
                      <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--ink-soft)', marginBottom: 4 }}>SHIP TO</div>
                      {o.shipTo.recipientName}<br />
                      {o.shipTo.addressLine1}<br />
                      {o.shipTo.addressLine2 && <>{o.shipTo.addressLine2}<br /></>}
                      {o.shipTo.city}, {o.shipTo.province} {o.shipTo.postalCode}<br />
                      {o.shipTo.phone}
                    </address>
                  ) : (
                    <span style={{ fontSize: 13, color: 'var(--clay)' }}>
                      No shipping address on file. Contact support before dispatching.
                    </span>
                  )}

                  {o.canShip ? (
                    <button
                      onClick={() => markShipped.mutate(o.orderId)}
                      disabled={markShipped.isPending}
                      style={{
                        padding: '9px 18px', background: 'var(--flame-gradient)', color: '#fff',
                        border: 'none', borderRadius: 'var(--r-sm)', fontWeight: 700, cursor: 'pointer',
                        opacity: markShipped.isPending ? 0.6 : 1,
                      }}>
                      {markShipped.isPending ? 'Marking…' : 'Mark as shipped'}
                    </button>
                  ) : o.status === 'PAID' ? (
                    <span style={{ fontSize: 12, color: 'var(--ink-soft)', maxWidth: 220, textAlign: 'right' }}>
                      This order also contains other vendors' items; eRestyu will arrange shipping.
                    </span>
                  ) : null}
                </div>

                {markShipped.isError && (
                  <p style={{ fontSize: 12, color: 'var(--clay)', marginTop: 8 }}>
                    Could not mark as shipped. Refresh and try again.
                  </p>
                )}
              </section>
            ))}
          </div>
        )}
      </main>
    </>
  )
}

import React from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, Page, AdminOrderSummary } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { StatusChip } from '../components/ui/StatusChip'

// Legal next transitions — the UI never offers what the state machine rejects.
const LEGAL: Record<string, string[]> = {
  PAID:      ['SHIPPED'],
  SHIPPED:   ['DELIVERED'],
  DELIVERED: ['REFUNDED'],
}

export function AdminPage() {
  const qc = useQueryClient()

  const { data, isLoading } = useQuery<Page<AdminOrderSummary>>({
    queryKey: ['admin-orders'],
    queryFn: () => api('/api/v1/admin/orders?sort=createdAt,desc&size=50'),
  })

  const transition = useMutation({
    mutationFn: ({ orderId, status, trackingNumber }: { orderId: number; status: string; trackingNumber?: string }) =>
      api(`/api/v1/admin/orders/${orderId}/status`, {
        method: 'POST',
        body: trackingNumber ? { status, trackingNumber } : { status },
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-orders'] }),
  })

  // SHIPPED is the one transition that can carry a waybill number (manual
  // interim; courier APIs deferred). prompt() matches the page's existing
  // native-dialog style; cancelling the dialog aborts the transition.
  const runTransition = (orderId: number, status: string) => {
    if (status === 'SHIPPED') {
      const trackingNumber = prompt('Tracking number (optional, leave blank for none):')
      if (trackingNumber === null) return
      transition.mutate({ orderId, status, trackingNumber: trackingNumber.trim() || undefined })
    } else {
      transition.mutate({ orderId, status })
    }
  }

  const orders = data?.content ?? []

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 28 }}>
          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28 }}>Admin</h1>
          <nav style={{ display: 'flex', gap: 2 }} aria-label="Admin sections">
            <span style={{ padding: '6px 14px', fontSize: 13, fontWeight: 700, color: 'var(--aloe)', borderBottom: '2px solid var(--aloe)' }}>Orders</span>
            <Link to="/admin/feedback" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Feedback</Link>
            <Link to="/admin/payouts" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Payouts</Link>
          </nav>
        </div>
        {isLoading ? <p>Loading…</p> : (
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ textAlign: 'left', borderBottom: '1px solid var(--line)' }}>
                {['Order', 'Customer', 'Total', 'Status', 'Actions'].map(h => (
                  <th key={h} style={{ padding: '10px 12px', fontSize: 12, fontWeight: 600, color: 'var(--ink-soft)' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {orders.map(o => (
                <tr key={o.id} style={{ borderBottom: '1px solid var(--line)' }}>
                  <td className="num" style={{ padding: '14px 12px', fontWeight: 700 }}>
                    <Link to={`/admin/orders/${o.id}`} style={{ color: 'inherit' }}>{o.orderNumber}</Link>
                  </td>
                  <td style={{ padding: '14px 12px', color: 'var(--ink-soft)', fontSize: 13 }}>{o.customerEmail}</td>
                  <td className="num" style={{ padding: '14px 12px' }}>R{Number(o.total).toFixed(2)}</td>
                  <td style={{ padding: '14px 12px' }}><StatusChip status={o.status} /></td>
                  <td style={{ padding: '14px 12px', display: 'flex', gap: 8 }}>
                    {(LEGAL[o.status] ?? []).map(next => (
                      <button key={next} onClick={() => runTransition(o.id, next)}
                        style={{
                          padding: '6px 14px', background: 'var(--aloe-tint)', color: 'var(--aloe-deep)',
                          border: '1px solid var(--aloe)', borderRadius: 'var(--r-sm)', fontWeight: 600, fontSize: 12,
                        }}>
                        → {next}
                      </button>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </main>
    </>
  )
}

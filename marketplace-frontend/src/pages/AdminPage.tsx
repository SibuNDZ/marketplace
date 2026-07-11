import React from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, Page, AdminOrderSummary } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
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
    mutationFn: ({ orderId, status }: { orderId: number; status: string }) =>
      api(`/api/v1/admin/orders/${orderId}/status`, { method: 'POST', body: { status } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-orders'] }),
  })

  const orders = data?.content ?? []

  return (
    <>
      <Topbar />
      <main className="page-shell">
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28, marginBottom: 28 }}>Orders</h1>
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
                  <td className="num" style={{ padding: '14px 12px', fontWeight: 700 }}>{o.orderNumber}</td>
                  <td style={{ padding: '14px 12px', color: 'var(--ink-soft)', fontSize: 13 }}>{o.customerEmail}</td>
                  <td className="num" style={{ padding: '14px 12px' }}>R{Number(o.total).toFixed(2)}</td>
                  <td style={{ padding: '14px 12px' }}><StatusChip status={o.status} /></td>
                  <td style={{ padding: '14px 12px', display: 'flex', gap: 8 }}>
                    {(LEGAL[o.status] ?? []).map(next => (
                      <button key={next} onClick={() => transition.mutate({ orderId: o.id, status: next })}
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

import React, { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, OrderResponse, ApiError } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { StatusChip } from '../components/ui/StatusChip'

export function OrderDetailPage() {
  const { id } = useParams()
  const qc = useQueryClient()
  const [cancelError, setCancelError] = useState<string>()

  const { data: order } = useQuery<OrderResponse>({
    queryKey: ['order', id],
    queryFn: () => api(`/api/v1/orders/${id}`),
    enabled: !!id,
  })

  const cancel = useMutation({
    mutationFn: () => api(`/api/v1/orders/${id}/cancel`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['order', id] }),
    onError: (e) => setCancelError(e instanceof ApiError ? e.detail : 'Could not cancel'),
  })

  if (!order) return <><Topbar /><div className="page-shell">Loading…</div></>

  return (
    <>
      <Topbar />
      <main className="page-shell">
        <Link to="/orders" style={{ fontSize: 13, color: 'var(--ink-soft)', display: 'inline-flex', alignItems: 'center', gap: 4, marginBottom: 24 }}>
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
        {order.status === 'PENDING' && (
          <div style={{ marginTop: 24 }}>
            {cancelError && <p style={{ color: 'var(--clay)', fontSize: 13, marginBottom: 8 }}>{cancelError}</p>}
            <button onClick={() => {
              if (confirm('Cancel this order? Stock will be released.')) cancel.mutate()
            }} disabled={cancel.isPending} style={{
              padding: '9px 20px', border: '1.5px solid var(--clay)', color: 'var(--clay)',
              borderRadius: 'var(--r-sm)', background: 'none', fontWeight: 600,
            }}>
              Cancel order
            </button>
          </div>
        )}
      </main>
    </>
  )
}

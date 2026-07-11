import React, { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, OrderResponse } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { StatusChip } from '../components/ui/StatusChip'

export function CheckoutSuccessPage() {
  const [params] = useSearchParams()
  const orderId = params.get('order')
  const [polled, setPolled] = useState(false)

  const { data: order } = useQuery<OrderResponse>({
    queryKey: ['order', orderId],
    queryFn: () => api(`/api/v1/orders/${orderId}`),
    enabled: !!orderId,
    // Poll once after 2 seconds — webhook may lag the redirect
    refetchInterval: !polled ? 2000 : false,
  })

  useEffect(() => {
    if (order?.status === 'PAID') setPolled(true)
    const t = setTimeout(() => setPolled(true), 6000) // give up after 6s
    return () => clearTimeout(t)
  }, [order?.status])

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail" style={{ textAlign: 'center', paddingTop: 80 }}>
        <div style={{ fontSize: 48, marginBottom: 16 }}>🎉</div>
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 800, fontSize: 30, marginBottom: 8 }}>Payment received!</h1>
        {order ? (
          <>
            <p style={{ color: 'var(--ink-soft)', marginBottom: 20 }}>Order <span className="num" style={{ fontWeight: 600 }}>{order.id}</span></p>
            {order.status === 'PENDING'
              ? <p style={{ color: 'var(--ink-soft)', fontSize: 14 }}>Confirming payment…</p>
              : <StatusChip status={order.status} />
            }
          </>
        ) : orderId ? (
          <p style={{ color: 'var(--ink-soft)' }}>Loading order…</p>
        ) : null}
        <div style={{ marginTop: 32, display: 'flex', gap: 12, justifyContent: 'center' }}>
          <Link to="/orders" style={{ padding: '10px 24px', background: 'var(--ink)', color: '#fff', borderRadius: 'var(--r-pill)', fontWeight: 600 }}>
            View orders
          </Link>
          <Link to="/" style={{ padding: '10px 24px', border: '1.5px solid var(--line)', borderRadius: 'var(--r-pill)', fontWeight: 600 }}>
            Continue shopping
          </Link>
        </div>
      </main>
    </>
  )
}

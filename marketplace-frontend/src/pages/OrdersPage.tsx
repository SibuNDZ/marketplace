import React from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, Page, OrderResponse } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { StatusChip } from '../components/ui/StatusChip'

export function OrdersPage() {
  const { data, isLoading } = useQuery<Page<OrderResponse>>({
    queryKey: ['orders'],
    queryFn: () => api('/api/v1/orders?sort=createdAt,desc'),
  })

  const orders = data?.content ?? []

  return (
    <>
      <Topbar />
      <main className="page-shell">
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28, marginBottom: 28 }}>Your orders</h1>
        {isLoading && <p style={{ color: 'var(--ink-soft)' }}>Loading…</p>}
        {!isLoading && orders.length === 0 && (
          <div style={{ textAlign: 'center', padding: '60px 0' }}>
            <p style={{ color: 'var(--ink-soft)', marginBottom: 16 }}>Nothing ordered yet</p>
            <Link to="/" style={{ padding: '10px 24px', background: 'var(--ink)', color: '#fff', borderRadius: 'var(--r-pill)', fontWeight: 600 }}>
              Browse products
            </Link>
          </div>
        )}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {orders.map(order => (
            <Link key={order.id} to={`/orders/${order.id}`} style={{
              background: 'var(--card)', borderRadius: 'var(--r)', padding: '18px 20px',
              boxShadow: 'var(--shadow)', display: 'flex', alignItems: 'center', gap: 16,
            }}>
              <div style={{ flex: 1 }}>
                <p style={{ fontWeight: 700 }} className="num">Order #{order.id}</p>
                <p style={{ fontSize: 13, color: 'var(--ink-soft)', marginTop: 2 }}>
                  {order.items.length} item{order.items.length !== 1 ? 's' : ''} · <span className="num">R{Number(order.total).toFixed(2)}</span>
                </p>
              </div>
              <StatusChip status={order.status} />
              <span style={{ color: 'var(--ink-soft)', fontSize: 18 }}>›</span>
            </Link>
          ))}
        </div>
      </main>
    </>
  )
}

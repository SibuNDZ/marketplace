import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, Page, OrderResponse } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { StatusChip } from '../components/ui/StatusChip'

/**
 * Tab keys match the backend OrderTab enum exactly. The status grouping
 * lives server-side (one tab covers two statuses), so this list only carries
 * labels — it never re-derives which statuses belong where.
 */
const TABS = [
  { key: 'ALL', label: 'All orders' },
  { key: 'UNPAID', label: 'Unpaid' },
  { key: 'PROCESSING', label: 'Processing' },
  { key: 'SHIPPED', label: 'Shipped' },
  { key: 'DELIVERED', label: 'Delivered' },
  { key: 'RETURNS', label: 'Returns & cancelled' },
] as const

type TabKey = typeof TABS[number]['key']

const EMPTY_COPY: Record<TabKey, string> = {
  ALL: 'Nothing ordered yet',
  UNPAID: 'No unpaid orders. Anything you have not paid for appears here.',
  PROCESSING: 'Nothing being prepared right now.',
  SHIPPED: 'Nothing on its way right now.',
  DELIVERED: 'No delivered orders yet.',
  RETURNS: 'No cancelled or refunded orders.',
}

export function OrdersPage() {
  const [tab, setTab] = useState<TabKey>('ALL')

  const { data, isLoading } = useQuery<Page<OrderResponse>>({
    queryKey: ['orders', tab],
    queryFn: () => api(`/api/v1/orders?sort=createdAt,desc${tab === 'ALL' ? '' : `&tab=${tab}`}`),
  })

  // Counts come from the server too, so a badge can never disagree with the
  // tab it labels.
  const { data: counts } = useQuery<Record<TabKey, number>>({
    queryKey: ['order-counts'],
    queryFn: () => api('/api/v1/orders/counts'),
  })

  const orders = data?.content ?? []
  const hasAnyOrders = (counts?.ALL ?? 0) > 0

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail">
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28, marginBottom: 20 }}>Your orders</h1>

        {/* The tab row is pointless before the first order, so it stays
            hidden until there is something to filter. */}
        {hasAnyOrders && (
          <div role="tablist" aria-label="Filter orders" style={{
            display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 24,
            borderBottom: '1px solid var(--line)', paddingBottom: 12,
          }}>
            {TABS.map(t => {
              const active = tab === t.key
              const count = counts?.[t.key]
              return (
                <button key={t.key} role="tab" aria-selected={active}
                  onClick={() => setTab(t.key)}
                  style={{
                    display: 'inline-flex', alignItems: 'center', gap: 6,
                    padding: '8px 14px', minHeight: 44, cursor: 'pointer',
                    borderRadius: 'var(--r-pill)', fontSize: 13, fontWeight: active ? 700 : 500,
                    border: `1px solid ${active ? 'var(--aloe)' : 'var(--line)'}`,
                    background: active ? 'var(--aloe-tint)' : 'transparent',
                    color: active ? 'var(--aloe-deep)' : 'var(--ink-soft)',
                  }}>
                  {t.label}
                  {count !== undefined && count > 0 && (
                    <span className="num" style={{
                      fontSize: 11, fontWeight: 700, padding: '1px 7px', borderRadius: 'var(--r-pill)',
                      background: active ? 'var(--aloe)' : 'var(--line)',
                      color: active ? '#fff' : 'var(--ink-soft)',
                    }}>{count}</span>
                  )}
                </button>
              )
            })}
          </div>
        )}

        {isLoading && <p style={{ color: 'var(--ink-soft)' }}>Loading…</p>}

        {!isLoading && orders.length === 0 && (
          <div style={{ textAlign: 'center', padding: '60px 0' }}>
            <p style={{ color: 'var(--ink-soft)', marginBottom: 16 }}>{EMPTY_COPY[tab]}</p>
            {/* Only the truly-empty case needs a way out to the catalogue;
                an empty filter just needs a different tab. */}
            {!hasAnyOrders && (
              <Link to="/" style={{ padding: '10px 24px', background: 'var(--ink)', color: 'var(--paper)', borderRadius: 'var(--r-pill)', fontWeight: 600 }}>
                Browse products
              </Link>
            )}
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
                  {order.trackingNumber && <> · tracking <span className="num">{order.trackingNumber}</span></>}
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

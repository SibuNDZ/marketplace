import React from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, Page, AdminOrderSummary, SellerSourcesResponse } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { StatusChip } from '../components/ui/StatusChip'
import { TestOrderChip } from '../components/ui/TestOrderChip'
import { REFERRAL_SOURCES } from '../data/referralSources'

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
            <Link to="/admin/listings" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Listings</Link>
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
                  <td style={{ padding: '14px 12px' }}>
                    <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
                      <StatusChip status={o.status} />{o.testOrder && <TestOrderChip />}
                    </span>
                  </td>
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
        <SellerSources />
      </main>
    </>
  )
}

/**
 * Where sellers say they came from. Below the orders table on purpose: it is
 * a monthly question, not a daily one, and orders are what this page is for.
 *
 * Renders nothing until there is at least one seller to count, so the page
 * does not carry an empty analytics box around.
 */
function SellerSources() {
  const { data } = useQuery<SellerSourcesResponse>({
    queryKey: ['admin-seller-sources'],
    queryFn: () => api('/api/v1/admin/sellers/sources'),
    staleTime: 5 * 60 * 1000,
  })

  if (!data || data.totalVendors === 0) return null

  const label = (v: string) =>
    REFERRAL_SOURCES.find(s => s.value === v)?.label ?? v

  return (
    <section style={{ marginTop: 40, maxWidth: 420 }}>
      <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 17, marginBottom: 4 }}>
        Where sellers came from
      </h2>
      {/* The unknown count is stated first and plainly. Every vendor who
          registered before the question existed is unknown, so for a while it
          is the biggest number here, and a reader who cannot see that would
          read a two-vendor channel as a trend. */}
      <p style={{ fontSize: 12.5, color: 'var(--ink-soft)', marginBottom: 12 }}>
        {data.answered} of {data.totalVendors} sellers answered
        {data.unknown > 0 && `, ${data.unknown} signed up before we asked`}
      </p>
      {data.sources.length === 0 ? (
        <p style={{ fontSize: 13, color: 'var(--ink-soft)' }}>No answers yet.</p>
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <tbody>
            {data.sources.map(s => (
              <tr key={s.source} style={{ borderBottom: '1px solid var(--line)' }}>
                <td style={{ padding: '8px 0', fontSize: 13.5 }}>{label(s.source)}</td>
                <td className="num" style={{ padding: '8px 0', textAlign: 'right', fontWeight: 700 }}>
                  {s.count}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data.otherLabels.length > 0 && (
        <p style={{ fontSize: 12.5, color: 'var(--ink-soft)', marginTop: 10, lineHeight: 1.6 }}>
          Written in under &ldquo;somewhere else&rdquo;: {data.otherLabels.join(', ')}
        </p>
      )}
    </section>
  )
}

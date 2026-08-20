import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  api, apiText, ApiError,
  VendorPendingGroup, PayoutBatchSummary,
} from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'

/**
 * The payout run: what is owed (grouped per vendor) -> approve a selection
 * into a batch -> download the bank file -> mark the batch paid with the
 * bank's reference.
 *
 * Money moves OUTSIDE this page: a human uploads the exported file to
 * NetBank and authorises it there. This page records that run truthfully and
 * refuses shortcuts (the server 409s mark-paid before export, re-paying,
 * negative sums) — the buttons here just surface those rules.
 *
 * Banking is masked to last 4 everywhere on this page. The one full-detail
 * artifact is the downloaded CSV, which goes straight to a file, never into
 * state or the DOM.
 */
export function AdminPayoutsPage() {
  const qc = useQueryClient()
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [error, setError] = useState<string | null>(null)

  const pending = useQuery<VendorPendingGroup[]>({
    queryKey: ['admin-payouts-pending'],
    queryFn: () => api('/api/v1/admin/payouts/pending'),
  })
  const batches = useQuery<PayoutBatchSummary[]>({
    queryKey: ['admin-payouts-batches'],
    queryFn: () => api('/api/v1/admin/payouts/batches'),
  })

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['admin-payouts-pending'] })
    qc.invalidateQueries({ queryKey: ['admin-payouts-batches'] })
  }
  const fail = (e: unknown) =>
    setError(e instanceof ApiError ? e.detail || e.title : 'Something went wrong')

  const approve = useMutation({
    mutationFn: (entryIds: number[]) =>
      api('/api/v1/admin/payouts/batches', { method: 'POST', body: { entryIds } }),
    onSuccess: () => { setSelected(new Set()); setError(null); refresh() },
    onError: fail,
  })

  const markPaid = useMutation({
    mutationFn: ({ batchId, paymentReference }: { batchId: number; paymentReference: string }) =>
      api(`/api/v1/admin/payouts/batches/${batchId}/paid`, {
        method: 'POST', body: { paymentReference },
      }),
    onSuccess: () => { setError(null); refresh() },
    onError: fail,
  })

  // The CSV goes straight from response to file via a transient object URL.
  const download = async (batchId: number) => {
    try {
      const csv = await apiText(`/api/v1/admin/payouts/batches/${batchId}/export`)
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }))
      const a = document.createElement('a')
      a.href = url
      a.download = `erestyu-payout-batch-${batchId}.csv`
      a.click()
      URL.revokeObjectURL(url)
      setError(null)
      refresh() // exportedAt moved
    } catch (e) {
      fail(e)
    }
  }

  const runMarkPaid = (batchId: number) => {
    const ref = prompt('Bank payment reference (from NetBank, after authorising the batch):')
    if (ref === null) return
    if (!ref.trim()) { setError('A payment reference is required to mark a batch paid.'); return }
    markPaid.mutate({ batchId, paymentReference: ref.trim() })
  }

  const toggle = (id: number) => setSelected(prev => {
    const next = new Set(prev)
    if (next.has(id)) next.delete(id); else next.add(id)
    return next
  })
  const toggleVendor = (group: VendorPendingGroup) => setSelected(prev => {
    const next = new Set(prev)
    const all = group.entries.every(e => next.has(e.id))
    group.entries.forEach(e => { if (all) next.delete(e.id); else next.add(e.id) })
    return next
  })

  const groups = pending.data ?? []
  const money = (v: string) => `R${Number(v).toFixed(2)}`

  const th: React.CSSProperties = { padding: '8px 10px', fontSize: 12, fontWeight: 600, color: 'var(--ink-soft)', textAlign: 'left' }
  const td: React.CSSProperties = { padding: '10px' }

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 28 }}>
          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28 }}>Admin</h1>
          <nav style={{ display: 'flex', gap: 2 }} aria-label="Admin sections">
            <Link to="/admin" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Orders</Link>
            <Link to="/admin/feedback" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Feedback</Link>
            <span style={{ padding: '6px 14px', fontSize: 13, fontWeight: 700, color: 'var(--aloe)', borderBottom: '2px solid var(--aloe)' }}>Payouts</span>
          </nav>
        </div>

        {error && (
          <p role="alert" style={{
            marginBottom: 20, padding: '10px 14px', borderRadius: 'var(--r-sm)',
            background: 'var(--danger-tint, #fdecec)', color: 'var(--danger, #b3261e)', fontSize: 13, fontWeight: 600,
          }}>
            {error}
          </p>
        )}

        {/* ── Owed ─────────────────────────────────────────────────── */}
        <section style={{ marginBottom: 40 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 12 }}>
            <h2 style={{ fontSize: 18, fontWeight: 700 }}>Owed to vendors</h2>
            <button
              onClick={() => approve.mutate([...selected])}
              disabled={selected.size === 0 || approve.isPending}
              style={{
                padding: '6px 14px', background: 'var(--aloe-tint)', color: 'var(--aloe-deep)',
                border: '1px solid var(--aloe)', borderRadius: 'var(--r-sm)', fontWeight: 600, fontSize: 12,
                opacity: selected.size === 0 ? 0.5 : 1,
              }}>
              Approve {selected.size > 0 ? `${selected.size} ` : ''}into a batch
            </button>
          </div>

          {pending.isLoading ? <p>Loading…</p> : groups.length === 0 ? (
            <p style={{ color: 'var(--ink-soft)', fontSize: 14 }}>Nothing owed. Entries appear here the moment an order is paid.</p>
          ) : groups.map(group => (
            <div key={group.vendorId} style={{ border: '1px solid var(--line)', borderRadius: 'var(--r-md)', marginBottom: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px', borderBottom: '1px solid var(--line)' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 10, fontWeight: 700, cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={group.entries.every(e => selected.has(e.id))}
                    onChange={() => toggleVendor(group)}
                    aria-label={`Select all of ${group.displayName}'s entries`}
                  />
                  {group.displayName}
                </label>
                <span style={{ fontSize: 12, color: 'var(--ink-soft)' }}>
                  {group.banking.complete
                    ? `${group.banking.bankName} ${group.banking.accountNumberLast4}`
                    : 'Banking details incomplete — batch will not export'}
                </span>
                <span className="num" style={{ marginLeft: 'auto', fontWeight: 700 }}>{money(group.totalNet)}</span>
              </div>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid var(--line)' }}>
                    <th style={th}></th>
                    <th style={th}>Order</th>
                    <th style={th}>Kind</th>
                    <th style={th}>Items</th>
                    <th style={th}>Delivery</th>
                    <th style={th}>Commission</th>
                    <th style={th}>Net</th>
                  </tr>
                </thead>
                <tbody>
                  {group.entries.map(e => (
                    <tr key={e.id} style={{ borderBottom: '1px solid var(--line)', fontSize: 13 }}>
                      <td style={td}>
                        <input type="checkbox" checked={selected.has(e.id)} onChange={() => toggle(e.id)}
                          aria-label={`Select entry for order ${e.orderNumber}`} />
                      </td>
                      <td className="num" style={td}>{e.orderNumber}</td>
                      <td style={td}>{e.kind === 'ADJUSTMENT' ? `Adjustment${e.note ? ` — ${e.note}` : ''}` : 'Sale'}</td>
                      <td className="num" style={td}>{money(e.itemSubtotal)}</td>
                      <td className="num" style={td}>{money(e.deliveryFee)}</td>
                      <td className="num" style={td}>−{money(e.commissionAmount)}</td>
                      <td className="num" style={{ ...td, fontWeight: 700 }}>{money(e.netPayable)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
        </section>

        {/* ── Batches ──────────────────────────────────────────────── */}
        <section>
          <h2 style={{ fontSize: 18, fontWeight: 700, marginBottom: 12 }}>Batches</h2>
          {batches.isLoading ? <p>Loading…</p> : (batches.data ?? []).length === 0 ? (
            <p style={{ color: 'var(--ink-soft)', fontSize: 14 }}>No batches yet.</p>
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--line)' }}>
                  <th style={th}>Batch</th>
                  <th style={th}>State</th>
                  <th style={th}>Vendors</th>
                  <th style={th}>Entries</th>
                  <th style={th}>Total</th>
                  <th style={th}>Reference</th>
                  <th style={th}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {(batches.data ?? []).map(b => (
                  <tr key={b.id} style={{ borderBottom: '1px solid var(--line)', fontSize: 13 }}>
                    <td className="num" style={{ ...td, fontWeight: 700 }}>#{b.id}</td>
                    <td style={td}>{b.state}</td>
                    <td className="num" style={td}>{b.vendorCount}</td>
                    <td className="num" style={td}>{b.entryCount}</td>
                    <td className="num" style={td}>{money(b.totalNet)}</td>
                    <td style={td}>{b.paymentReference ?? '—'}</td>
                    <td style={{ ...td, display: 'flex', gap: 8 }}>
                      {b.state !== 'PAID' && (
                        <button onClick={() => download(b.id)}
                          style={{
                            padding: '6px 14px', background: 'var(--aloe-tint)', color: 'var(--aloe-deep)',
                            border: '1px solid var(--aloe)', borderRadius: 'var(--r-sm)', fontWeight: 600, fontSize: 12,
                          }}>
                          {b.state === 'EXPORTED' ? 'Re-download bank file' : 'Download bank file'}
                        </button>
                      )}
                      {b.state === 'EXPORTED' && (
                        <button onClick={() => runMarkPaid(b.id)}
                          style={{
                            padding: '6px 14px', background: 'var(--aloe-tint)', color: 'var(--aloe-deep)',
                            border: '1px solid var(--aloe)', borderRadius: 'var(--r-sm)', fontWeight: 600, fontSize: 12,
                          }}>
                          Mark paid
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </main>
    </>
  )
}

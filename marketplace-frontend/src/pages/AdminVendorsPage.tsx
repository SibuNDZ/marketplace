import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, ApprovedVendor, PendingVendor, SubaccountStatus } from '../lib/api'
import { imageUrlAt } from '../lib/productImage'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { useAuth } from '../context/AuthContext'

/**
 * Vendor verification review (paystack-subaccounts.md §4.3): every vendor
 * whose latest verification row is PENDING, with what they submitted and
 * their first listings, and two actions.
 *
 * Banking and identity arrive MASKED from the server (last four only);
 * this page has no way to show a full account or document number because
 * the response has no field for one. Approve is offered only when the
 * provider result allows it; the server enforces the same rule with a 409,
 * so the disabled button is a courtesy, not the control.
 *
 * In this slice the provider result is always NOT_RUN: the bank-account
 * validation call is not built yet, so approval rests on the admin's own
 * review of the holder name and the listings. The page says so.
 */
const DOCUMENT_LABEL: Record<string, string> = {
  ID_NUMBER: 'SA ID number',
  PASSPORT: 'Passport number',
  CIPC: 'CIPC registration',
}

const RESULT_LABEL: Record<string, string> = {
  NOT_RUN: 'Not checked (bank validation not yet live)',
  OK: 'Bank check passed',
  MISMATCH: 'Bank check: name mismatch',
  UNAVAILABLE: 'Bank check unavailable',
}

const approvable = (v: PendingVendor) => v.providerResult === 'OK' || v.providerResult === 'NOT_RUN'
const recheckable = (v: PendingVendor) => v.providerResult === 'MISMATCH' || v.providerResult === 'UNAVAILABLE'

const FLAGS: [keyof PendingVendor, string][] = [
  ['accountHolderMatch', 'holder matches'],
  ['accountOpen', 'open'],
  ['accountOpen3m', 'open 3+ months'],
  ['acceptsCredits', 'accepts credits'],
]

/** "holder matches ✓ · open ✓ · open 3+ months ✗", only for flags the provider returned. */
function flagLine(v: PendingVendor): string {
  return FLAGS
    .filter(([k]) => v[k] !== null && v[k] !== undefined)
    .map(([k, label]) => `${label} ${v[k] ? '✓' : '✗'}`)
    .join(' · ')
}

export function AdminVendorsPage() {
  const { user } = useAuth()
  const qc = useQueryClient()
  const [rejecting, setRejecting] = useState<number | null>(null)
  const [note, setNote] = useState('')
  const [error, setError] = useState<string>()

  const { data, isLoading } = useQuery<PendingVendor[]>({
    queryKey: ['admin-vendors-pending'],
    queryFn: () => api('/api/v1/admin/vendors/pending'),
    enabled: user?.role === 'ADMIN',
  })
  const approved = useQuery<ApprovedVendor[]>({
    queryKey: ['admin-vendors-approved'],
    queryFn: () => api('/api/v1/admin/vendors/approved'),
    enabled: user?.role === 'ADMIN',
  })
  // Subaccount status is read from Paystack only when the admin asks for
  // it, so this page never depends on Paystack being up.
  const [subStatus, setSubStatus] = useState<Record<number, SubaccountStatus | 'loading' | 'failed'>>({})
  const checkSubaccount = async (vendorId: number) => {
    setSubStatus(s => ({ ...s, [vendorId]: 'loading' }))
    try {
      const status = await api<SubaccountStatus>(`/api/v1/admin/vendors/${vendorId}/subaccount`)
      setSubStatus(s => ({ ...s, [vendorId]: status }))
    } catch {
      setSubStatus(s => ({ ...s, [vendorId]: 'failed' }))
    }
  }

  const done = () => {
    setError(undefined)
    setRejecting(null)
    setNote('')
    qc.invalidateQueries({ queryKey: ['admin-vendors-pending'] })
    qc.invalidateQueries({ queryKey: ['admin-vendors-approved'] })
  }
  const fail = (e: unknown) =>
    setError(e instanceof ApiError ? e.detail || e.title : 'That did not go through. Refresh and try again.')

  const approve = useMutation({
    mutationFn: (vendorId: number) => api(`/api/v1/admin/vendors/${vendorId}/approve`, { method: 'POST', body: {} }),
    onSuccess: done,
    onError: fail,
  })
  const reject = useMutation({
    mutationFn: ({ vendorId, note }: { vendorId: number; note: string }) =>
      api(`/api/v1/admin/vendors/${vendorId}/reject`, { method: 'POST', body: { note } }),
    onSuccess: done,
    onError: fail,
  })
  // Re-runs the provider check without the document number (the server
  // does not have it). A 409 means the provider refused and the vendor must
  // resubmit; the server's detail says so and is shown as is.
  const recheck = useMutation({
    mutationFn: (vendorId: number) => api(`/api/v1/admin/vendors/${vendorId}/recheck`, { method: 'POST', body: {} }),
    onSuccess: done,
    onError: fail,
  })

  const storeName = (v: PendingVendor) => v.businessName ?? v.email

  const confirmApprove = (v: PendingVendor) => {
    if (window.confirm(
      `Approve ${storeName(v)}?\n\n`
      + 'This records your approval of the bank account on file. Once the selling gate is on, '
      + 'approval is what lets buyers check out their items.',
    )) approve.mutate(v.vendorId)
  }

  const vendors = data ?? []
  const cell: React.CSSProperties = { padding: '10px 12px', fontSize: 13, verticalAlign: 'top' }
  const small: React.CSSProperties = { fontSize: 12, color: 'var(--ink-soft)' }
  const btn: React.CSSProperties = {
    fontSize: 12, fontWeight: 600, background: 'none', border: '1px solid var(--line)',
    borderRadius: 'var(--r-sm)', padding: '5px 12px', cursor: 'pointer',
  }

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 20 }}>
          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28 }}>Admin</h1>
          <nav style={{ display: 'flex', gap: 2 }} aria-label="Admin sections">
            <Link to="/admin" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Orders</Link>
            <Link to="/admin/feedback" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Feedback</Link>
            <Link to="/admin/payouts" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Payouts</Link>
            <Link to="/admin/listings" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Listings</Link>
            <span style={{ padding: '6px 14px', fontSize: 13, fontWeight: 700, color: 'var(--aloe)', borderBottom: '2px solid var(--aloe)' }}>Vendors</span>
          </nav>
        </div>

        {user && user.role !== 'ADMIN' ? (
          <p className="muted-copy">This page is for eRestyu admins.</p>
        ) : (
          <>
            <p style={{ ...small, maxWidth: 720, marginBottom: 16 }}>
              Sellers whose bank details are waiting for your review. Bank-account validation
              through the payment provider is not live yet, so each decision rests on the
              account holder name and the seller&rsquo;s listings. Account and document
              numbers are shown by their last four characters only.
            </p>

            {error && <p role="alert" style={{ color: 'var(--clay)', fontSize: 13, marginBottom: 12 }}>{error}</p>}

            {isLoading ? <p>Loading…</p> : vendors.length === 0 ? (
              <p className="muted-copy">No sellers are waiting for verification.</p>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 900 }}>
                  <thead>
                    <tr style={{ textAlign: 'left', borderBottom: '1px solid var(--line)' }}>
                      {['Store', 'Bank account', 'Identity', 'Listings', ''].map((h, i) => (
                        <th key={i} style={{ padding: '10px 12px', fontSize: 12, fontWeight: 600, color: 'var(--ink-soft)' }}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {vendors.map(v => (
                      <tr key={v.vendorId} style={{ borderBottom: '1px solid var(--line)' }}>
                        <td style={{ ...cell, fontWeight: 600 }}>
                          <Link to={`/shop/${v.vendorId}`} style={{ color: 'var(--ink)' }}>{storeName(v)}</Link>
                          <div style={{ ...small, fontWeight: 400 }}>{v.email}</div>
                          <div style={{ ...small, fontWeight: 400, whiteSpace: 'nowrap' }}>
                            Submitted {new Date(v.submittedAt).toLocaleDateString()}
                          </div>
                        </td>
                        <td style={cell}>
                          <div>{v.accountHolderName}</div>
                          <div style={small}>
                            {v.bankName ?? 'Bank not named'}{v.bankCode ? ` (${v.bankCode})` : ''}
                            {v.accountLast4 ? ` ${v.accountLast4}` : ''}
                            {v.accountType ? ` · ${v.accountType === 'CHEQUE' ? 'Cheque' : 'Savings'}` : ''}
                          </div>
                          <div style={{ ...small, color: v.providerResult === 'OK' ? 'var(--aloe)' : v.providerResult === 'NOT_RUN' ? 'var(--ink-soft)' : 'var(--clay)' }}>
                            {RESULT_LABEL[v.providerResult] ?? v.providerResult}
                            {v.verificationMessage ? ` · ${v.verificationMessage}` : ''}
                          </div>
                          {flagLine(v) && <div style={small}>{flagLine(v)}</div>}
                        </td>
                        <td style={cell}>
                          {v.documentType
                            ? <>{DOCUMENT_LABEL[v.documentType] ?? v.documentType}<div style={small}>{v.documentLast4}</div></>
                            : <span style={small}>No document supplied</span>}
                        </td>
                        <td style={cell}>
                          {v.listings.length === 0
                            ? <span style={small}>No live listings yet</span>
                            : (
                              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                                {v.listings.map(l => (
                                  <Link key={l.id} to={`/products/${l.id}`} title={`${l.name} · R${Number(l.price).toFixed(2)}`}
                                    aria-label={l.name} style={{ display: 'block' }}>
                                    {l.imageUrl
                                      ? <img src={imageUrlAt(l.imageUrl, 120)} alt="" width={48} height={48}
                                          style={{ objectFit: 'cover', borderRadius: 'var(--r-sm)', display: 'block' }} />
                                      : <div aria-hidden style={{ width: 48, height: 48, borderRadius: 'var(--r-sm)', background: 'var(--line)' }} />}
                                  </Link>
                                ))}
                              </div>
                            )}
                        </td>
                        <td style={{ ...cell, textAlign: 'right', whiteSpace: 'nowrap' }}>
                          {rejecting === v.vendorId ? (
                            <form onSubmit={e => { e.preventDefault(); if (note.trim()) reject.mutate({ vendorId: v.vendorId, note: note.trim() }) }}
                              style={{ display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'flex-end' }}>
                              <input autoFocus required value={note} onChange={e => setNote(e.target.value)}
                                placeholder="Why (the seller is told this)" aria-label="Rejection note"
                                style={{ padding: '6px 9px', border: '1px solid var(--line)', borderRadius: 'var(--r-sm)', fontSize: 13, width: 240 }} />
                              <div style={{ display: 'flex', gap: 6 }}>
                                <button type="submit" disabled={reject.isPending || !note.trim()}
                                  style={{ ...btn, color: 'var(--clay)' }}>
                                  {reject.isPending ? 'Rejecting…' : 'Confirm reject'}
                                </button>
                                <button type="button" onClick={() => { setRejecting(null); setNote('') }} style={{ ...btn, color: 'var(--ink-soft)' }}>
                                  Cancel
                                </button>
                              </div>
                            </form>
                          ) : (
                            <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end' }}>
                              <button onClick={() => confirmApprove(v)}
                                disabled={!approvable(v) || (approve.isPending && approve.variables === v.vendorId)}
                                title={approvable(v) ? undefined : 'Cannot approve: the bank check did not pass'}
                                aria-label={`Approve ${storeName(v)}`}
                                style={{ ...btn, color: approvable(v) ? 'var(--aloe)' : 'var(--ink-soft)' }}>
                                {approve.isPending && approve.variables === v.vendorId ? 'Approving…' : 'Approve'}
                              </button>
                              {recheckable(v) && (
                                <button onClick={() => recheck.mutate(v.vendorId)}
                                  disabled={recheck.isPending && recheck.variables === v.vendorId}
                                  aria-label={`Re-run check for ${storeName(v)}`}
                                  style={{ ...btn, color: 'var(--trust-blue)' }}>
                                  {recheck.isPending && recheck.variables === v.vendorId ? 'Checking…' : 'Re-run check'}
                                </button>
                              )}
                              <button onClick={() => { setRejecting(v.vendorId); setNote('') }}
                                aria-label={`Reject ${storeName(v)}`}
                                style={{ ...btn, color: 'var(--clay)' }}>
                                Reject
                              </button>
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 19, marginTop: 32, marginBottom: 8 }}>Approved sellers</h2>
            <p style={{ ...small, maxWidth: 720, marginBottom: 12 }}>
              Subaccount status is fetched from the payment provider only when you ask for it. A new
              or updated subaccount holds its first payout until you verify it in the Paystack dashboard.
            </p>
            {(approved.data ?? []).length === 0 ? (
              <p className="muted-copy">No approved sellers yet.</p>
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 700 }}>
                <thead>
                  <tr style={{ textAlign: 'left', borderBottom: '1px solid var(--line)' }}>
                    {['Store', 'Approved', 'Subaccount', ''].map((h, i) => (
                      <th key={i} style={{ padding: '10px 12px', fontSize: 12, fontWeight: 600, color: 'var(--ink-soft)' }}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {(approved.data ?? []).map(v => {
                    const s = subStatus[v.vendorId]
                    return (
                      <tr key={v.vendorId} style={{ borderBottom: '1px solid var(--line)' }}>
                        <td style={{ ...cell, fontWeight: 600 }}>
                          <Link to={`/shop/${v.vendorId}`} style={{ color: 'var(--ink)' }}>{v.businessName ?? v.email}</Link>
                          <div style={{ ...small, fontWeight: 400 }}>{v.email}</div>
                        </td>
                        <td style={{ ...cell, whiteSpace: 'nowrap' }}>{new Date(v.approvedAt).toLocaleDateString()}</td>
                        <td style={cell}>
                          {v.subaccountCode ? <span className="num">{v.subaccountCode}</span> : <span style={small}>No subaccount</span>}
                          {s && s !== 'loading' && s !== 'failed' && (
                            <div style={{ ...small, color: s.isVerified ? 'var(--aloe)' : s.splitEnabled ? 'var(--clay)' : 'var(--ink-soft)' }}>
                              {s.message}
                            </div>
                          )}
                          {s === 'failed' && <div style={{ ...small, color: 'var(--clay)' }}>Could not reach the payment provider. Try again.</div>}
                        </td>
                        <td style={{ ...cell, textAlign: 'right' }}>
                          <button onClick={() => checkSubaccount(v.vendorId)} disabled={s === 'loading'}
                            aria-label={`Check subaccount for ${v.businessName ?? v.email}`}
                            style={{ ...btn, color: 'var(--trust-blue)' }}>
                            {s === 'loading' ? 'Checking…' : 'Check status'}
                          </button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            )}
          </>
        )}
      </main>
    </>
  )
}

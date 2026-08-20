import React, { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, PayoutSettingsStatus } from '../../lib/api'

/**
 * Payout onboarding on the vendor dashboard: banking details + terms
 * acceptance, with an honest status line.
 *
 * Banking comes BACK masked (last 4) — the form fields are write-only from
 * the vendor's side, which is why editing always starts from blank inputs
 * rather than pre-filling values the server deliberately does not return.
 *
 * The terms sentence is rendered verbatim from the server (the same config
 * the ledger charges from), never re-worded here: the vendor accepts the
 * text the system runs on, not a paraphrase. Acceptance echoes the version
 * number shown; the server refuses a stale one.
 */
export function PayoutSetupCard() {
  const qc = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState({
    accountHolderName: '', bankName: '', accountNumber: '', branchCode: '',
    accountType: 'CHEQUE' as 'CHEQUE' | 'SAVINGS',
  })

  const { data: status } = useQuery<PayoutSettingsStatus>({
    queryKey: ['vendor-payout-settings'],
    queryFn: () => api('/api/v1/vendor/settings/payouts'),
  })

  const done = () => {
    qc.invalidateQueries({ queryKey: ['vendor-payout-settings'] })
    setError(null)
  }
  const fail = (e: unknown) =>
    setError(e instanceof ApiError ? e.detail || e.title : 'Something went wrong')

  const saveBanking = useMutation({
    mutationFn: () => api('/api/v1/vendor/settings/payouts/banking', { method: 'PUT', body: form }),
    onSuccess: () => { setEditing(false); done() },
    onError: fail,
  })
  const acceptTerms = useMutation({
    mutationFn: () => api('/api/v1/vendor/settings/payouts/accept-terms', {
      method: 'POST', body: { version: status!.termsVersion },
    }),
    onSuccess: done,
    onError: fail,
  })

  if (!status) return null

  const input: React.CSSProperties = {
    padding: '7px 9px', border: '1px solid var(--line)', borderRadius: 4, fontSize: 13,
  }
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  return (
    <section aria-label="Payout setup" style={{
      border: '1px solid var(--line)', borderRadius: 'var(--r-sm)',
      padding: '14px 16px', marginBottom: 20,
    }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 10 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700 }}>Getting paid</h2>
        {status.gateEnabled && !status.sellable && (
          <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--clay)' }}>
            Buyers cannot check out your items until this is complete.
          </span>
        )}
      </div>

      {error && (
        <p role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--clay)', marginBottom: 10 }}>
          {error}
        </p>
      )}

      {/* ── banking ── */}
      <div style={{ marginBottom: 14 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>Bank account</span>
          <span style={{ fontSize: 13, color: 'var(--ink-soft)' }}>
            {status.banking.complete
              ? `${status.banking.bankName} ${status.banking.accountNumberLast4}`
              : 'Not captured yet'}
          </span>
          {!editing && (
            <button onClick={() => setEditing(true)} style={{
              fontSize: 12, color: 'var(--trust-blue)', background: 'none', border: 'none', cursor: 'pointer',
            }}>
              {status.banking.complete ? 'Change' : 'Add details'}
            </button>
          )}
        </div>

        {editing && (
          <form
            onSubmit={e => { e.preventDefault(); saveBanking.mutate() }}
            style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 10 }}
          >
            <input required maxLength={120} placeholder="Account holder name" aria-label="Account holder name"
              style={{ ...input, flex: '1 1 180px' }} value={form.accountHolderName} onChange={set('accountHolderName')} />
            <input required maxLength={80} placeholder="Bank" aria-label="Bank name"
              style={{ ...input, flex: '1 1 120px' }} value={form.bankName} onChange={set('bankName')} />
            <input required pattern="\d{6,20}" inputMode="numeric" placeholder="Account number" aria-label="Account number"
              style={{ ...input, flex: '1 1 140px' }} value={form.accountNumber} onChange={set('accountNumber')} />
            <input required pattern="\d{6}" inputMode="numeric" placeholder="Branch code" aria-label="Branch code"
              style={{ ...input, width: 110 }} value={form.branchCode} onChange={set('branchCode')} />
            <select aria-label="Account type" style={input} value={form.accountType} onChange={set('accountType')}>
              <option value="CHEQUE">Cheque / current</option>
              <option value="SAVINGS">Savings</option>
            </select>
            <button type="submit" disabled={saveBanking.isPending} style={{
              padding: '7px 16px', background: 'var(--aloe)', color: '#fff', border: 'none',
              borderRadius: 4, fontWeight: 600, fontSize: 13, cursor: 'pointer',
            }}>
              {saveBanking.isPending ? 'Saving…' : 'Save'}
            </button>
            <button type="button" onClick={() => setEditing(false)} style={{
              fontSize: 12, color: 'var(--ink-soft)', background: 'none', border: 'none', cursor: 'pointer',
            }}>
              Cancel
            </button>
          </form>
        )}
      </div>

      {/* ── terms ── */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>Payout terms</span>
          {status.termsCurrent ? (
            <span style={{ fontSize: 12, color: 'var(--aloe)' }}>
              Accepted{status.acceptedAt ? ` on ${new Date(status.acceptedAt).toLocaleDateString()}` : ''}
            </span>
          ) : status.acceptedVersion != null ? (
            <span style={{ fontSize: 12, color: 'var(--clay)' }}>The terms have changed. Please review and accept again.</span>
          ) : null}
        </div>
        <p style={{ fontSize: 13, color: 'var(--ink-soft)', maxWidth: 640, marginBottom: 8 }}>
          {status.termsText}
        </p>
        {!status.termsCurrent && (
          <button onClick={() => acceptTerms.mutate()} disabled={acceptTerms.isPending} style={{
            padding: '7px 16px', background: 'var(--aloe)', color: '#fff', border: 'none',
            borderRadius: 4, fontWeight: 600, fontSize: 13, cursor: 'pointer',
          }}>
            {acceptTerms.isPending ? 'Saving…' : 'I accept these terms'}
          </button>
        )}
      </div>
    </section>
  )
}

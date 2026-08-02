import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, Page } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'

interface FeedbackSummary {
  id: number
  userEmail: string
  category: string
  message: string
  status: 'NEW' | 'REVIEWED'
  createdAt: string
}

const CATEGORY_LABELS: Record<string, string> = {
  BUG: '🐛 Bug', IDEA: '💡 Idea', COMPLAINT: '⚠️ Complaint', PRAISE: '👍 Praise', OTHER: 'Other',
}

/** The operator's feedback inbox: list, status filter, mark reviewed. */
export function AdminFeedbackPage() {
  const qc = useQueryClient()
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'NEW' | 'REVIEWED'>('NEW')

  const { data, isLoading } = useQuery<Page<FeedbackSummary>>({
    queryKey: ['admin-feedback', statusFilter],
    queryFn: () => api(`/api/v1/admin/feedback?size=50${statusFilter === 'ALL' ? '' : `&status=${statusFilter}`}`),
  })

  const markReviewed = useMutation({
    mutationFn: (id: number) => api(`/api/v1/admin/feedback/${id}/reviewed`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-feedback'] }),
  })

  const items = data?.content ?? []

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 20 }}>
          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28 }}>Admin</h1>
          <nav style={{ display: 'flex', gap: 2, borderBottom: 'none' }} aria-label="Admin sections">
            <Link to="/admin" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Orders</Link>
            <span style={{ padding: '6px 14px', fontSize: 13, fontWeight: 700, color: 'var(--aloe)', borderBottom: '2px solid var(--aloe)' }}>Feedback</span>
          </nav>
        </div>

        <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
          {(['NEW', 'REVIEWED', 'ALL'] as const).map(s => (
            <button key={s} onClick={() => setStatusFilter(s)} style={{
              padding: '6px 14px', fontSize: 12, fontWeight: 600, borderRadius: 'var(--r-pill)',
              border: `1px solid ${statusFilter === s ? 'var(--aloe)' : 'var(--line)'}`,
              background: statusFilter === s ? 'var(--aloe-tint)' : 'none',
              color: statusFilter === s ? 'var(--aloe-deep)' : 'var(--ink-soft)', cursor: 'pointer',
              minHeight: 34,
            }}>
              {s === 'ALL' ? 'All' : s === 'NEW' ? 'New' : 'Reviewed'}
            </button>
          ))}
        </div>

        {isLoading ? <p>Loading…</p> : items.length === 0 ? (
          <p style={{ color: 'var(--ink-soft)', fontSize: 14 }}>Nothing here.</p>
        ) : (
          <div style={{ display: 'grid', gap: 12 }}>
            {items.map(f => (
              <section key={f.id} style={{
                border: '1px solid var(--line)', borderRadius: 'var(--r-md)', padding: 16,
                opacity: f.status === 'REVIEWED' ? 0.65 : 1,
              }}>
                <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'baseline', gap: 10, marginBottom: 8 }}>
                  <span style={{ fontSize: 12, fontWeight: 700 }}>{CATEGORY_LABELS[f.category] ?? f.category}</span>
                  <span style={{ fontSize: 12, color: 'var(--ink-soft)' }}>{f.userEmail}</span>
                  <span className="num" style={{ fontSize: 11, color: 'var(--ink-soft)', marginLeft: 'auto' }}>
                    {new Date(f.createdAt).toLocaleString()}
                  </span>
                </div>
                <p style={{ fontSize: 14, lineHeight: 1.6, whiteSpace: 'pre-wrap', marginBottom: 10 }}>{f.message}</p>
                {f.status === 'NEW' && (
                  <button onClick={() => markReviewed.mutate(f.id)} disabled={markReviewed.isPending} style={{
                    padding: '6px 14px', fontSize: 12, fontWeight: 600, borderRadius: 'var(--r-sm)',
                    border: '1px solid var(--aloe)', background: 'var(--aloe-tint)', color: 'var(--aloe-deep)',
                    cursor: 'pointer', minHeight: 34,
                  }}>
                    Mark reviewed
                  </button>
                )}
              </section>
            ))}
          </div>
        )}
      </main>
    </>
  )
}

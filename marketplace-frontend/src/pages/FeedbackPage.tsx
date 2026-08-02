import React, { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api, ApiError } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'

const CATEGORIES = [
  { value: 'BUG', label: 'Something is broken' },
  { value: 'IDEA', label: 'An idea or suggestion' },
  { value: 'COMPLAINT', label: 'A complaint' },
  { value: 'PRAISE', label: 'Something works well' },
  { value: 'OTHER', label: 'Something else' },
] as const

/**
 * The private feedback channel: category + message, straight to the admin
 * inbox. The confirmation copy is literally true today (the inbox is read
 * by the founder); revise it if that ever stops being true. No response-SLA
 * promise because none exists.
 */
export function FeedbackPage() {
  const [category, setCategory] = useState<string>('IDEA')
  const [message, setMessage] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string>()

  const submit = useMutation({
    mutationFn: () =>
      api('/api/v1/feedback', { method: 'POST', body: { category, message } }),
    onSuccess: () => { setSent(true); setMessage(''); setError(undefined) },
    onError: (e) => setError(e instanceof ApiError ? e.detail : 'Could not send. Try again in a moment.'),
  })

  if (sent) {
    return (
      <>
        <Topbar />
        <main className="page-shell no-catrail" style={{ maxWidth: 560 }}>
          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28, marginBottom: 12 }}>
            Feedback sent
          </h1>
          <p style={{ fontSize: 15, lineHeight: 1.6, color: 'var(--ink-soft)', marginBottom: 20 }}>
            Thanks. This goes straight to the founder.
          </p>
          <button onClick={() => setSent(false)} style={{
            padding: '10px 20px', border: '1px solid var(--line)', background: 'none',
            borderRadius: 'var(--r-sm)', fontWeight: 600, cursor: 'pointer',
          }}>
            Send another
          </button>
        </main>
      </>
    )
  }

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail" style={{ maxWidth: 560 }}>
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28, marginBottom: 8 }}>
          Give feedback on eRestyu
        </h1>
        <p style={{ fontSize: 14, color: 'var(--ink-soft)', marginBottom: 24, lineHeight: 1.6 }}>
          Critique the platform: what is broken, confusing, missing, or working.
          This is private, between you and the people building eRestyu.
        </p>

        <form onSubmit={e => { e.preventDefault(); submit.mutate() }}
          style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
            What kind of feedback?
            <select value={category} onChange={e => setCategory(e.target.value)} style={{
              padding: '11px 12px', border: '1px solid var(--line)', borderRadius: 'var(--r-sm)',
              fontSize: 14, background: 'var(--card)',
            }}>
              {CATEGORIES.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
            </select>
          </label>

          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
            Your feedback
            <textarea required maxLength={2000} rows={6} value={message}
              onChange={e => setMessage(e.target.value)}
              placeholder="Say it plainly. Specific beats polite."
              style={{
                padding: '11px 12px', border: '1px solid var(--line)', borderRadius: 'var(--r-sm)',
                fontSize: 14, fontFamily: 'var(--body)', resize: 'vertical', background: 'var(--card)',
              }} />
            <span style={{ fontSize: 11, color: 'var(--ink-soft)', fontWeight: 400, textAlign: 'right' }} className="num">
              {message.length}/2000
            </span>
          </label>

          {error && <p style={{ fontSize: 13, color: 'var(--clay)' }}>{error}</p>}

          <button type="submit" disabled={submit.isPending || !message.trim()} style={{
            padding: '13px', background: 'var(--flame-gradient)', color: '#fff', border: 'none',
            borderRadius: 'var(--r-sm)', fontWeight: 700, fontSize: 15, cursor: 'pointer',
            opacity: submit.isPending || !message.trim() ? 0.6 : 1, minHeight: 44,
          }}>
            {submit.isPending ? 'Sending…' : 'Send feedback'}
          </button>
        </form>
      </main>
    </>
  )
}

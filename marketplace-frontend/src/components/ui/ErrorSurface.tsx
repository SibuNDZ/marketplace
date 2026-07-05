import React from 'react'
import { ApiError } from '../../lib/api'

interface Props {
  error: ApiError
  onDismiss?: () => void
}

// Renders the RFC 7807 error as the design specifies: title, detail,
// per-shortage rows in mono, requestId small at the bottom.
export function ErrorSurface({ error, onDismiss }: Props) {
  return (
    <div style={{
      background: 'var(--card)',
      border: '1px solid var(--clay)',
      borderRadius: 'var(--r)',
      padding: '16px 20px',
      boxShadow: 'var(--shadow-lift)',
      position: 'relative',
      maxWidth: 420,
    }}>
      {onDismiss && (
        <button onClick={onDismiss} style={{
          position: 'absolute', top: 12, right: 12,
          background: 'none', border: 'none', fontSize: 18, color: 'var(--ink-soft)',
          lineHeight: 1, padding: 4,
        }} aria-label="Dismiss">×</button>
      )}
      <p style={{ fontWeight: 600, color: 'var(--ink)', marginBottom: 4 }}>
        {error.title}
      </p>
      {error.detail && (
        <p style={{ fontSize: 13, color: 'var(--ink-soft)', marginBottom: error.shortages ? 12 : 0 }}>
          {error.detail}
        </p>
      )}
      {error.shortages && error.shortages.length > 0 && (
        <div style={{
          background: 'var(--clay-tint)', borderRadius: 'var(--r-sm)',
          padding: '8px 12px', marginBottom: 10,
        }}>
          {error.shortages.map((s) => (
            <p key={s.productId} className="num" style={{ fontSize: 12, color: 'var(--clay)', margin: '2px 0' }}>
              {s.productName} — wanted {s.requested}, only {s.available} available
            </p>
          ))}
        </div>
      )}
      {error.requestId && (
        <p className="num" style={{ fontSize: 11, color: 'var(--ink-soft)', marginTop: 4 }}>
          request id · {error.requestId}
        </p>
      )}
    </div>
  )
}

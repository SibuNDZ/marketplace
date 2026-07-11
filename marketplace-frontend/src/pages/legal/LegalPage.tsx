import React from 'react'
import { Topbar } from '../../components/layout/Topbar'

// Shared shell for legal/policy pages: display-face title, prose column,
// last-updated in mono. A future Returns Policy is a content file rendered
// through this, not a new page build.
interface Props {
  title: string
  lastUpdated: string       // ISO date, rendered verbatim in mono
  children: React.ReactNode
}

export function LegalPage({ title, lastUpdated, children }: Props) {
  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail" style={{ maxWidth: 780 }}>
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 800, fontSize: 34, letterSpacing: '-0.02em', marginBottom: 6 }}>
          {title}
        </h1>
        <p style={{ fontSize: 13, color: 'var(--ink-soft)', marginBottom: 8 }}>
          Last updated: <span className="num">{lastUpdated}</span>
        </p>
        <p style={{
          fontSize: 13, background: 'var(--sun-tint)', color: 'var(--sun-deep)',
          padding: '10px 14px', borderRadius: 'var(--r-sm)', marginBottom: 28,
        }}>
          This is a portfolio project, not a trading business. This page describes
          how the software actually behaves — every statement below is backed by
          the running system.
        </p>
        <div className="legal-prose">
          {children}
        </div>
      </main>
    </>
  )
}

/** Section heading + body, so content files stay terse. */
export function LegalSection({ heading, children }: { heading: string; children: React.ReactNode }) {
  return (
    <section style={{ marginBottom: 28 }}>
      <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 19, marginBottom: 10 }}>{heading}</h2>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontSize: 14.5, lineHeight: 1.65, color: 'var(--ink-soft)' }}>
        {children}
      </div>
    </section>
  )
}

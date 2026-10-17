import React from 'react'
import { SiteHeader as Topbar } from '../../components/layout/SiteHeader'

// Shared shell for legal/policy pages: display-face title, prose column,
// last-updated in mono. No banner: the operator is named in the page body
// (COMPANY in data/company.ts), and a reviewer should see the terms as terms. A future Returns Policy is a content file rendered
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
        <div className="legal-prose">
          {children}
        </div>
      </main>
    </>
  )
}

/** Stable fragment id from a heading, so a policy section can be linked directly (e.g. /terms#what-may-not-be-sold). */
export function sectionId(heading: string): string {
  return heading.toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
}

/** Section heading + body, so content files stay terse. */
export function LegalSection({ heading, children }: { heading: string; children: React.ReactNode }) {
  return (
    <section id={sectionId(heading)} style={{ marginBottom: 28 }}>
      <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 19, marginBottom: 10 }}>{heading}</h2>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontSize: 14.5, lineHeight: 1.65, color: 'var(--ink-soft)' }}>
        {children}
      </div>
    </section>
  )
}

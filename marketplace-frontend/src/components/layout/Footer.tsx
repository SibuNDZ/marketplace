import React from 'react'
import { Link } from 'react-router-dom'
import { PaymentMarks } from './PaymentMarks'
import { useSellerEntry } from '../../hooks/useSellerEntry'

// Footer rule: every item is a real destination. Topics we want but haven't
// written yet exist as pages that say "under construction" (Careers, Contact)
// rather than dead text; topics we don't need yet (Press, Support Local
// Farmers, app download, ...) are simply gone until they earn a page.
function LinkItem({ to, children }: { to: string; children: React.ReactNode }) {
  return <Link to={to} style={{ fontSize: 13, color: 'var(--footer-text)' }}>{children}</Link>
}

function Column({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <h3 style={{ fontSize: 13, fontWeight: 700, color: 'var(--footer-heading)', letterSpacing: '0.02em', marginBottom: 2 }}>
        {title}
      </h3>
      {children}
    </div>
  )
}

const SOCIALS = [
  { label: 'Instagram', glyph: 'IG' },
  { label: 'Facebook', glyph: 'FB' },
  { label: 'X (Twitter)', glyph: 'X' },
  { label: 'TikTok', glyph: 'TT' },
  { label: 'YouTube', glyph: 'YT' },
  { label: 'Pinterest', glyph: 'PN' },
  { label: 'LinkedIn', glyph: 'IN' },
]

export function Footer() {
  const sellerEntry = useSellerEntry()
  return (
    <footer className="site-footer" aria-label="Site footer" style={{ background: 'var(--footer-bg)', marginTop: 48, color: 'var(--footer-text)' }}>
      <div style={{ maxWidth: 'var(--content-max)', margin: '0 auto', padding: '48px var(--gutter) 32px' }}>

        {/* Main column grid */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
          gap: 32,
          paddingBottom: 36,
          borderBottom: '1px solid var(--footer-line)',
        }}>
          <Column title="About eRestyu">
            <LinkItem to="/about">About eRestyu</LinkItem>
            <LinkItem to="/careers">Careers</LinkItem>
            <LinkItem to="/contact">Contact Us</LinkItem>
          </Column>

          <Column title="Customer Service">
            <LinkItem to="/shipping">Shipping & Delivery</LinkItem>
            <LinkItem to="/returns">Returns & Cancellations</LinkItem>
            <LinkItem to="/help">Help Center & FAQ</LinkItem>
          </Column>

          <Column title="Sell on eRestyu">
            <LinkItem to="/how-it-works">How to Buy / How to Sell</LinkItem>
            {/* Same role-aware destination as the mobile seller strip: this
                used to send a signed-in seller to a signup form. */}
            {sellerEntry && <LinkItem to={sellerEntry.to}>{sellerEntry.label}</LinkItem>}
            <LinkItem to="/orders">Check Order Status</LinkItem>
          </Column>

          <Column title="Stay Connected">
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {SOCIALS.map(s => (
                <button key={s.label} aria-label={s.label} title={s.label} tabIndex={0} style={{
                  width: 28, height: 28, borderRadius: '50%',
                  border: '1px solid var(--footer-line)', background: 'transparent',
                  color: 'var(--footer-text)', fontSize: 10, fontWeight: 700,
                  transition: 'background 0.15s, color 0.15s',
                }}
                  onMouseEnter={e => { (e.currentTarget as HTMLButtonElement).style.background = 'var(--flame)'; (e.currentTarget as HTMLButtonElement).style.color = '#fff' }}
                  onMouseLeave={e => { (e.currentTarget as HTMLButtonElement).style.background = 'transparent'; (e.currentTarget as HTMLButtonElement).style.color = 'var(--footer-text)' }}
                >
                  {s.glyph}
                </button>
              ))}
            </div>
          </Column>
        </div>

        {/* Trust row — honest equivalents, not fabricated certifications.
            See Footer.tsx history / PR notes: no PCI DSS, POPIA-"compliant",
            B-BBEE level, or APWG badges are shown without an actual audit
            backing them — claiming those falsely is a real legal exposure
            (B-BBEE fronting is a criminal offence under the B-BBEE Act). */}
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 24, padding: '28px 0', borderBottom: '1px solid var(--footer-line)' }}>
          <div>
            <p style={{ fontSize: 11, fontWeight: 700, color: 'var(--footer-heading)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 10 }}>
              Trust & Security
            </p>
            <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
              {['🔒 Secure encrypted checkout', '🔐 Encrypted in transit', '📄 Your data under POPIA', '⚖️ Your rights under the CPA'].map(t => (
                <span key={t} style={{ fontSize: 12, color: 'var(--footer-text)' }}>{t}</span>
              ))}
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 24, padding: '24px 0', borderBottom: '1px solid var(--footer-line)' }}>
          <div>
            <p style={{ fontSize: 11, fontWeight: 700, color: 'var(--footer-heading)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 10 }}>
              We Accept
            </p>
            <PaymentMarks />
          </div>
        </div>

        {/* SA localization row */}
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: 16, padding: '20px 0' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{
              fontSize: 10, fontWeight: 800, letterSpacing: '0.03em',
              padding: '2px 7px', borderRadius: 4, background: 'rgba(31,107,74,0.25)', color: '#7FD9A8',
            }}>
              ZA
            </span>
            <span style={{ fontSize: 13 }}>Deliver to: South Africa</span>
          </div>
          <label style={{ fontSize: 13, display: 'flex', alignItems: 'center', gap: 8 }}>
            Language
            <select style={{
              background: 'transparent', color: 'var(--footer-text)', border: '1px solid var(--footer-line)',
              borderRadius: 6, padding: '5px 8px', fontSize: 13,
            }}>
              <option>English</option>
              <option>isiZulu</option>
              <option>Afrikaans</option>
              <option>isiXhosa</option>
            </select>
          </label>
        </div>

        {/* Legal bottom bar */}
        <div style={{
          borderTop: '1px solid var(--footer-line)', paddingTop: 20, marginTop: 4,
          display: 'flex', flexWrap: 'wrap', justifyContent: 'center', gap: 16, textAlign: 'center',
        }}>
          <span style={{ fontSize: 12, color: 'var(--footer-text-dim)' }}>© 2026 eRestyu. All rights reserved.</span>
          <Link to="/terms" style={{ fontSize: 12, color: 'var(--footer-text)' }}>Terms of Service</Link>
          <Link to="/privacy" style={{ fontSize: 12, color: 'var(--footer-text)' }}>Privacy Policy</Link>
        </div>
      </div>
    </footer>
  )
}

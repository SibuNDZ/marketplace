import React from 'react'
import { Link } from 'react-router-dom'

// Most items below (About, Careers, Returns Policy, Safety Center, Sitemap...)
// have no corresponding page in this app yet. Rendering them as real <a>/<Link>
// would be a dead click, so they're plain text. The few with a real
// destination (Start Selling, Check Order Status) are wired as links —
// swap more to Link as their pages get built.
function StaticItem({ children }: { children: React.ReactNode }) {
  return <span style={{ fontSize: 13, color: 'var(--footer-text)' }}>{children}</span>
}

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

const PAYMENT_METHODS = ['Visa', 'Mastercard', 'EFT', 'PayFlex', 'Ozow', 'Apple Pay', 'Google Pay', 'PayPal', 'Capitec Pay']

const APP_STORE_BENEFITS = [
  'Price-drop alerts',
  'Track orders any time',
  'Faster & more secure checkout',
  'Low stock item alerts',
  'Exclusive offers',
  'Coupons & offers alerts',
]

function StoreButton({ label, sub, icon }: { label: string; sub: string; icon: string }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 8,
      background: '#000', border: '1px solid var(--footer-line)', borderRadius: 8,
      padding: '7px 12px', flex: 1,
    }}>
      <span style={{ fontSize: 18, color: '#fff' }} aria-hidden>{icon}</span>
      <div style={{ lineHeight: 1.1 }}>
        <div style={{ fontSize: 9, color: 'rgba(255,255,255,0.7)' }}>{sub}</div>
        <div style={{ fontSize: 13, fontWeight: 700, color: '#fff' }}>{label}</div>
      </div>
    </div>
  )
}

export function Footer() {
  return (
    <footer aria-label="Site footer" style={{ background: 'var(--footer-bg)', marginTop: 48, color: 'var(--footer-text)' }}>
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
            <StaticItem>About eRestyu</StaticItem>
            <StaticItem>Careers</StaticItem>
            <StaticItem>Press</StaticItem>
            <StaticItem>Corporate Responsibility</StaticItem>
            <StaticItem>Affiliate & Influencer Program</StaticItem>
            <StaticItem>Support Local Farmers</StaticItem>
          </Column>

          <Column title="Customer Service">
            <StaticItem>Return & Refund Policy</StaticItem>
            <StaticItem>Shipping Info</StaticItem>
            <StaticItem>Price Adjustment Policy (30 days)</StaticItem>
            <StaticItem>Report Suspicious Activity</StaticItem>
            <StaticItem>Intellectual Property Policy</StaticItem>
            <StaticItem>Contact Us</StaticItem>
          </Column>

          <Column title="Help Center">
            <StaticItem>Support Center & FAQ</StaticItem>
            <StaticItem>Safety Center</StaticItem>
            <StaticItem>Purchase Protection</StaticItem>
            <StaticItem>How to Buy / How to Sell</StaticItem>
            <StaticItem>Sitemap</StaticItem>
            <StaticItem>Partner with Us</StaticItem>
          </Column>

          <Column title="Download the App eRestyu.com">
            <ul style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 5 }}>
              {APP_STORE_BENEFITS.map(b => (
                <li key={b} style={{ fontSize: 12.5, color: 'var(--footer-text)', display: 'flex', gap: 6 }}>
                  <span style={{ color: 'var(--aloe)' }}>✔</span>{b}
                </li>
              ))}
            </ul>
            <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
              <StoreButton label="App Store" sub="Download on the" icon="📱" />
              <StoreButton label="Google Play" sub="GET IT ON" icon="▶" />
            </div>
          </Column>

          <Column title="Sell eRestyu">
            <LinkItem to="/register">Start Selling</LinkItem>
            <StaticItem>Become a Verified Supplier</StaticItem>
            <LinkItem to="/orders">Check Order Status</LinkItem>
            <StaticItem>Partnerships</StaticItem>
            <div style={{ marginTop: 8 }}>
              <p style={{ fontSize: 12, fontWeight: 700, color: 'var(--footer-heading)', marginBottom: 8 }}>Stay Connected</p>
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
              {['🔒 Secure checkout via Stripe', '🔐 Encrypted in transit', '📄 Your data under POPIA', '⚖️ Your rights under the CPA'].map(t => (
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
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {PAYMENT_METHODS.map(p => (
                <span key={p} style={{
                  fontSize: 11, fontWeight: 600, color: 'var(--footer-text)',
                  border: '1px solid var(--footer-line)', borderRadius: 4, padding: '4px 9px',
                  display: 'inline-flex', alignItems: 'center', gap: 4,
                }}>
                  💳 {p}
                </span>
              ))}
            </div>
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
          {['Terms of Service', 'Privacy Policy', 'Cookie Policy', 'Accessibility'].map(t => (
            <span key={t} style={{ fontSize: 12, color: 'var(--footer-text-dim)' }}>{t}</span>
          ))}
        </div>
      </div>
    </footer>
  )
}

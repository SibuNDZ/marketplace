import React from 'react'

/**
 * Inline brand marks for the "We Accept" footer row. Each renders on a white
 * chip so the marks keep their brand colors against the dark footer, the way
 * card badges normally appear. Marks are simplified vectors and brand-colored
 * wordmarks, self-hosted (no external image requests, nothing to break
 * offline), sized to a common 22px-high chip row.
 */

function Chip({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <span role="img" aria-label={label} title={label} style={{
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      minWidth: 58, height: 30, padding: '0 10px',
      background: '#fff', borderRadius: 5, border: '1px solid rgba(0,0,0,0.12)',
    }}>
      {children}
    </span>
  )
}

const wordmark = (text: string, color: string, extra: React.CSSProperties = {}): React.ReactNode => (
  <span style={{ fontSize: 13, fontWeight: 800, color, letterSpacing: '-0.01em', lineHeight: 1, ...extra }}>
    {text}
  </span>
)

function Visa() {
  return <Chip label="Visa">{wordmark('VISA', '#1A1F71', { fontStyle: 'italic', letterSpacing: '0.02em' })}</Chip>
}

function Mastercard() {
  return (
    <Chip label="Mastercard">
      <svg width="34" height="21" viewBox="0 0 34 21" aria-hidden>
        <circle cx="13" cy="10.5" r="9" fill="#EB001B" />
        <circle cx="21" cy="10.5" r="9" fill="#F79E1B" />
        <path d="M17 3.6a9 9 0 0 1 0 13.8 9 9 0 0 1 0-13.8Z" fill="#FF5F00" />
      </svg>
    </Chip>
  )
}

function ApplePay() {
  return (
    <Chip label="Apple Pay">
      <svg width="14" height="16" viewBox="0 0 14 17" aria-hidden style={{ marginRight: 3 }}>
        <path fill="#000" d="M11.6 9c0-2 1.7-3 1.8-3.1-1-1.4-2.5-1.6-3-1.6-1.3-.2-2.5.7-3.2.7-.7 0-1.7-.7-2.8-.7C3 4.3 1.7 5.1 1 6.4c-1.4 2.4-.4 6 1 8 .7 1 1.5 2 2.5 2 1 0 1.4-.6 2.7-.6 1.2 0 1.6.6 2.7.6 1.1 0 1.8-1 2.5-2 .8-1.2 1.1-2.3 1.1-2.4 0 0-2-.8-2-3ZM9.6 2.8c.6-.7 1-1.7.9-2.7-.9 0-1.9.6-2.5 1.3-.6.6-1 1.6-.9 2.6 1 .1 2-.5 2.5-1.2Z" />
      </svg>
      {wordmark('Pay', '#000')}
    </Chip>
  )
}

function GooglePay() {
  return (
    <Chip label="Google Pay">
      <svg width="16" height="16" viewBox="0 0 48 48" aria-hidden style={{ marginRight: 3 }}>
        <path fill="#4285F4" d="M45.1 24.5c0-1.6-.1-3.1-.4-4.5H24v8.5h11.8c-.5 2.8-2 5.1-4.4 6.7v5.5h7.1c4.2-3.8 6.6-9.5 6.6-16.2Z" />
        <path fill="#34A853" d="M24 46c5.9 0 10.9-2 14.5-5.3l-7.1-5.5c-2 1.3-4.5 2.1-7.4 2.1-5.7 0-10.5-3.8-12.3-9H4.4v5.7C8 41.2 15.4 46 24 46Z" />
        <path fill="#FBBC05" d="M11.7 28.3c-.4-1.3-.7-2.8-.7-4.3s.3-3 .7-4.3V14H4.4C2.9 17 2 20.4 2 24s.9 7 2.4 10l7.3-5.7Z" />
        <path fill="#EA4335" d="M24 10.7c3.2 0 6.1 1.1 8.4 3.3l6.3-6.3C34.9 4.2 29.9 2 24 2 15.4 2 8 6.8 4.4 14l7.3 5.7c1.8-5.2 6.6-9 12.3-9Z" />
      </svg>
      {wordmark('Pay', '#3C4043')}
    </Chip>
  )
}

function PayPal() {
  return (
    <Chip label="PayPal">
      <span style={{ fontSize: 13, fontWeight: 800, fontStyle: 'italic', lineHeight: 1 }}>
        <span style={{ color: '#003087' }}>Pay</span>
        <span style={{ color: '#009CDE' }}>Pal</span>
      </span>
    </Chip>
  )
}

function Ozow() {
  return <Chip label="Ozow">{wordmark('Ozow', '#07B25D')}</Chip>
}

function Payflex() {
  return <Chip label="Payflex">{wordmark('payflex', '#C8017D', { fontWeight: 700 })}</Chip>
}

function CapitecPay() {
  return (
    <Chip label="Capitec Pay">
      <span style={{ fontSize: 12.5, fontWeight: 800, lineHeight: 1 }}>
        <span style={{ color: '#00486D' }}>Capitec</span>
        <span style={{ color: '#E32219' }}> Pay</span>
      </span>
    </Chip>
  )
}

function Eft() {
  return (
    <Chip label="EFT (bank transfer)">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#334155" strokeWidth="2" aria-hidden style={{ marginRight: 4 }}>
        <path d="M3 21h18M5 21V10m14 11V10M3 10l9-6 9 6H3Z" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      {wordmark('EFT', '#334155')}
    </Chip>
  )
}

/** Order follows rough usage frequency: cards, wallets, then local rails. */
export function PaymentMarks() {
  return (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
      <Visa />
      <Mastercard />
      <ApplePay />
      <GooglePay />
      <PayPal />
      <Ozow />
      <Payflex />
      <CapitecPay />
      <Eft />
    </div>
  )
}

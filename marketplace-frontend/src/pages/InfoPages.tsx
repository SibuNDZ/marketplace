import React from 'react'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { FAQ_ENTRIES } from '../data/faqContent'

/**
 * The footer's content pages, in one file: each is a short prose page on the
 * LegalPage shell pattern. House rule carried over from the footer: no dead
 * links, no invented claims. Pages describe what the running system actually
 * does; anything we don't have yet says "under construction" instead of
 * pretending.
 */

function InfoPage({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail" style={{ maxWidth: 780 }}>
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 800, fontSize: 34, letterSpacing: '-0.02em', marginBottom: 24 }}>
          {title}
        </h1>
        {children}
      </main>
    </>
  )
}

function Section({ heading, children }: { heading: string; children: React.ReactNode }) {
  return (
    <section style={{ marginBottom: 28 }}>
      <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 19, marginBottom: 10 }}>{heading}</h2>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontSize: 14.5, lineHeight: 1.65, color: 'var(--ink-soft)' }}>
        {children}
      </div>
    </section>
  )
}

function UnderConstruction({ note }: { note?: string }) {
  return (
    <div style={{
      background: 'var(--sun-tint)', border: '1px solid var(--sun)',
      borderRadius: 'var(--r-sm)', padding: '18px 20px', fontSize: 14.5, lineHeight: 1.6,
    }}>
      <p style={{ fontWeight: 700, marginBottom: 6 }}>🚧 This page is under construction.</p>
      <p style={{ color: 'var(--ink-soft)' }}>
        {note ?? 'We are still writing this content. Check back soon.'}
      </p>
    </div>
  )
}

export function AboutPage() {
  return (
    <InfoPage title="About eRestyu">
      <Section heading="What eRestyu is">
        <p>
          eRestyu is a South African multi-vendor marketplace: independent local
          vendors list their products in one catalog, and shoppers buy from
          several of them in a single checkout, paying in rand.
        </p>
      </Section>
      <Section heading="How it works today">
        <p>
          Every vendor runs their own stall: their products, stock, prices, and
          a flat delivery fee they set themselves. When you order, each vendor
          is notified of exactly their part of the order, packs it, and marks it
          shipped, with a tracking number when they have one. You get an email
          at every step and can watch your order move from paid to shipped to
          delivered on your <Link to="/orders">orders page</Link>.
        </p>
        <p>
          Payments run through our secure payment provider. eRestyu never sees or stores your card
          details.
        </p>
      </Section>
      <Section heading="Want to sell here?">
        <p>
          <Link to="/register">Create an account</Link> to get started, or read{' '}
          <Link to="/how-it-works">how buying and selling works</Link>.
        </p>
      </Section>
    </InfoPage>
  )
}

export function CareersPage() {
  return (
    <InfoPage title="Careers">
      <UnderConstruction note="We are not hiring just yet. When roles open, they will be listed here." />
    </InfoPage>
  )
}

export function ContactPage() {
  return (
    <InfoPage title="Contact us">
      <Section heading="Email">
        <p>
          Write to <a href="mailto:hello@erestyu.com">hello@erestyu.com</a> for
          anything: order questions, vendor onboarding, partnerships, or
          problems with the site. Replies to any eRestyu order email reach the
          same inbox.
        </p>
      </Section>
      <Section heading="Response times">
        <p>
          eRestyu is a small team, so there is no formal response-time
          guarantee yet; mail is read daily on weekdays.
        </p>
      </Section>
    </InfoPage>
  )
}

export function ReturnsPage() {
  return (
    <InfoPage title="Returns & cancellations">
      <Section heading="Before you pay">
        <p>
          An unpaid order can be cancelled any time from your{' '}
          <Link to="/orders">orders page</Link>. Stock is released immediately
          and nothing is charged. Unpaid orders also cancel automatically after
          30 minutes.
        </p>
      </Section>
      <Section heading="After you pay">
        <p>
          Once an order is paid, cancellations and refunds are handled case by
          case while we build out self-service refunds. Reply to your order
          confirmation email or write to{' '}
          <a href="mailto:hello@erestyu.com">hello@erestyu.com</a> and we will
          sort it out with the vendor.
        </p>
      </Section>
      <Section heading="Returns policy">
        <UnderConstruction note="The full returns policy is being written. Until it is published, the Consumer Protection Act's default rights apply." />
      </Section>
    </InfoPage>
  )
}

export function ShippingInfoPage() {
  return (
    <InfoPage title="Shipping & delivery">
      <Section heading="Delivery fees">
        <p>
          Each vendor sets one flat delivery fee. Your order shows one delivery
          line per vendor whose items are in it, and vendors who offer free
          delivery add no line at all. The fee you see at checkout is the fee
          you pay: vendors changing their fee later never changes an existing
          order.
        </p>
      </Section>
      <Section heading="Who ships your order">
        <p>
          Vendors pack and dispatch their own items. When an order contains
          items from several vendors, each vendor ships their part; eRestyu
          coordinates mixed orders. You get a shipping email per order, with a
          tracking number when the vendor provides one, and the tracking number
          also appears on your order page.
        </p>
      </Section>
      <Section heading="Where we deliver">
        <p>Delivery is currently within South Africa only.</p>
      </Section>
    </InfoPage>
  )
}

export function HelpPage() {
  // Renders FAQ_ENTRIES, the same array the corner FAQ widget uses. Two
  // hand-maintained copies of these answers would drift, and which version a
  // shopper got would depend on which surface they opened.
  return (
    <InfoPage title="Help center & FAQ">
      {FAQ_ENTRIES.map(entry => (
        <Section key={entry.question} heading={entry.question}>
          <p>
            {entry.answer}
            {entry.link && <> <Link to={entry.link.to}>{entry.link.label}</Link>.</>}
          </p>
        </Section>
      ))}
    </InfoPage>
  )
}

/**
 * The Fees section reads live numbers from the same config the payout
 * ledger charges from — the page can never quote a rate the system does not
 * apply. While commission is not live (the payout selling gate is off and
 * the rate is unset business config), the section keeps its original
 * "listing is free" copy rather than publishing a placeholder number as if
 * it were a decision — the honest-signals rule.
 */
function FeesSection() {
  const [fees, setFees] = React.useState<import('../lib/api').PublicFees | null>(null)
  React.useEffect(() => {
    api<import('../lib/api').PublicFees>('/api/v1/fees', { auth: false })
      .then(setFees)
      .catch(() => setFees(null)) // static copy below stays correct without it
  }, [])

  return (
    <Section heading="Fees">
      <p>
        Listing is free. Payment processing happens at checkout through our secure payment provider.
      </p>
      {fees?.commissionLive && (
        <p>
          When your items sell, eRestyu keeps a {fees.commissionPercent}% commission
          on the item total. Your delivery fee passes through to you in full. Your
          share is paid by EFT within {fees.payoutWindowDays} days of the weekly
          payout run following delivery confirmation — the same terms you accept in
          your dashboard.
        </p>
      )}
    </Section>
  )
}

export function HowItWorksPage() {
  return (
    <InfoPage title="How to buy / how to sell">
      <Section heading="Buying">
        <p>
          Browse the catalog, add items to your cart from any number of
          vendors, and check out once. You will enter a delivery address and
          pay securely at checkout. After payment, every vendor involved
          gets your delivery details and ships their items; you get an email
          when your order is confirmed and again when it ships.
        </p>
      </Section>
      <Section heading="Selling">
        <p>
          <Link to="/register">Create an account</Link>, then set up your
          stall: list products with photos, prices, and stock, and set your
          flat delivery fee in your dashboard. When a customer pays for an
          order with your items, you get an email with exactly your items and
          the delivery address. Pack it, mark it shipped from your{' '}
          <Link to="/vendor/orders">orders view</Link>, and add a tracking
          number if you have one; the buyer is notified automatically.
        </p>
      </Section>
      <FeesSection />
    </InfoPage>
  )
}

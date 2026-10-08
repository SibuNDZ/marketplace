import React from 'react'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { FAQ_ENTRIES } from '../data/faqContent'
import { COMPANY, MARKETPLACE_DISCLOSURE } from '../data/company'

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
        <p>{MARKETPLACE_DISCLOSURE}</p>
        <p>
          Sellers list their products in one catalogue, and shoppers can buy
          from several of them in a single checkout, paying in rand.
          {' '}{COMPANY.tradingName} is operated by {COMPANY.legalName}
          {COMPANY.registrationNumber && <>, registration number {COMPANY.registrationNumber}</>}.
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
          <Link to="/sell">What it costs and how you get paid</Link> is the
          place to start, or read{' '}
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
      {COMPANY.address && (
        <Section heading="Address">
          <p>{COMPANY.legalName}, {COMPANY.address}</p>
        </Section>
      )}
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
    <InfoPage title="Returns & refunds">
      <Section heading="Before you pay">
        <p>
          An unpaid order can be cancelled any time from your{' '}
          <Link to="/orders">orders page</Link>. Stock is released immediately
          and nothing is charged. Unpaid orders also cancel automatically after
          30 minutes.
        </p>
      </Section>
      <Section heading="Who handles a return">
        <p>
          Each product is sold by the seller named on it, and the seller is
          responsible for it. You do not have to chase the seller yourself:
          reply to your order confirmation email or write to{' '}
          <a href="mailto:hello@erestyu.com">hello@erestyu.com</a>, and we take
          it up with the seller. Because eRestyu takes the payment, any refund
          is paid back by eRestyu to the card or account you paid with.
        </p>
      </Section>
      <Section heading="Changing your mind: 7 days">
        <p>
          For goods bought online, the Electronic Communications and
          Transactions Act lets you cancel within 7 days of receiving them,
          without giving a reason. Return the goods in the condition you
          received them; the only cost to you is the direct cost of sending
          them back. Some items are excluded, such as perishable goods, items
          made to your own specification, and audio, video or software you
          have unsealed.
        </p>
      </Section>
      <Section heading="Faulty goods: 6 months">
        <p>
          Under the Consumer Protection Act, if goods are defective or not fit
          for their purpose, you may return them within 6 months of delivery
          and choose a repair, a replacement or a refund.
        </p>
      </Section>
      <Section heading="Refund timing">
        <p>
          If you cancel within the 7 days, your refund is paid to the card or
          account you paid with within 30 days of your cancellation, as the
          law requires, and usually much sooner.
        </p>
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
 * apply. The rate was decided by the owner on 2026-08-30 (10%) and
 * commission-confirmed is now true, so the commission paragraph renders. The
 * conditional stays because the flag is what makes it honest: if the rate
 * ever goes back to being unset config, the page drops the number rather
 * than publishing a placeholder as if it were a decision.
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
          payout run following delivery confirmation, the same terms you accept in
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
          <Link to="/sell">Create an account</Link>, then set up your
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

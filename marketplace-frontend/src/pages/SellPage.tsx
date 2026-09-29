import React from 'react'
import { Link } from 'react-router-dom'
import { api, PublicFees } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { useSellerSignupCta } from '../hooks/useSellerEntry'

/**
 * The seller pitch, at /sell.
 *
 * Before this page existed, every seller call-to-action on the site led
 * straight into "Create an account": a form, with no answer to any of the
 * questions someone weighing it up actually has. What does it cost, when do I
 * get paid, what do I need before I start. A vendor asked all three over
 * WhatsApp, which is the sign that the site was not answering them.
 *
 * House rule from InfoPages, and it matters more here than anywhere because
 * this is the page a recruitment campaign points at: every number and every
 * step below is something the running system does. The fee comes from the
 * same endpoint the payout ledger charges from, so the page cannot quote a
 * rate we do not apply. Nothing describes a feature we have not built.
 */

/** The sections only differ in heading and body, so they share one shell. */
function Block({ heading, children }: { heading: string; children: React.ReactNode }) {
  return (
    <section style={{ marginBottom: 32 }}>
      <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 20, marginBottom: 10 }}>
        {heading}
      </h2>
      <div style={{
        display: 'flex', flexDirection: 'column', gap: 10,
        fontSize: 15, lineHeight: 1.65, color: 'var(--ink-soft)',
      }}>
        {children}
      </div>
    </section>
  )
}

function Step({ n, title, body }: { n: number; title: string; body: string }) {
  return (
    <li style={{ display: 'flex', gap: 14, alignItems: 'flex-start' }}>
      <span aria-hidden="true" style={{
        flex: '0 0 26px', height: 26, borderRadius: '50%', background: 'var(--aloe-tint)',
        color: 'var(--aloe-deep)', fontWeight: 800, fontSize: 13,
        display: 'grid', placeItems: 'center', marginTop: 2,
      }}>
        {n}
      </span>
      <span>
        <strong style={{ color: 'var(--ink)', fontWeight: 700 }}>{title}</strong>
        <br />
        {body}
      </span>
    </li>
  )
}

export function SellPage() {
  const cta = useSellerSignupCta()
  const [fees, setFees] = React.useState<PublicFees | null>(null)

  // Same pattern as the How It Works Fees section: the live numbers are
  // fetched, and if the call fails the page falls back to copy that is true
  // without them. A landing page must not render a blank where a fee belongs.
  React.useEffect(() => {
    api<PublicFees>('/api/v1/fees', { auth: false }).then(setFees).catch(() => setFees(null))
  }, [])

  const button = (label: string, to: string) => (
    <Link to={to} style={{
      display: 'inline-block', padding: '13px 26px', background: 'var(--aloe)', color: '#fff',
      borderRadius: 'var(--r-sm)', fontWeight: 700, fontSize: 15.5, minHeight: 44,
      boxSizing: 'border-box',
    }}>
      {label}
    </Link>
  )

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail" style={{ maxWidth: 760 }}>
        <h1 style={{
          // clamp rather than a fixed 38px: at 375px the fixed size wrapped
          // the headline onto four lines and pushed the call-to-action below
          // the fold, on the width most of this page's traffic will arrive at.
          fontFamily: 'var(--display)', fontWeight: 800, fontSize: 'clamp(29px, 7.5vw, 38px)',
          letterSpacing: '-0.02em', lineHeight: 1.15, marginBottom: 14,
        }}>
          Sell your products on eRestyu
        </h1>
        <p style={{ fontSize: 17, lineHeight: 1.6, color: 'var(--ink-soft)', marginBottom: 24 }}>
          eRestyu is a South African marketplace for independent sellers. You
          get your own stall in a shared catalog: your products, your prices,
          your stock, and a delivery fee you set yourself. Listing costs
          nothing and takes a few minutes.
        </p>

        {/* ADMIN gets no button: the API refuses admin to vendor, and offering
            a door that errors is worse than not offering one. */}
        {cta && <div style={{ marginBottom: 36 }}>{button(cta.label, cta.to)}</div>}

        <Block heading="What it costs">
          <p>
            Listing is free, and there is no monthly fee. You are not charged
            for having products on the site.
          </p>
          {fees?.commissionLive ? (
            <p>
              When an item sells, eRestyu keeps {fees.commissionPercent}% of the
              item total. Your delivery fee is never commissioned: it passes
              through to you in full. Card processing costs come out of our
              share, not yours.
            </p>
          ) : (
            <p>
              When an item sells, eRestyu keeps a commission on the item total.
              Your delivery fee passes through to you in full.
            </p>
          )}
        </Block>

        <Block heading="How you get paid">
          <p>
            Your share of every paid order is worked out and recorded at the
            moment the order is paid, per order, at the rate above. You can see
            what is owed to you in your dashboard.
          </p>
          <p>
            Payouts go out by EFT into your own bank account
            {fees ? ` within ${fees.payoutWindowDays} days of the weekly payout run` : ' on a weekly payout run'}
            , once delivery is confirmed. You add your banking details and
            accept the payout terms in your dashboard, and those terms quote
            the same numbers as this page.
          </p>
        </Block>

        <Block heading="What you need before you start">
          <p>
            A business or trading name, which is what buyers see on every one
            of your listings instead of your personal name. Your own name, for
            the account behind it. A photo, a price and a stock count for each
            product. A flat delivery fee for your stall. A South African bank
            account, for payouts.
          </p>
          <p>
            There is no application and no waiting. You are not vetted, and we
            do not pretend to vet you: you create the account and the stall is
            there.
          </p>
        </Block>

        <Block heading="What happens when something sells">
          <ol style={{ display: 'flex', flexDirection: 'column', gap: 14, listStyle: 'none', padding: 0 }}>
            <Step n={1} title="You get an email"
              body="It contains exactly your items from that order and the delivery address, nothing from other sellers." />
            <Step n={2} title="You pack and send it"
              body="Your own courier or delivery arrangement, covered by the delivery fee you set." />
            <Step n={3} title="You mark it shipped"
              body="From your orders view, with a tracking number if you have one. The buyer is notified automatically." />
            <Step n={4} title="You get paid"
              body="Your share is already recorded against the order and goes out on the payout run." />
          </ol>
        </Block>

        <Block heading="Selling to people who are not on eRestyu yet">
          <p>
            Every seller gets a public stall page with all of their listings on
            it, at its own link. It works as a catalog you can send to someone
            on WhatsApp or put in a social bio, and buyers do not need an
            account to look at it.
          </p>
        </Block>

        {cta && (
          <div style={{
            marginTop: 8, padding: '26px 24px', borderRadius: 'var(--r)',
            border: '1px solid var(--aloe)', background: 'var(--aloe-tint)',
          }}>
            <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 19, marginBottom: 8 }}>
              Ready to list?
            </h2>
            <p style={{ fontSize: 14.5, lineHeight: 1.6, color: 'var(--ink-soft)', marginBottom: 18 }}>
              Set up your stall now. You can add your banking details later,
              before your first payout.
            </p>
            {button(cta.label, cta.to)}
          </div>
        )}

        <p style={{ fontSize: 14, color: 'var(--ink-soft)', marginTop: 28 }}>
          Still deciding? Read{' '}
          <Link to="/how-it-works">how buying and selling works</Link>, or{' '}
          <Link to="/contact">get in touch</Link>.
        </p>
      </main>
    </>
  )
}

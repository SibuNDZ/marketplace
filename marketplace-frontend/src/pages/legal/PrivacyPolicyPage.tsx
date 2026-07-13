import React from 'react'
import { LegalPage, LegalSection } from './LegalPage'

// Every statement here is backed by code that actually behaves that way:
// the 90-day view retention is PopularityJob.sweepOldViews (nightly 03:15),
// the Stripe claim is StripeCheckoutService (hosted checkout — card data
// never reaches this backend), refresh-token handling is RefreshTokenService,
// and the shipping address line is OrderService.shippingFor — collected via
// PaymentController.pay, shared with the admin (today's single fulfiller,
// per AdminOrderController's new detail endpoint) only once an order is
// PAID or later. This page is the privacy notice the discovery slice's
// POPIA note promised.
export function PrivacyPolicyPage() {
  return (
    <LegalPage title="Privacy Policy" lastUpdated="2026-07-13">
      <LegalSection heading="What we collect">
        <p>
          <strong>Account details</strong> — your email address, name, and a hash
          of your password (never the password itself, which is bcrypt-hashed
          before storage).
        </p>
        <p>
          <strong>Order history</strong> — the products, quantities, and prices of
          orders you place, kept as a permanent record of the transaction.
        </p>
        <p>
          <strong>Shipping and contact details</strong> — the recipient name,
          phone number, and delivery address you submit at checkout. This is
          shared with the fulfilling admin once your payment is confirmed (today
          the marketplace has a single admin fulfiller; this will be scoped to
          the specific vendor if a dedicated vendor fulfilment view ships later).
        </p>
        <p>
          <strong>Product views</strong> — which product pages you visit, used for
          your "recently viewed" list and the popularity rankings. Anonymous
          visits are counted for rankings but are not linked to any account.
        </p>
      </LegalSection>

      <LegalSection heading="Why we collect it">
        <p>
          Order fulfilment (we cannot deliver what we cannot record or ship what
          we don't know the address of), account access, and product discovery —
          view history feeds the recently-viewed shelf and the hourly popularity
          model that ranks the catalog.
        </p>
      </LegalSection>

      <LegalSection heading="How long we keep it">
        <p>
          <strong>Product views: 90 days.</strong> A nightly job deletes view
          records older than 90 days — this is enforced in code, not just policy.
        </p>
        <p>
          <strong>Login sessions:</strong> refresh tokens expire after 7 days of
          inactivity and are rotated on every use; a stolen token that is replayed
          revokes the whole session family.
        </p>
        <p>
          <strong>Accounts and orders</strong> are kept while your account exists.
        </p>
      </LegalSection>

      <LegalSection heading="Payment details">
        <p>
          Card details never touch this system. Payment happens on Stripe's hosted
          checkout page; we receive confirmation that payment succeeded, the order
          reference, and nothing about your card.
        </p>
      </LegalSection>

      <LegalSection heading="Your rights under POPIA">
        <p>
          Under the Protection of Personal Information Act you may request access
          to the personal information we hold about you, ask for it to be
          corrected, or ask for it to be deleted. For this project, contact the
          repository owner via the project's GitHub page to exercise any of these.
        </p>
      </LegalSection>
    </LegalPage>
  )
}

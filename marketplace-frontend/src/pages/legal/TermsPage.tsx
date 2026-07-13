import React from 'react'
import { LegalPage, LegalSection } from './LegalPage'

// Short and honest rather than borrowed legalese. Facts verified against
// the code: 30-minute payment window (StripeCheckoutService.PAYMENT_WINDOW_MINUTES,
// swept with a 5-minute grace by OrderExpiryJob), customer cancel on PENDING
// restores stock (OrderService.cancelOrder), prices snapshot at purchase,
// reviews require a delivered purchase (ReviewService).
export function TermsPage() {
  return (
    <LegalPage title="Terms of Service" lastUpdated="2026-07-11">
      <LegalSection heading="Orders">
        <p>
          An order is defined by your cart at the moment you place it. Prices are
          snapshotted at that moment — you pay what checkout showed you, even if
          the vendor reprices afterwards.
        </p>
      </LegalSection>

      <LegalSection heading="Payment window">
        <p>
          After placing an order you have <strong>30 minutes</strong> to complete
          payment on Stripe. Unpaid orders are automatically cancelled shortly
          after the window closes and the reserved stock is released back to the
          catalog.
        </p>
      </LegalSection>

      <LegalSection heading="Cancellation">
        <p>
          You can cancel an order yourself at any time while it is still awaiting
          payment. Cancellation releases the stock immediately. Orders that have
          been paid move through shipping and delivery and can no longer be
          cancelled from your side — refunds on delivered orders are handled by
          the marketplace.
        </p>
      </LegalSection>

      <LegalSection heading="Reviews">
        <p>
          Only customers whose order of a product has been delivered can review
          it, and each customer can review a product once. Ratings shown in the
          catalog are computed from these verified-purchase reviews only.
        </p>
      </LegalSection>

      <LegalSection heading="Selling">
        <p>
          Vendors manage only their own products and stock. Product listings are
          removed from the catalog when a vendor deletes them, but records of past
          orders for those products are preserved.
        </p>
      </LegalSection>
    </LegalPage>
  )
}

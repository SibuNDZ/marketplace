import React from 'react'
import { Link } from 'react-router-dom'
import { LegalPage, LegalSection } from './LegalPage'
import { COMPANY, MARKETPLACE_DISCLOSURE } from '../../data/company'

// Short and honest rather than borrowed legalese. Facts verified against
// the code: 30-minute payment window (StripeCheckoutService.PAYMENT_WINDOW_MINUTES,
// swept with a 5-minute grace by OrderExpiryJob), customer cancel on PENDING
// restores stock (OrderService.cancelOrder), prices snapshot at purchase,
// reviews require a delivered purchase (ReviewService), uploaded photos are
// served from a public URL with no auth required to view (ObjectStorageService.publicUrl).
export function TermsPage() {
  return (
    <LegalPage title="Terms of Service" lastUpdated="2026-10-09">
      <LegalSection heading="Who we are">
        <p>
          {COMPANY.tradingName} is operated by {COMPANY.legalName}
          {COMPANY.registrationNumber && <>, registration number {COMPANY.registrationNumber}</>}
          {COMPANY.address && <>, {COMPANY.address}</>}. You can reach us at{' '}
          <a href={`mailto:${COMPANY.email}`}>{COMPANY.email}</a>.
        </p>
      </LegalSection>

      <LegalSection heading="Who you are buying from">
        <p>{MARKETPLACE_DISCLOSURE}</p>
        <p>
          When you buy a product, you buy it from the seller named on the
          listing. The seller is responsible for the product: that it is as
          described, that it is theirs to sell, and that it arrives. eRestyu
          provides the marketplace, takes payment on the seller&rsquo;s behalf,
          and handles refunds as set out in our{' '}
          <Link to="/returns">returns and refunds policy</Link>.
        </p>
      </LegalSection>

      <LegalSection heading="Orders">
        <p>
          An order is defined by your cart at the moment you place it. Prices are
          snapshotted at that moment: you pay what checkout showed you, even if
          the vendor reprices afterwards.
        </p>
      </LegalSection>

      <LegalSection heading="Payment window">
        <p>
          After placing an order you have <strong>30 minutes</strong> to complete
          payment with our payment provider. Unpaid orders are automatically cancelled shortly
          after the window closes and the reserved stock is released back to the
          catalog.
        </p>
      </LegalSection>

      <LegalSection heading="Cancellation">
        <p>
          You can cancel an order yourself at any time while it is still awaiting
          payment. Cancellation releases the stock immediately. Orders that have
          been paid move through shipping and delivery and can no longer be
          cancelled from your side. Refunds and returns after payment are
          covered by our <Link to="/returns">returns and refunds policy</Link>.
        </p>
      </LegalSection>

      <LegalSection heading="Reviews">
        <p>
          Only customers whose order of a product has been delivered can review
          it, and each customer can review a product once. Ratings shown in the
          catalog are computed from these verified-purchase reviews only.
        </p>
      </LegalSection>

      <LegalSection heading="What may not be sold">
        <p>
          Sellers may not list counterfeit or replica goods, trademarked brands
          they are not authorised to sell, stolen goods, or anything that is
          illegal to sell in South Africa. eRestyu may remove any listing, and
          close any store, that breaks these rules, and may remove a listing
          it has reason to doubt while it checks.
        </p>
      </LegalSection>

      <LegalSection heading="Selling">
        <p>
          Vendors manage only their own products and stock. Product listings are
          removed from the catalog when a vendor deletes them, but records of past
          orders for those products are preserved. By uploading a product photo,
          a vendor confirms they hold the rights to it; uploaded photos are
          served publicly and are visible to anyone browsing the catalog.
        </p>
        <p>
          A vendor may link their store's own Instagram, TikTok, Facebook and X
          profiles, which are shown publicly on their shop page. Linking an
          account that does not belong to the store is not allowed, and eRestyu
          may remove any store's links at its discretion.
        </p>
      </LegalSection>
    </LegalPage>
  )
}

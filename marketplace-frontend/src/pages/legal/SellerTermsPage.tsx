import React from 'react'
import { Link } from 'react-router-dom'
import { api, PublicFees } from '../../lib/api'
import { LegalPage, LegalSection } from './LegalPage'
import { COMPANY } from '../../data/company'

/**
 * The seller terms as a page (paystack-subaccounts.md §8), rendered through
 * LegalPage like the other policies. Built in slice 1 but NOT yet the text
 * a seller accepts: the acceptance call still records PayoutTerms.text()
 * at PAYOUT_TERMS_VERSION 3, and nothing here changes that.
 *
 * The numbers come from the public fees endpoint, the same config the
 * ledger charges from, never from a string typed here. Until the fees
 * response arrives the numeric sentences are not rendered at all: a page
 * that showed a placeholder rate would be stating a fee nobody decided.
 */

/**
 * What the live system does today, word for word what PayoutTerms.text()
 * builds on the server: EFT within N days of the weekly run following
 * delivery confirmation. This is the version rendered.
 */
export const HOW_YOU_ARE_PAID_LIVE = (fees: PublicFees) =>
  `eRestyu remits your share of each sale (item total minus ${fees.commissionPercent}% commission; `
  + 'delivery fees pass through in full) by EFT to your nominated account within '
  + `${fees.payoutWindowDays} days of the weekly payout run following delivery confirmation.`

/**
 * The §8 version of the same section. NOT rendered: it describes settlement
 * by the payment provider's split, which is not how sellers are paid yet.
 * Switch when settlement moves to Paystack split; bump PAYOUT_TERMS_VERSION
 * in the same deploy.
 */
export const HOW_YOU_ARE_PAID_SPLIT =
  'Your share of each sale (item total less commission, plus your delivery fee) is paid to the '
  + 'bank account you nominated by our payment provider as part of that transaction’s settlement. '
  + 'The settlement timing is set by the payment provider. eRestyu does not hold your share.'

export const SELLER_TERMS_VERSION_LABEL = 'version 3'

export function SellerTermsPage() {
  const [fees, setFees] = React.useState<PublicFees | null>(null)
  const [feesFailed, setFeesFailed] = React.useState(false)
  React.useEffect(() => {
    api<PublicFees>('/api/v1/fees', { auth: false })
      .then(setFees)
      .catch(() => setFeesFailed(true))
  }, [])

  return (
    <LegalPage title="Seller terms" lastUpdated="2026-10-18">
      <LegalSection heading="Who you are dealing with">
        <p>
          {COMPANY.tradingName} is operated by {COMPANY.legalName}. You sell your own goods
          to buyers through {COMPANY.tradingName}. {COMPANY.tradingName} provides the
          marketplace and the payment process; it does not buy, hold or ship your goods.
        </p>
      </LegalSection>

      <LegalSection heading="Fees">
        {fees ? (
          <p>
            {COMPANY.tradingName} charges a commission of{' '}
            <span className="num">{fees.commissionPercent}%</span> of the item total of each
            sale. Delivery fees you set are passed to you in full. {COMPANY.tradingName} pays
            the payment processing fees out of its commission. There are no listing or
            monthly fees.
          </p>
        ) : (
          <p>{feesFailed ? 'The fee figures could not be loaded. Refresh to try again.' : 'Loading fee figures…'}</p>
        )}
      </LegalSection>

      <LegalSection heading="How you are paid">
        {fees ? (
          <p>{HOW_YOU_ARE_PAID_LIVE(fees)}</p>
        ) : (
          <p>{feesFailed ? 'The payout figures could not be loaded. Refresh to try again.' : 'Loading payout figures…'}</p>
        )}
      </LegalSection>

      <LegalSection heading="Verification">
        <p>
          Before you can sell, {COMPANY.tradingName} reviews the bank account you nominate,
          the name of its holder and your first listings. If you supply an identity document
          number (South African ID number, passport number or CIPC registration number), we
          keep a record of the document type and its last four characters; we do not keep the
          number itself. Changing your bank details suspends selling until the new details
          are reviewed.
        </p>
      </LegalSection>

      <LegalSection heading="Your responsibilities">
        <p>
          You are responsible for each item you list: that it is as described, that it is
          yours to sell, that it is lawful to sell in South Africa, and that it reaches the
          buyer. You may not list counterfeit or replica goods, brands you are not authorised
          to sell, or stolen goods. {COMPANY.tradingName} may remove any listing, and close
          any store, that breaks these rules, and may remove a listing it has reason to doubt
          while it checks.
        </p>
      </LegalSection>

      <LegalSection heading="Refunds and set-off">
        <p>
          Buyers have the rights set out in our{' '}
          <Link to="/returns">Returns and Refunds policy</Link>, including the seven-day
          cooling-off right under the ECT Act and the six-month defect right under the
          Consumer Protection Act. When {COMPANY.tradingName} refunds a buyer for a sale of
          yours, you owe {COMPANY.tradingName} the amount of your share of that sale.
          {' '}{COMPANY.tradingName} may recover it by reducing your share of your later sales
          until it is recovered, and will show you the outstanding amount and the order it
          relates to.
        </p>
      </LegalSection>

      <LegalSection heading="Photos and store links">
        <p>
          By uploading a product photo you confirm you hold the rights to it. Photos are
          public. You may link only social profiles that belong to your store.
        </p>
      </LegalSection>

      <LegalSection heading="Ending">
        <p>
          You may stop selling at any time by removing your listings; orders already paid
          must still be fulfilled. {COMPANY.tradingName} may close a store for breach of
          these terms. These terms change by version; selling continues only after you
          accept the current version.
        </p>
      </LegalSection>

      <LegalSection heading="Law and contact">
        <p>
          South African law applies. Write to{' '}
          <a href={`mailto:${COMPANY.email}`}>{COMPANY.email}</a>.
        </p>
      </LegalSection>
    </LegalPage>
  )
}

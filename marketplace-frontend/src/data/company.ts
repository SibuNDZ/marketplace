/**
 * Who operates eRestyu, as a payment provider's reviewer (and the CPA)
 * expects to find it: on every page, in plain text.
 *
 * Only facts go here. A field left undefined is not rendered at all, so the
 * site never shows a placeholder where a registration number should be.
 */
export const COMPANY = {
  tradingName: 'eRestyu',
  legalName: 'ERESTYU (Pty) Ltd',
  /** CIPC registration number, e.g. 2026/123456/07. */
  registrationNumber: undefined as string | undefined,
  /** Registered address, one line. */
  address: undefined as string | undefined,
  email: 'hello@erestyu.com',
}

/**
 * What eRestyu is, for the footer on every page. Deliberately says nothing
 * about vetting: the owner does not want that on the homepage, and the
 * footer renders there (2026-10-09).
 */
export const MARKETPLACE_SUMMARY =
  'eRestyu is a commission marketplace. Independent sellers list and sell their own goods. ' +
  'Buyers pay eRestyu, which takes a 10% commission and pays the seller after delivery.'

/**
 * The full description, used word for word where a buyer reads it before
 * paying or agreeing: the Terms and the About page (and, in short form,
 * every product page). Keep it identical to the description on the payment
 * provider's business profile.
 */
export const MARKETPLACE_DISCLOSURE =
  MARKETPLACE_SUMMARY + ' eRestyu does not vet sellers, authenticate brands or hold stock.'

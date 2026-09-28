import { useAuth } from '../context/AuthContext'
import type { AuthUser } from '../context/AuthContext'

export interface SellerEntry {
  to: string
  label: string
}

/**
 * Where a seller call-to-action should go, given who is signed in.
 *
 * It used to be a hardcoded link to /register?role=vendor everywhere, which
 * meant a signed-in vendor tapping the loudest button on the mobile home page
 * landed on "Create an account", and re-registering their own email returned
 * 409. That is a closed loop: the button that exists to get sellers listing
 * was the one thing that could not lead to a listing.
 *
 * Signed in, the destination is derived from the role rather than the page, so
 * every entry point stays correct on its own:
 *   CUSTOMER -> the self-serve upgrade in account settings
 *   VENDOR   -> their stall, where "+ New product" lives
 *   ADMIN    -> nothing; the API refuses admin -> vendor, because that would
 *               be a silent privilege downgrade. Offering the door and then
 *               erroring is worse than not offering it.
 */
function signedIn(user: AuthUser): SellerEntry | null {
  if (user.role === 'ADMIN') return null
  if (user.role === 'VENDOR') return { to: '/vendor', label: 'List a product' }
  return { to: '/account#start-selling', label: 'Start selling' }
}

/**
 * The DISCOVERY entry: what the header, footer and catalogue link to.
 *
 * Signed out, this goes to the pitch rather than the form. A stranger tapping
 * "Sell on eRestyu" had no way to find out what it costs, when they get paid
 * or what they need before starting, because the only thing behind that button
 * was a set of input fields. Sending them to /sell answers the questions
 * first, which is the point of having the page.
 */
export function useSellerEntry(): SellerEntry | null {
  const { user } = useAuth()
  if (!user) return { to: '/sell', label: 'Sell on eRestyu' }
  return signedIn(user)
}

/**
 * The CONVERSION entry: what /sell's own buttons link to.
 *
 * Identical once signed in, and it has to be, or a vendor reading the pitch
 * would be invited to register a second time. It differs only for a signed-out
 * visitor, who has already had the pitch by the time they reach a button on
 * that page, so sending them back to it would be a loop.
 */
export function useSellerSignupCta(): SellerEntry | null {
  const { user } = useAuth()
  if (!user) return { to: '/register?role=vendor', label: 'Create your seller account' }
  return signedIn(user)
}

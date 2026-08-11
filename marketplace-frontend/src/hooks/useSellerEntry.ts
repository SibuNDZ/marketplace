import { useAuth } from '../context/AuthContext'

export interface SellerEntry {
  to: string
  label: string
}

/**
 * Where the seller call-to-action should actually go, given who is signed in.
 *
 * It used to be a hardcoded link to /register?role=vendor everywhere, which
 * meant a signed-in vendor tapping the loudest button on the mobile home page
 * landed on "Create an account", and re-registering their own email returned
 * 409. That is a closed loop: the button that exists to get sellers listing
 * was the one thing that could not lead to a listing.
 *
 * The destination is derived from the role rather than the page, so every
 * entry point stays correct on its own:
 *   signed out  -> registration, seller card preselected (unchanged)
 *   CUSTOMER    -> the self-serve upgrade in account settings
 *   VENDOR      -> their stall, where "+ New product" lives
 *   ADMIN       -> nothing; the API refuses admin -> vendor, because that
 *                  would be a silent privilege downgrade. Offering the door
 *                  and then erroring is worse than not offering it.
 *
 * Labels change with the destination: "Sell on eRestyu" is a pitch, and
 * pitching to someone who already sells is what made the old button read as
 * "you are not signed up" to people who were.
 */
export function useSellerEntry(): SellerEntry | null {
  const { user } = useAuth()

  if (!user) return { to: '/register?role=vendor', label: 'Sell on eRestyu' }
  if (user.role === 'ADMIN') return null
  if (user.role === 'VENDOR') return { to: '/vendor', label: 'List a product' }
  return { to: '/account#start-selling', label: 'Start selling' }
}

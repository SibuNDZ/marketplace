import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useAuth } from '../context/AuthContext'

interface PaymentsHealth {
  mode: string
  /** "admins" while payments run test keys and checkout is guarded. */
  checkoutOpenTo?: 'everyone' | 'admins'
}

/**
 * Whether this visitor can check out right now.
 *
 * While payments run test keys, the server limits checkout to admins
 * (CheckoutPolicy) and answers anyone else with 409 "Checkout not open yet".
 * This hook only saves the shopper from pressing a button that would say no:
 * it shows a calm "opens soon" state instead. The server enforces the rule
 * either way.
 *
 * Fails OPEN: if the health call fails or has not answered, the button shows
 * as normal and the server's 409 is the backstop. A broken status call must
 * never be what stops a real shopper paying once live payments are on.
 */
/**
 * Whether checkout is open to the PUBLIC, for marketing claims such as
 * "Secure checkout" or the accepted-cards row. Unlike useCheckoutOpen this
 * fails CLOSED and ignores the viewer's role: a claim appears only once the
 * server confirms live payments, and an admin's view must not show it early.
 */
export function useCheckoutLive(): boolean {
  const { data } = useQuery<PaymentsHealth>({
    queryKey: ['payments-health'],
    queryFn: () => api('/api/v1/payments/health', { auth: false }),
    staleTime: 60_000,
  })
  return data?.checkoutOpenTo === 'everyone'
}

export function useCheckoutOpen(): boolean {
  const { user } = useAuth()
  const { data } = useQuery<PaymentsHealth>({
    queryKey: ['payments-health'],
    queryFn: () => api('/api/v1/payments/health', { auth: false }),
    staleTime: 60_000,
  })
  if (data?.checkoutOpenTo !== 'admins') return true
  return user?.role === 'ADMIN'
}

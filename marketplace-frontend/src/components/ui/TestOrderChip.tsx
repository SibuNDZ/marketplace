/**
 * Marks an order an admin placed while payments ran test keys. Such an order
 * is not a sale: no vendor was notified and no payout is owed (backend
 * CheckoutPolicy). Shown beside the status so a PAID test order can never be
 * mistaken for one to ship.
 */
export function TestOrderChip() {
  return (
    <span title="Placed in test mode: not a sale, no vendor notified, no payout owed" style={{
      display: 'inline-block', padding: '2px 8px', borderRadius: 'var(--r-pill)',
      fontSize: 11, fontWeight: 800, letterSpacing: '0.04em',
      color: 'var(--sun-deep)', background: 'var(--sun-tint)', border: '1px solid var(--sun)',
    }}>
      TEST
    </span>
  )
}

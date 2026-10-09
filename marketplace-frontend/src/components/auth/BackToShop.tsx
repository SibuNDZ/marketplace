import { Link } from 'react-router-dom'

/**
 * The way out of the auth pages. They render without the site header (a
 * focused card, no search or cart), which left no route back to the shop
 * for someone who opened sign-in and then decided to keep browsing: the
 * browser's Back button was the only exit, and after a redirect to /login it
 * does not even go anywhere useful.
 *
 * Paired with the wordmark on each of these pages also linking home, which is
 * where people instinctively click; this is the visible, labelled version.
 */
export function BackToShop() {
  return (
    <Link to="/" style={{
      display: 'inline-flex', alignItems: 'center', gap: 4, alignSelf: 'flex-start',
      fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)', marginBottom: 20,
    }}>
      <span aria-hidden="true">←</span> Back to the shop
    </Link>
  )
}

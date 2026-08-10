// Shared by the catalogue chip row and the right panel's Highlights section,
// so both render the same filters and stay in sync through the ?filters=
// search param (single source of truth, like ?category=).
//
// bestSelling and fiveStar run on REAL aggregates from the popularity read
// model. On a fresh catalog with no activity they simply match nothing —
// the truthful result, not a bug.
//
// handmade is different in kind and that difference is deliberate: it is a
// SERVER-side filter on a product column, so it goes in the query rather
// than filtering the current page client-side. Filtering handmade in the
// browser would only ever search the 20 products already loaded, which
// silently lies once there is more than one page.
export const QUICK_FILTERS = [
  { key: 'handmade', label: '🧵 Handmade' },
  { key: 'bestSelling', label: '🔥 Best-Selling' },
  { key: 'fiveStar', label: '⭐ Top Rated' },
] as const

export type QuickFilterKey = typeof QUICK_FILTERS[number]['key']

export function isQuickFilterKey(value: string): value is QuickFilterKey {
  return QUICK_FILTERS.some(f => f.key === value)
}

// Shared by the catalogue chip row and the right panel's Highlights section,
// so both render the same filters and stay in sync through the ?filters=
// search param (single source of truth, like ?category=).
//
// handmade is a server-side filter on a product column. bestSelling and
// fiveStar are also server-side now (minSold / minRating + rank), so they
// search the whole catalogue rather than the page already on screen.
export const QUICK_FILTERS = [
  { key: 'handmade', label: '🧵 Handmade' },
  { key: 'bestSelling', label: '🔥 Best-Selling' },
  { key: 'fiveStar', label: '⭐ Top Rated' },
] as const

export type QuickFilterKey = typeof QUICK_FILTERS[number]['key']

export function isQuickFilterKey(value: string): value is QuickFilterKey {
  return QUICK_FILTERS.some(f => f.key === value)
}

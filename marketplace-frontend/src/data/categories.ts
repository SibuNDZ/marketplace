// Category taxonomy — keys are the EXACT ProductCategory enum names the
// backend serializes (see entity/ProductCategory.java), so a value read
// off ProductResponse.category or sent as ?category= needs no translation.
//
// Five categories, not seventeen: this is a food-and-crafts local
// marketplace. The earlier Temu-redesign taxonomy (Vehicles, Property
// Rentals, Electronics, ...) was decorative scaffolding with no real
// products behind it and no path to ever having any — cut with the rest
// of the id-arithmetic fabrication when V10 gave products a real column.
export interface Category {
  key: string
  label: string
  icon: string
}

export const ALL_CATEGORY: Category = { key: 'ALL', label: 'All', icon: '🛍️' }

export const CATEGORIES: Category[] = [
  { key: 'PRODUCE', label: 'Produce', icon: '🥑' },
  { key: 'PANTRY', label: 'Pantry', icon: '🍯' },
  { key: 'CRAFTS', label: 'Crafts', icon: '🎨' },
  { key: 'HOME', label: 'Home', icon: '🛋️' },
  { key: 'OTHER', label: 'Other', icon: '📦' },
]

export const PILL_CATEGORIES: Category[] = [ALL_CATEGORY, ...CATEGORIES]

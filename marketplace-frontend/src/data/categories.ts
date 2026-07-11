// Category taxonomy for both the primary pill rail and the secondary
// sidebar tree. One list, two renderings — Product has no category column
// yet, so assignment is simulated in lib/marketplaceSignals.ts rather than
// filtered server-side.
export interface Category {
  key: string
  label: string
  icon: string
}

export const ALL_CATEGORY: Category = { key: 'all', label: 'All', icon: '🛍️' }

export const CATEGORIES: Category[] = [
  { key: 'produce', label: 'Produce', icon: '🥑' },
  { key: 'pantry', label: 'Pantry', icon: '🍯' },
  { key: 'crafts', label: 'Crafts', icon: '🎨' },
  { key: 'home', label: 'Home', icon: '🛋️' },
  { key: 'apparel', label: 'Apparel', icon: '👕' },
  { key: 'electronics', label: 'Electronics', icon: '🔌' },
  { key: 'vehicles', label: 'Vehicles', icon: '🚗' },
  { key: 'property', label: 'Property Rentals', icon: '🏠' },
  { key: 'entertainment', label: 'Entertainment', icon: '🎭' },
  { key: 'family', label: 'Family', icon: '👨‍👩‍👧' },
  { key: 'garden', label: 'Garden & Outdoor', icon: '🌿' },
  { key: 'hobbies', label: 'Hobbies', icon: '🧵' },
  { key: 'homegoods', label: 'Home Goods', icon: '🪴' },
  { key: 'free', label: 'Free Stuff', icon: '🆓' },
  { key: 'classifieds', label: 'Classifieds', icon: '📋' },
  { key: 'health', label: 'Health & Beauty', icon: '💄' },
  { key: 'sports', label: 'Sports & Outdoors', icon: '⚽' },
]

export const PILL_CATEGORIES: Category[] = [ALL_CATEGORY, ...CATEGORIES]

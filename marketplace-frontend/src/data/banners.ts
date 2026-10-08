// Banner config: 3 editorial hero slides plus 5 category tiles. Both kinds
// share one carousel, interleaved hero/category (H1 C1 H2 C2 H3 C3 C4 C5), so
// they cycle in the same space rather than stacking down the page. Same
// honesty rule: no discount claims, no countdowns.

export interface BannerConfig {
  format: 'hero' | 'tile'
  /** Category slug the tile targets. Heroes are editorial and target nothing. */
  category?: string
  /**
   * Deep-link slug for the CTA, mirroring the sidebar's featured drill-downs
   * (Fashion → Jewellery, Jewellery → Watches, Beauty → Skincare,
   * Home → Furniture). Absent when no natural subcategory exists (Pantry).
   */
  subcategory?: string
  badge: string
  title: string
  subtitle: string
  cta: string
  gradient: string
  /** Dark-theme variant: same composition, obsidian-metallic values. */
  gradientDark: string
  icon: string
}

// Rewritten 2026-10-09. The previous slides advertised stock nobody was
// selling (braai tongs, biltong, hand-knit beanies), a winter promotion in
// spring, and "Top rated" and "Handmade" badges no data stood behind. Each
// slide now says only what is true of the whole marketplace or of a category.
export const BANNERS: BannerConfig[] = [
  // -- hero slides --
  {
    format: 'hero',
    badge: 'Independent sellers',
    title: 'Sold by the people who make it',
    subtitle: 'Every product is listed by its own seller, named on the listing',
    cta: 'Browse everything',
    gradient: 'linear-gradient(120deg, #FF7A18 0%, #FF4626 55%, #AF2896 100%)',
    gradientDark: 'linear-gradient(120deg, #0e0a14 0%, #2a1040 55%, #6e0f8a 100%)',
    icon: '🧺',
  },
  {
    format: 'hero',
    badge: 'For sellers',
    title: 'Free to list. 10% when you sell.',
    subtitle: 'No monthly fee, and your delivery fee stays yours',
    cta: 'erestyu.com/sell',
    gradient: 'linear-gradient(120deg, #2E6B4F 0%, #3C8461 55%, #E89B4E 100%)',
    gradientDark: 'linear-gradient(120deg, #070b0a 0%, #0f2a20 55%, #2E6B4F 100%)',
    icon: '🏪',
  },
  {
    format: 'hero',
    badge: 'Your stall',
    title: 'One link for your whole stall',
    subtitle: 'Share it on WhatsApp or in your bio. Buyers need no account to look',
    cta: 'See how it works',
    gradient: 'linear-gradient(120deg, #C97D00 0%, #FFB020 55%, #FFD76A 100%)',
    gradientDark: 'linear-gradient(120deg, #0d0c08 0%, #33250a 55%, #8a6410 100%)',
    icon: '🔗',
  },
  // -- category tiles: the category's name, nothing promised about its stock --
  { format: 'tile', category: 'fashion', subcategory: 'jewellery', badge: 'Department', title: 'Fashion',
    subtitle: 'Clothing, shoes and accessories from independent sellers', cta: 'Browse fashion',
    gradient: 'linear-gradient(120deg, #E2582F 0%, #B84424 55%, #7a2a16 100%)', gradientDark: 'linear-gradient(120deg, #0e0a14 0%, #2a1040 55%, #6e0f8a 100%)', icon: '👗' },
  { format: 'tile', category: 'jewellery-collections', subcategory: 'watches', badge: 'Department', title: 'Jewellery',
    subtitle: 'Jewellery and watches from independent sellers', cta: 'Browse jewellery',
    gradient: 'linear-gradient(120deg, #C97D00 0%, #E89B4E 55%, #FFD76A 100%)', gradientDark: 'linear-gradient(120deg, #0e0a14 0%, #2a1040 55%, #6e0f8a 100%)', icon: '💍' },
  { format: 'tile', category: 'beauty-and-personal-care', subcategory: 'skincare', badge: 'Department', title: 'Beauty',
    subtitle: 'Skincare, fragrance and personal care from independent sellers', cta: 'Browse beauty',
    gradient: 'linear-gradient(120deg, #B84424 0%, #E2582F 55%, #E89B4E 100%)', gradientDark: 'linear-gradient(120deg, #0e0a14 0%, #2a1040 55%, #6e0f8a 100%)', icon: '🧴' },
  { format: 'tile', category: 'home-and-living', subcategory: 'furniture', badge: 'Department', title: 'Home and living',
    subtitle: 'Furniture, decor and homeware from independent sellers', cta: 'Browse home',
    gradient: 'linear-gradient(120deg, #2E6B4F 0%, #3C8461 55%, #7FB59A 100%)', gradientDark: 'linear-gradient(120deg, #0e0a14 0%, #2a1040 55%, #6e0f8a 100%)', icon: '🏠' },
  { format: 'tile', category: 'pantry', badge: 'Department', title: 'Pantry',
    subtitle: 'Food and drink from independent sellers', cta: 'Browse pantry',
    gradient: 'linear-gradient(120deg, #8a6410 0%, #C97D00 55%, #E89B4E 100%)', gradientDark: 'linear-gradient(120deg, #0e0a14 0%, #2a1040 55%, #6e0f8a 100%)', icon: '🫙' },
]

export const HERO_BANNERS = BANNERS.filter(b => b.format === 'hero')
export const TILE_BANNERS = BANNERS.filter(b => b.format === 'tile')

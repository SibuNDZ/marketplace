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

export const BANNERS: BannerConfig[] = [
  // -- hero slides (existing copy, verbatim) --
  {
    format: 'hero',
    badge: 'Seasonal',
    title: 'Braai Season Essentials',
    subtitle: 'Tongs, boerewors spirals & firelighters from local stalls',
    cta: 'Shop the braai',
    gradient: 'linear-gradient(120deg, #FF7A18 0%, #FF4626 55%, #AF2896 100%)',
    gradientDark: 'linear-gradient(120deg, #0e0a14 0%, #2a1040 55%, #6e0f8a 100%)',
    icon: '🔥',
  },
  {
    format: 'hero',
    badge: 'New in',
    title: 'Winter Warmers',
    subtitle: 'Wool blankets, rooibos, and hand-knit beanies from local stalls',
    cta: 'Explore winter picks',
    gradient: 'linear-gradient(120deg, #2E5CA6 0%, #1E6FE0 55%, #46B4D6 100%)',
    gradientDark: 'linear-gradient(120deg, #070b14 0%, #0a2438 55%, #0a6a80 100%)',
    icon: '🧣',
  },
  {
    format: 'hero',
    badge: 'Local',
    title: 'Weekend Picks',
    subtitle: 'Hand-picked from Cape Town stalls, delivered to your door',
    cta: 'Browse eRestyu',
    gradient: 'linear-gradient(120deg, #C97D00 0%, #FFB020 55%, #FFD76A 100%)',
    gradientDark: 'linear-gradient(120deg, #0d0c08 0%, #33250a 55%, #8a6410 100%)',
    icon: '🧺',
  },

  // -- category tiles (one per top-level department) --
  {
    format: 'tile',
    category: 'pantry',
    badge: 'Local',
    title: 'Pantry Picks',
    subtitle: 'Rooibos, biltong & small batch preserves from local stalls',
    cta: 'Shop pantry',
    gradient: 'linear-gradient(120deg, #B36A00 0%, #E8A020 55%, #FFC85C 100%)',
    gradientDark: 'linear-gradient(120deg, #0c0a08 0%, #2e2008 55%, #7a5510 100%)',
    icon: '🧺',
  },
  {
    format: 'tile',
    category: 'fashion',
    subcategory: 'jewellery',
    badge: 'New in',
    title: 'Fresh Fits',
    subtitle: 'Hand knit beanies, scarves & streetwear from local makers',
    cta: 'Shop fashion',
    gradient: 'linear-gradient(120deg, #1E5FA6 0%, #1E8FB4 55%, #3EC6C0 100%)',
    gradientDark: 'linear-gradient(120deg, #070d12 0%, #0a2e3a 55%, #0e7c8c 100%)',
    icon: '🧣',
  },
  {
    // Top-level Jewellery is 'jewellery-collections'; the bare 'jewellery'
    // slug belongs to the Fashion subcategory (see V15 migration).
    format: 'tile',
    category: 'jewellery-collections',
    subcategory: 'watches',
    badge: 'Handmade',
    title: 'Shine Local',
    subtitle: 'Rose gold chains, watches & artisan pieces',
    cta: 'Shop jewellery',
    gradient: 'linear-gradient(120deg, #B76E79 0%, #D9938B 55%, #EEC9B7 100%)',
    gradientDark: 'linear-gradient(120deg, #120a10 0%, #3a1230 55%, #8a2468 100%)',
    icon: '💍',
  },
  {
    format: 'tile',
    category: 'beauty-and-personal-care',
    subcategory: 'skincare',
    badge: 'Top rated',
    title: 'Glow Local',
    subtitle: 'Small batch skincare & self care essentials',
    cta: 'Shop beauty',
    gradient: 'linear-gradient(120deg, #D6408B 0%, #B23FB2 55%, #7B3FE4 100%)',
    gradientDark: 'linear-gradient(120deg, #100814 0%, #380a44 55%, #8a14b0 100%)',
    icon: '🧴',
  },
  {
    format: 'tile',
    category: 'home-and-living',
    subcategory: 'furniture',
    badge: 'Seasonal',
    title: 'Cosy Corners',
    subtitle: 'Rugs, décor & furniture from local workshops',
    cta: 'Shop home',
    gradient: 'linear-gradient(120deg, #1F6B4A 0%, #1F8B6E 55%, #2FB4A6 100%)',
    gradientDark: 'linear-gradient(120deg, #06100e 0%, #0a3030 55%, #10847c 100%)',
    icon: '🛋️',
  },
]

export const HERO_BANNERS = BANNERS.filter(b => b.format === 'hero')
export const TILE_BANNERS = BANNERS.filter(b => b.format === 'tile')

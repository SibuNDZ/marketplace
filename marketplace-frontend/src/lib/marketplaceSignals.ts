// REMAINING FABRICATION: category assignment ONLY. Dies with V10.
//
// Everything else this module once fabricated is gone:
//   - ratings / review counts / sold counts → REAL now, from the
//     product_popularity read model via ProductResponse (avgRating,
//     reviewCount, soldCount) and the live /reviews/summary endpoint.
//   - discount % / was-prices → deleted, no replacement. Fabricated
//     reference pricing is CPA-problematic and against the design ethos.
//   - countdown / flash sale / verified badge / free shipping / MOQ /
//     shipping origin → deleted. Manufactured urgency and unbacked claims.
//
// Category assignment stays as accepted scaffolding because Product has no
// category column yet: products land in categories by product.id arithmetic,
// so "Produce (3)" is a real count of an arbitrary grouping. Real fix is a
// category column (V10) + a dropdown on the create-product form — the two
// land together naturally. imageSeed also stays: picsum placeholders are
// presentational, not claims.
import { CATEGORIES } from '../data/categories'

// mulberry32 — small, deterministic, no dependency.
function mulberry32(seed: number) {
  let a = seed
  return function rand() {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export interface MarketplaceSignals {
  categoryKey: string
  imageSeed: string
}

const cache = new Map<number, MarketplaceSignals>()

export function getMarketplaceSignals(productId: number): MarketplaceSignals {
  const cached = cache.get(productId)
  if (cached) return cached

  const rand = mulberry32(productId * 2654435761)
  const signals: MarketplaceSignals = {
    categoryKey: CATEGORIES[Math.floor(rand() * CATEGORIES.length)].key,
    imageSeed: `mk-${productId}`,
  }
  cache.set(productId, signals)
  return signals
}

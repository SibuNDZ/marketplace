import { useQuery } from '@tanstack/react-query'
import { api, Page, ProductResponse } from '../lib/api'

/**
 * One shared page of recent products that the discovery surfaces (featured
 * carousel, Top Selling, Bargain finds) rank client-side. Shared queryKey =
 * one request feeds all three, and they can never disagree about the pool.
 *
 * Ranking happens here so every consumer applies the same honesty rules:
 * real soldCount / avgRating aggregates only, no fabricated signals.
 */
export function useProductPool() {
  return useQuery<Page<ProductResponse>>({
    queryKey: ['products', 'pool'],
    queryFn: () => api('/api/v1/products?page=0&size=24&sort=createdAt,desc'),
    staleTime: 5 * 60 * 1000,
  })
}

const live = (p: ProductResponse) => p.stock > 0 && !p.deletedAt

/** Units actually sold, descending. Empty when nothing has sold yet. */
export function topSelling(pool: ProductResponse[], limit: number): ProductResponse[] {
  return pool
    .filter(p => live(p) && p.soldCount > 0)
    .sort((a, b) => b.soldCount - a.soldCount)
    .slice(0, limit)
}

/**
 * Lowest real prices. A discount model exists (originalPrice) but vendor
 * self-report of it is paused as of 2026-08-13 — the arithmetic guardrail
 * proved nothing about whether the "was" price was ever real, unlike every
 * other trust-sensitive figure here. So "bargain" means cheapest, not
 * "marked down", until a price-history-derived version replaces it.
 */
export function bargains(pool: ProductResponse[], limit: number): ProductResponse[] {
  return pool
    .filter(live)
    .sort((a, b) => Number(a.price) - Number(b.price))
    .slice(0, limit)
}

/** Most sold, then best rated, then newest — the "featured" fallback order. */
export function featured(pool: ProductResponse[], limit: number): ProductResponse[] {
  return pool
    .filter(live)
    .sort((a, b) =>
      b.soldCount - a.soldCount
      || Number(b.avgRating) - Number(a.avgRating)
      || +new Date(b.createdAt) - +new Date(a.createdAt))
    .slice(0, limit)
}

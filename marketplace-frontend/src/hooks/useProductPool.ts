import { useQuery } from '@tanstack/react-query'
import { api, Page, ProductResponse } from '../lib/api'

/**
 * Popularity shelves used to rank the newest 24 products in the browser.
 * That silently lied once the catalogue had more than one page: "top
 * selling" was only top-selling among whatever had just been created.
 * These hooks hit the server endpoints that already own the ranking.
 */
export function usePopular(by: 'sales' | 'rating' | 'views' = 'sales', limit = 12) {
  return useQuery<ProductResponse[]>({
    queryKey: ['products', 'popular', by, limit],
    queryFn: () => api(`/api/v1/products/popular?by=${by}&limit=${limit}`),
    staleTime: 5 * 60 * 1000,
  })
}

/** Cheapest in-stock listings, server-sorted. */
export function useBargains(limit = 4) {
  return useQuery<Page<ProductResponse>>({
    queryKey: ['products', 'bargains', limit],
    queryFn: () => api(`/api/v1/products?page=0&size=${limit}&rank=price&inStock=true`),
    staleTime: 5 * 60 * 1000,
  })
}

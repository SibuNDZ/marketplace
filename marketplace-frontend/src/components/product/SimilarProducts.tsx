import React from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, ProductResponse } from '../../lib/api'
import { ProductCard } from './ProductCard'

interface Props {
  productId: string | number
  limit?: number
}

/**
 * "You might also like", ranked by embedding similarity and broken out of
 * near-ties by the product's own rating, sales and views. Falls back to text
 * search when a product has no embedding yet.
 *
 * Deliberately renders NOTHING when the backend finds nothing genuinely
 * related. On a catalogue this small a padded shelf would just be "here are
 * some other products", which looks like a recommendation without being one
 * — the same reason Top Selling hides itself until real sales exist.
 *
 * The backend also sends a similarityReason per product. It is not rendered:
 * on this shelf it would say "Similar item" under every card, restating the
 * heading above them.
 */
export function SimilarProducts({ productId, limit = 6 }: Props) {
  const { data, isLoading } = useQuery<ProductResponse[]>({
    queryKey: ['product', String(productId), 'similar', limit],
    queryFn: () => api(`/api/v1/products/${productId}/similar?limit=${limit}`),
    enabled: !!productId,
    staleTime: 5 * 60 * 1000,
  })

  if (isLoading || !data || data.length === 0) return null

  return (
    <section style={{ marginTop: 40 }} aria-label="Related products">
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
        <span style={{ fontSize: 18 }} aria-hidden>💡</span>
        <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 18 }}>You might also like</h2>
        <div style={{ flex: 1, height: 1, background: 'var(--line)' }} />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16 }}>
        {data.map(p => <ProductCard key={p.id} product={p} />)}
      </div>
    </section>
  )
}

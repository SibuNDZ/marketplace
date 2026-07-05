import React, { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, Page, ProductResponse } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { ProductCard } from '../components/product/ProductCard'

const CATEGORIES = ['All', 'Produce', 'Pantry', 'Crafts', 'Home']

export function CatalogPage() {
  const [category, setCategory] = useState('All')
  const [page, setPage] = useState(0)
  const PAGE_SIZE = 20

  const { data, isLoading } = useQuery<Page<ProductResponse>>({
    queryKey: ['products', page, PAGE_SIZE],
    queryFn: () => api(`/api/v1/products?page=${page}&size=${PAGE_SIZE}&sort=createdAt,desc`),
  })

  const products = data?.content ?? []

  return (
    <>
      <Topbar />
      <main className="page-shell">
        {/* Hero */}
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 24 }}>
          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 800, fontSize: 36, letterSpacing: '-0.02em', color: 'var(--ink)' }}>
            <span>Fresh from </span>
            <span style={{ color: 'var(--aloe)' }}>every stall</span>
          </h1>
          {data && (
            <p style={{ fontSize: 13, color: 'var(--ink-soft)' }}>
              <span className="num">{data.totalElements}</span> products
            </p>
          )}
        </div>

        {/* Category chips */}
        <div style={{ display: 'flex', gap: 8, marginBottom: 28, flexWrap: 'wrap' }}>
          {CATEGORIES.map(cat => (
            <button key={cat} onClick={() => setCategory(cat)} style={{
              padding: '7px 16px', borderRadius: 'var(--r-pill)', border: 'none',
              background: category === cat ? 'var(--ink)' : 'var(--card)',
              color: category === cat ? '#fff' : 'var(--ink)',
              fontWeight: 600, fontSize: 13,
              boxShadow: category === cat ? 'none' : 'var(--shadow)',
              cursor: 'pointer',
            }}>{cat}</button>
          ))}
        </div>

        {/* Grid */}
        {isLoading ? (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 20 }}>
            {Array.from({ length: 8 }).map((_, i) => (
              <div key={i} style={{ background: 'var(--line)', borderRadius: 'var(--r)', height: 340, animation: 'pulse 1.5s infinite' }} />
            ))}
          </div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 20 }}>
            {products.map(p => <ProductCard key={p.id} product={p} />)}
          </div>
        )}

        {/* Load more */}
        {data && page + 1 < data.totalPages && (
          <div style={{ textAlign: 'center', marginTop: 36 }}>
            <button onClick={() => setPage(p => p + 1)} style={{
              padding: '11px 28px', border: '1.5px solid var(--ink)',
              borderRadius: 'var(--r-pill)', background: 'transparent',
              fontWeight: 600, fontSize: 14,
            }}>
              Load more · <span className="num">{products.length}</span> of <span className="num">{data.totalElements}</span>
            </button>
          </div>
        )}
      </main>
    </>
  )
}

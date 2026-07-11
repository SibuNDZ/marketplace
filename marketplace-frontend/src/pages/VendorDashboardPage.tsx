import React, { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, Page, ProductResponse, ApiError } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { StockBadge } from '../components/ui/StockBadge'

export function VendorDashboardPage() {
  const qc = useQueryClient()
  const [activeTab, setActiveTab] = useState<'live' | 'archived'>('live')

  const { data, isLoading } = useQuery<Page<ProductResponse>>({
    queryKey: ['vendor-products'],
    queryFn: () => api('/api/v1/products?size=100'),
  })

  const adjustStock = useMutation({
    mutationFn: ({ id, delta }: { id: number; delta: number }) =>
      api(`/api/v1/products/${id}/stock`, { method: 'POST', body: { delta } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['vendor-products'] }),
  })

  const softDelete = useMutation({
    mutationFn: (id: number) => api(`/api/v1/products/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['vendor-products'] }),
  })

  const live = (data?.content ?? []).filter(p => !p.deletedAt)
  const archived = (data?.content ?? []).filter(p => p.deletedAt)
  const products = activeTab === 'live' ? live : archived

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28 }}>Your stall</h1>
          <button style={{ padding: '9px 18px', background: 'var(--aloe)', color: '#fff', border: 'none', borderRadius: 'var(--r-sm)', fontWeight: 600 }}>
            + New product
          </button>
        </div>

        {/* Tabs */}
        <div style={{ display: 'flex', gap: 0, borderBottom: '2px solid var(--line)', marginBottom: 20 }}>
          {(['live', 'archived'] as const).map(tab => (
            <button key={tab} onClick={() => setActiveTab(tab)} style={{
              padding: '8px 20px', background: 'none', border: 'none',
              borderBottom: `2px solid ${activeTab === tab ? 'var(--aloe)' : 'transparent'}`,
              fontWeight: activeTab === tab ? 700 : 500, color: activeTab === tab ? 'var(--aloe)' : 'var(--ink-soft)',
              marginBottom: -2, textTransform: 'capitalize',
            }}>{tab} ({tab === 'live' ? live.length : archived.length})</button>
          ))}
        </div>

        {isLoading ? <p>Loading…</p> : (
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ textAlign: 'left', borderBottom: '1px solid var(--line)' }}>
                {['Product', 'SKU', 'Price', 'Stock', ''].map(h => (
                  <th key={h} style={{ padding: '10px 12px', fontSize: 12, fontWeight: 600, color: 'var(--ink-soft)' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {products.map(p => (
                <tr key={p.id} style={{ borderBottom: '1px solid var(--line)', opacity: p.deletedAt ? 0.5 : 1 }}>
                  <td style={{ padding: '12px 12px', fontWeight: 600 }}>{p.name}</td>
                  <td className="num" style={{ padding: '12px 12px', fontSize: 13, color: 'var(--ink-soft)' }}>{p.sku ?? '—'}</td>
                  <td className="num" style={{ padding: '12px 12px' }}>R{Number(p.price).toFixed(2)}</td>
                  <td style={{ padding: '12px 12px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <button onClick={() => adjustStock.mutate({ id: p.id, delta: -1 })}
                        style={{ width: 24, height: 24, border: '1px solid var(--line)', borderRadius: 4, background: 'none', fontSize: 14 }}>−</button>
                      <span className="num" style={{ minWidth: 28, textAlign: 'center', fontWeight: 600 }}>{p.stock}</span>
                      <button onClick={() => adjustStock.mutate({ id: p.id, delta: 1 })}
                        style={{ width: 24, height: 24, border: '1px solid var(--line)', borderRadius: 4, background: 'none', fontSize: 14 }}>+</button>
                    </div>
                  </td>
                  <td style={{ padding: '12px 12px' }}>
                    {!p.deletedAt && (
                      <button onClick={() => { if (confirm('Delete this product?')) softDelete.mutate(p.id) }}
                        style={{ fontSize: 12, color: 'var(--clay)', background: 'none', border: 'none', cursor: 'pointer' }}>
                        Delete
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </main>
    </>
  )
}

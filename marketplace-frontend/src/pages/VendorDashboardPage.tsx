import React, { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, Page, ProductResponse, VendorSettings } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'

/**
 * Inline editor for the vendor's flat delivery fee (Task 2.3). Charged once
 * per order containing their items; R0 means free delivery. Kept as a small
 * self-contained block so the dashboard stays a product table at heart.
 */
function DeliveryFeeEditor() {
  const qc = useQueryClient()
  const { data } = useQuery<VendorSettings>({
    queryKey: ['vendor-settings'],
    queryFn: () => api('/api/v1/vendor/settings'),
  })
  const [fee, setFee] = useState('')
  const [saved, setSaved] = useState(false)
  useEffect(() => {
    if (data) setFee(Number(data.deliveryFee).toFixed(2))
  }, [data])

  const save = useMutation({
    mutationFn: () => api('/api/v1/vendor/settings', { method: 'PUT', body: { deliveryFee: fee } }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['vendor-settings'] })
      setSaved(true)
      setTimeout(() => setSaved(false), 2500)
    },
  })

  const dirty = data != null && fee !== Number(data.deliveryFee).toFixed(2)

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10, marginBottom: 20,
      border: '1px solid var(--line)', borderRadius: 'var(--r-sm)', padding: '10px 14px',
    }}>
      <label htmlFor="delivery-fee" style={{ fontSize: 13, fontWeight: 600 }}>
        Delivery fee per order
      </label>
      <span style={{ fontSize: 13, color: 'var(--ink-soft)' }}>R</span>
      <input
        id="delivery-fee"
        type="number" min="0" step="0.01" inputMode="decimal"
        value={fee}
        onChange={e => setFee(e.target.value)}
        style={{
          width: 90, padding: '6px 8px', border: '1px solid var(--line)',
          borderRadius: 4, fontSize: 14, textAlign: 'right',
        }}
      />
      {dirty && (
        <button onClick={() => save.mutate()} disabled={save.isPending} style={{
          padding: '6px 14px', background: 'var(--aloe)', color: '#fff', border: 'none',
          borderRadius: 4, fontWeight: 600, fontSize: 13, cursor: 'pointer',
        }}>
          {save.isPending ? 'Saving…' : 'Save'}
        </button>
      )}
      {saved && <span style={{ fontSize: 12, color: 'var(--aloe)' }}>Saved</span>}
      {save.isError && <span style={{ fontSize: 12, color: 'var(--clay)' }}>Could not save. Check the amount.</span>}
      <span style={{ fontSize: 12, color: 'var(--ink-soft)', marginLeft: 'auto' }}>
        Charged once per order containing your items. R0.00 means free delivery.
      </span>
    </div>
  )
}

export function VendorDashboardPage() {
  const qc = useQueryClient()
  const [activeTab, setActiveTab] = useState<'live' | 'archived'>('live')
  // No global toast system — ProductFormPage passes a one-shot notice
  // (e.g. "image failed, retry from Edit") through router state instead.
  const location = useLocation()
  const [notice, setNotice] = useState<string | undefined>(
    (location.state as { notice?: string } | null)?.notice,
  )

  // /mine, not the public catalog list: the dashboard must show ONLY this
  // vendor's products (including archived ones), not the whole marketplace.
  const { data, isLoading } = useQuery<Page<ProductResponse>>({
    queryKey: ['vendor-products'],
    queryFn: () => api('/api/v1/products/mine?size=100'),
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
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <Link to="/vendor/orders" style={{
              padding: '9px 18px', border: '1px solid var(--line)', color: 'var(--ink)',
              borderRadius: 'var(--r-sm)', fontWeight: 700, display: 'inline-block',
            }}>
              Orders to fulfil
            </Link>
            <Link to="/vendor/products/new" style={{
              padding: '9px 18px', background: 'var(--flame-gradient)', color: '#fff',
              borderRadius: 'var(--r-sm)', fontWeight: 700, display: 'inline-block',
            }}>
              + New product
            </Link>
          </div>
        </div>

        <DeliveryFeeEditor />

        {notice && (
          <div style={{
            background: 'var(--sun-tint)', border: '1px solid var(--sun)',
            borderRadius: 'var(--r-sm)', padding: '10px 14px', marginBottom: 16,
            display: 'flex', alignItems: 'center', justifyContent: 'space-between', fontSize: 13,
          }}>
            <span>{notice}</span>
            <button onClick={() => setNotice(undefined)} style={{
              background: 'none', border: 'none', fontSize: 16, cursor: 'pointer', color: 'var(--ink-soft)',
            }} aria-label="Dismiss">×</button>
          </div>
        )}

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
                {['Product', 'SKU', 'Category', 'Price', 'Stock', ''].map(h => (
                  <th key={h} style={{ padding: '10px 12px', fontSize: 12, fontWeight: 600, color: 'var(--ink-soft)' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {products.map(p => (
                <tr key={p.id} style={{ borderBottom: '1px solid var(--line)', opacity: p.deletedAt ? 0.5 : 1 }}>
                  <td style={{ padding: '12px 12px', fontWeight: 600 }}>{p.name}</td>
                  <td className="num" style={{ padding: '12px 12px', fontSize: 13, color: 'var(--ink-soft)' }}>{p.sku ?? '-'}</td>
                  <td style={{ padding: '12px 12px', fontSize: 13, color: 'var(--ink-soft)' }}>
                    {/* The response carries the resolved name, so the
                        client-side key-to-label lookup table is gone. */}
                    {p.categoryName}
                    {p.handmade && (
                      <span title="Handmade" style={{ marginLeft: 6 }} aria-label="Handmade">🧵</span>
                    )}
                  </td>
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
                      <div style={{ display: 'flex', gap: 12 }}>
                        <Link to={`/vendor/products/${p.id}/edit`} style={{ fontSize: 12, color: 'var(--trust-blue)' }}>
                          Edit
                        </Link>
                        <button onClick={() => { if (confirm('Delete this product?')) softDelete.mutate(p.id) }}
                          style={{ fontSize: 12, color: 'var(--clay)', background: 'none', border: 'none', cursor: 'pointer' }}>
                          Delete
                        </button>
                      </div>
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

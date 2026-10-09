import React, { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, Page, ProductResponse } from '../lib/api'
import { imageUrlAt } from '../lib/productImage'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { useAuth } from '../context/AuthContext'

const PAGE_SIZE = 50

/**
 * Every live listing on the marketplace, for moderation. The Terms reserve
 * eRestyu's right to remove listings that break the rules (counterfeits,
 * unauthorised brands, test stock); this is where that happens, without
 * signing in as the seller.
 *
 * Remove is the same soft delete a seller's own Delete does: the listing
 * leaves the catalogue at once, the seller still sees it under Archived,
 * and past orders keep their lines. The server logs which admin removed
 * which listing from which store.
 */
export function AdminListingsPage() {
  const { user } = useAuth()
  const qc = useQueryClient()
  const [query, setQuery] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [error, setError] = useState<string>()

  const { data, isLoading } = useQuery<Page<ProductResponse>>({
    queryKey: ['admin-listings', search, page],
    queryFn: () => api(`/api/v1/products?page=${page}&size=${PAGE_SIZE}`
      + (search ? `&name=${encodeURIComponent(search)}` : '&sort=createdAt,desc')),
    enabled: user?.role === 'ADMIN',
  })

  const remove = useMutation({
    mutationFn: (id: number) => api(`/api/v1/products/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      setError(undefined)
      qc.invalidateQueries({ queryKey: ['admin-listings'] })
      qc.invalidateQueries({ queryKey: ['products'] })
    },
    onError: () => setError('That listing could not be removed. Refresh and try again.'),
  })

  const confirmRemove = (p: ProductResponse) => {
    const store = p.vendorName ?? 'its store'
    if (window.confirm(
      `Remove "${p.name}" from ${store}?\n\n`
      + 'It leaves the catalogue immediately. The seller still sees it under Archived, '
      + 'and past orders are not affected.',
    )) remove.mutate(p.id)
  }

  const submitSearch = (e: FormEvent) => {
    e.preventDefault()
    setPage(0)
    setSearch(query.trim())
  }

  const listings = data?.content ?? []
  const cell: React.CSSProperties = { padding: '10px 12px', fontSize: 13, verticalAlign: 'middle' }

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 20 }}>
          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28 }}>Admin</h1>
          <nav style={{ display: 'flex', gap: 2 }} aria-label="Admin sections">
            <Link to="/admin" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Orders</Link>
            <Link to="/admin/feedback" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Feedback</Link>
            <Link to="/admin/payouts" style={{ padding: '6px 14px', fontSize: 13, fontWeight: 600, color: 'var(--ink-soft)' }}>Payouts</Link>
            <span style={{ padding: '6px 14px', fontSize: 13, fontWeight: 700, color: 'var(--aloe)', borderBottom: '2px solid var(--aloe)' }}>Listings</span>
          </nav>
        </div>

        {user && user.role !== 'ADMIN' ? (
          <p className="muted-copy">This page is for eRestyu admins.</p>
        ) : (
          <>
            <form onSubmit={submitSearch} style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap', alignItems: 'center' }}>
              <input value={query} onChange={e => setQuery(e.target.value)}
                placeholder="Search products or stores" aria-label="Search listings"
                style={{ flex: '1 1 260px', maxWidth: 420, padding: '8px 12px', border: '1px solid var(--line)', borderRadius: 'var(--r-sm)', fontSize: 14 }} />
              <button type="submit" className="btn-outline" style={{ padding: '8px 18px' }}>Search</button>
              {search && (
                <button type="button" onClick={() => { setQuery(''); setSearch(''); setPage(0) }}
                  style={{ background: 'none', border: 'none', color: 'var(--ink-soft)', fontSize: 13, cursor: 'pointer' }}>
                  Clear
                </button>
              )}
              {data && (
                <span style={{ fontSize: 13, color: 'var(--ink-soft)', marginLeft: 'auto' }}>
                  <span className="num">{data.totalElements}</span> live listing{data.totalElements === 1 ? '' : 's'}
                  {search ? ` matching “${search}”` : ''}
                </span>
              )}
            </form>

            {error && <p role="alert" style={{ color: 'var(--clay)', fontSize: 13, marginBottom: 12 }}>{error}</p>}

            {isLoading ? <p>Loading…</p> : listings.length === 0 ? (
              <p className="muted-copy">{search ? 'No live listings match that search.' : 'There are no live listings.'}</p>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 760 }}>
                  <thead>
                    <tr style={{ textAlign: 'left', borderBottom: '1px solid var(--line)' }}>
                      {['', 'Product', 'Store', 'Price', 'Stock', 'Listed', ''].map((h, i) => (
                        <th key={i} style={{ padding: '10px 12px', fontSize: 12, fontWeight: 600, color: 'var(--ink-soft)' }}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {listings.map(p => (
                      <tr key={p.id} style={{ borderBottom: '1px solid var(--line)' }}>
                        <td style={{ ...cell, width: 64 }}>
                          {p.imageUrl
                            ? <img src={imageUrlAt(p.imageUrl, 120)} alt="" width={48} height={48}
                                style={{ objectFit: 'cover', borderRadius: 'var(--r-sm)', display: 'block' }} />
                            : <div aria-hidden style={{ width: 48, height: 48, borderRadius: 'var(--r-sm)', background: 'var(--line)' }} />}
                        </td>
                        <td style={{ ...cell, fontWeight: 600 }}>
                          <Link to={`/products/${p.id}`} style={{ color: 'var(--ink)' }}>{p.name}</Link>
                          <div style={{ fontSize: 12, fontWeight: 400, color: 'var(--ink-soft)' }}>{p.categoryName}</div>
                        </td>
                        <td style={cell}>
                          {p.vendorId != null
                            ? <Link to={`/shop/${p.vendorId}`} style={{ color: 'var(--trust-blue)' }}>{p.vendorName ?? `Store ${p.vendorId}`}</Link>
                            : (p.vendorName ?? '-')}
                        </td>
                        <td className="num" style={cell}>R{Number(p.price).toFixed(2)}</td>
                        <td className="num" style={cell}>{p.stock}</td>
                        <td style={{ ...cell, color: 'var(--ink-soft)', whiteSpace: 'nowrap' }}>
                          {new Date(p.createdAt).toLocaleDateString()}
                        </td>
                        <td style={{ ...cell, textAlign: 'right' }}>
                          <button onClick={() => confirmRemove(p)}
                            disabled={remove.isPending && remove.variables === p.id}
                            aria-label={`Remove ${p.name}`}
                            style={{ fontSize: 12, fontWeight: 600, color: 'var(--clay)', background: 'none',
                              border: '1px solid var(--line)', borderRadius: 'var(--r-sm)', padding: '5px 12px', cursor: 'pointer' }}>
                            {remove.isPending && remove.variables === p.id ? 'Removing…' : 'Remove'}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {data && data.totalPages > 1 && (
              <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginTop: 16, fontSize: 13 }}>
                <button className="btn-outline" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</button>
                <span>Page <span className="num">{page + 1}</span> of <span className="num">{data.totalPages}</span></span>
                <button className="btn-outline" disabled={page + 1 >= data.totalPages} onClick={() => setPage(p => p + 1)}>Next</button>
              </div>
            )}
          </>
        )}
      </main>
    </>
  )
}

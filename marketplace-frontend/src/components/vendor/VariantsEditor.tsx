import React, { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, ProductResponse, VariantRequest, VariantResponse, fieldErrorsFrom } from '../../lib/api'

/**
 * The vendor's size/colour options editor — the missing half of variants.
 * Shoppers have had VariantSelector since V20/V25, but nothing in the
 * product form could CREATE an option, so the picker never appeared on any
 * real listing.
 *
 * Edit mode only: the endpoints hang off a productId, so a new product has
 * to exist before it can grow options (the form says so in create mode).
 *
 * Mutations hit the API immediately rather than staging until the form's
 * Save — same reasoning as photo deletion in ProductFormPage: the variant
 * endpoints are their own resource with their own validation, and every
 * response is the full ProductResponse, which replaces the cached product
 * directly instead of triggering a refetch.
 */

interface Props {
  productId: string
  variants: VariantResponse[]
}

const input: React.CSSProperties = {
  padding: '8px 10px', border: '1.5px solid var(--line)',
  borderRadius: 'var(--r-sm)', fontFamily: 'var(--body)', fontSize: 13,
}

/** First useful sentence out of an ApiError: field message over generic. */
function messageFrom(e: unknown): string {
  if (e instanceof ApiError) {
    const first = Object.values(fieldErrorsFrom(e)).flat()[0]
    return first || e.detail || e.title
  }
  return 'Something went wrong'
}

function VariantRow({ productId, variant }: { productId: string; variant: VariantResponse }) {
  const qc = useQueryClient()
  const [draft, setDraft] = useState({
    label: variant.label, price: variant.price, stock: String(variant.stock),
  })
  const [error, setError] = useState<string>()

  const dirty = draft.label !== variant.label
    || draft.price !== variant.price
    || draft.stock !== String(variant.stock)

  const update = useMutation({
    mutationFn: (body: VariantRequest) =>
      api<ProductResponse>(`/api/v1/products/${productId}/variants/${variant.id}`, { method: 'PUT', body }),
    onSuccess: (product) => { setError(undefined); qc.setQueryData(['product', productId], product) },
    onError: (e) => setError(messageFrom(e)),
  })

  const remove = useMutation({
    mutationFn: () =>
      api<ProductResponse>(`/api/v1/products/${productId}/variants/${variant.id}`, { method: 'DELETE' }),
    onSuccess: (product) => qc.setQueryData(['product', productId], product),
    onError: (e) => setError(messageFrom(e)),
  })

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <input aria-label={`Label for ${variant.label}`} value={draft.label} maxLength={100}
          onChange={e => setDraft(d => ({ ...d, label: e.target.value }))}
          style={{ ...input, flex: '2 1 120px' }} />
        <input aria-label={`Price for ${variant.label}`} type="number" min="0.01" step="0.01" value={draft.price}
          onChange={e => setDraft(d => ({ ...d, price: e.target.value }))}
          style={{ ...input, flex: '1 1 90px' }} />
        <input aria-label={`Stock for ${variant.label}`} type="number" min="0" step="1" value={draft.stock}
          onChange={e => setDraft(d => ({ ...d, stock: e.target.value }))}
          style={{ ...input, flex: '1 1 70px' }} />
        <button type="button" disabled={!dirty || update.isPending}
          onClick={() => update.mutate({ label: draft.label, price: draft.price, stock: Number(draft.stock) })}
          style={{
            padding: '7px 12px', fontSize: 12, fontWeight: 600, borderRadius: 'var(--r-sm)',
            border: '1.5px solid var(--line)', background: 'var(--card)',
            opacity: dirty ? 1 : 0.4, cursor: dirty ? 'pointer' : 'default',
          }}>
          {update.isPending ? 'Saving…' : 'Save'}
        </button>
        <button type="button" aria-label={`Remove option ${variant.label}`}
          disabled={remove.isPending} onClick={() => remove.mutate()}
          style={{
            width: 28, height: 28, borderRadius: '50%', border: '1px solid var(--line)',
            background: 'var(--card)', cursor: 'pointer', fontSize: 13, lineHeight: 1,
          }}>×</button>
      </div>
      {error && <span style={{ fontSize: 12, color: 'var(--clay)' }}>{error}</span>}
    </div>
  )
}

export function VariantsEditor({ productId, variants }: Props) {
  const qc = useQueryClient()
  const [draft, setDraft] = useState({ label: '', price: '', stock: '' })
  const [error, setError] = useState<string>()

  const add = useMutation({
    mutationFn: (body: VariantRequest) =>
      api<ProductResponse>(`/api/v1/products/${productId}/variants`, { method: 'POST', body }),
    onSuccess: (product) => {
      setError(undefined)
      setDraft({ label: '', price: '', stock: '' })
      qc.setQueryData(['product', productId], product)
    },
    onError: (e) => setError(messageFrom(e)),
  })

  const canAdd = draft.label.trim() !== '' && draft.price !== '' && draft.stock !== ''

  return (
    // The editor renders inside the product form. Enter in any of its inputs
    // must not submit that form — same trap the tags field guards against;
    // here it would save a half-edited product instead of adding the option.
    <div
      onKeyDown={e => { if (e.key === 'Enter') e.preventDefault() }}
      style={{ display: 'flex', flexDirection: 'column', gap: 10 }}
    >
      {variants.length > 0 && (
        <div style={{ display: 'flex', gap: 8, fontSize: 11, fontWeight: 600, color: 'var(--ink-soft)' }}>
          <span style={{ flex: '2 1 120px' }}>Option</span>
          <span style={{ flex: '1 1 90px' }}>Price (R)</span>
          <span style={{ flex: '1 1 70px' }}>Stock</span>
          {/* spacers matching the row buttons */}
          <span style={{ width: 64 }} />
          <span style={{ width: 28 }} />
        </div>
      )}
      {variants.map(v => <VariantRow key={v.id} productId={productId} variant={v} />)}

      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <input aria-label="New option label" placeholder="e.g. 34, or Black / XL" maxLength={100}
          value={draft.label} onChange={e => setDraft(d => ({ ...d, label: e.target.value }))}
          style={{ ...input, flex: '2 1 120px' }} />
        <input aria-label="New option price" type="number" min="0.01" step="0.01" placeholder="Price"
          value={draft.price} onChange={e => setDraft(d => ({ ...d, price: e.target.value }))}
          style={{ ...input, flex: '1 1 90px' }} />
        <input aria-label="New option stock" type="number" min="0" step="1" placeholder="Stock"
          value={draft.stock} onChange={e => setDraft(d => ({ ...d, stock: e.target.value }))}
          style={{ ...input, flex: '1 1 70px' }} />
        <button type="button" disabled={!canAdd || add.isPending}
          onClick={() => add.mutate({ label: draft.label.trim(), price: draft.price, stock: Number(draft.stock) })}
          style={{
            padding: '7px 12px', fontSize: 12, fontWeight: 700, borderRadius: 'var(--r-sm)',
            border: 'none', background: 'var(--ink)', color: '#fff',
            opacity: canAdd ? 1 : 0.4, cursor: canAdd ? 'pointer' : 'default',
            whiteSpace: 'nowrap',
          }}>
          {add.isPending ? 'Adding…' : 'Add option'}
        </button>
      </div>
      {error && <span style={{ fontSize: 12, color: 'var(--clay)' }}>{error}</span>}
    </div>
  )
}

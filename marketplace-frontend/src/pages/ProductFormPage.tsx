import React, { FormEvent, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, ProductCategoryKey, ProductRequest, ProductResponse, fieldErrorsFrom } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { ErrorSurface } from '../components/ui/ErrorSurface'
import { CATEGORIES } from '../data/categories'

const EMPTY: ProductRequest = {
  name: '', description: '', sku: '', price: '', stock: 0, category: 'OTHER',
}

function Field({ label, error, children }: { label: string; error?: string[]; children: React.ReactNode }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
      {label}
      {children}
      {error?.map((msg, i) => (
        <span key={i} style={{ fontSize: 12, color: 'var(--clay)', fontWeight: 400 }}>{msg}</span>
      ))}
    </label>
  )
}

const inputStyle = (hasError?: boolean): React.CSSProperties => ({
  padding: '9px 12px', border: `1.5px solid ${hasError ? 'var(--clay)' : 'var(--line)'}`,
  borderRadius: 'var(--r-sm)', fontFamily: 'var(--body)', fontSize: 14,
})

export function ProductFormPage() {
  const { id } = useParams()
  const isEdit = !!id
  const navigate = useNavigate()
  const qc = useQueryClient()

  const [form, setForm] = useState<ProductRequest>(EMPTY)
  const [genericError, setGenericError] = useState<ApiError>()
  const [fieldErrors, setFieldErrors] = useState<Record<string, string[]>>({})

  const { data: existing } = useQuery<ProductResponse>({
    queryKey: ['product', id],
    queryFn: () => api(`/api/v1/products/${id}`),
    enabled: isEdit,
  })

  useEffect(() => {
    if (existing) {
      setForm({
        name: existing.name,
        description: existing.description ?? '',
        sku: existing.sku ?? '',
        price: existing.price,
        stock: existing.stock,
        category: existing.category,
      })
    }
  }, [existing])

  const set = <K extends keyof ProductRequest>(key: K, value: ProductRequest[K]) =>
    setForm(f => ({ ...f, [key]: value }))

  const save = useMutation({
    mutationFn: () => isEdit
      ? api<ProductResponse>(`/api/v1/products/${id}`, { method: 'PUT', body: form })
      : api<ProductResponse>('/api/v1/products', { method: 'POST', body: form }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['vendor-products'] })
      qc.invalidateQueries({ queryKey: ['products'] })
      navigate('/vendor')
    },
    onError: (e) => {
      if (e instanceof ApiError) {
        const fe = fieldErrorsFrom(e)
        setFieldErrors(fe)
        // Only fall back to the generic surface when nothing could be
        // pinned to a field — an inline error next to the SKU input is
        // more useful than a toast repeating the same sentence.
        setGenericError(Object.keys(fe).length === 0 ? e : undefined)
      }
    },
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setFieldErrors({})
    setGenericError(undefined)
    save.mutate()
  }

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail" style={{ maxWidth: 560 }}>
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 26, marginBottom: 24 }}>
          {isEdit ? 'Edit product' : 'New product'}
        </h1>

        <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {genericError && <ErrorSurface error={genericError} onDismiss={() => setGenericError(undefined)} />}

          <Field label="Name" error={fieldErrors.name}>
            <input required value={form.name} onChange={e => set('name', e.target.value)} style={inputStyle(!!fieldErrors.name)} />
          </Field>

          <Field label="Description" error={fieldErrors.description}>
            <textarea rows={3} value={form.description} onChange={e => set('description', e.target.value)}
              style={{ ...inputStyle(!!fieldErrors.description), resize: 'vertical', fontFamily: 'var(--body)' }} />
          </Field>

          <div style={{ display: 'flex', gap: 12 }}>
            <div style={{ flex: 1 }}>
              <Field label="SKU" error={fieldErrors.sku}>
                <input required value={form.sku} onChange={e => set('sku', e.target.value)} style={inputStyle(!!fieldErrors.sku)} />
              </Field>
            </div>
            <div style={{ flex: 1 }}>
              <Field label="Category" error={fieldErrors.category}>
                <select required value={form.category} onChange={e => set('category', e.target.value as ProductCategoryKey)}
                  style={inputStyle(!!fieldErrors.category)}>
                  {CATEGORIES.map(c => <option key={c.key} value={c.key}>{c.icon} {c.label}</option>)}
                </select>
              </Field>
            </div>
          </div>

          <div style={{ display: 'flex', gap: 12 }}>
            <div style={{ flex: 1 }}>
              <Field label="Price (R)" error={fieldErrors.price}>
                <input required type="number" min="0.01" step="0.01" value={form.price}
                  onChange={e => set('price', e.target.value)} style={inputStyle(!!fieldErrors.price)} />
              </Field>
            </div>
            <div style={{ flex: 1 }}>
              <Field label="Stock" error={fieldErrors.stock}>
                <input required type="number" min="0" step="1" value={form.stock}
                  onChange={e => set('stock', Number(e.target.value))} style={inputStyle(!!fieldErrors.stock)} />
              </Field>
            </div>
          </div>

          <div style={{ display: 'flex', gap: 10, marginTop: 6 }}>
            <button type="submit" disabled={save.isPending} style={{
              flex: 1, padding: '11px 20px', background: 'var(--flame-gradient)', color: '#fff',
              border: 'none', borderRadius: 'var(--r-sm)', fontWeight: 700, fontSize: 15,
            }}>
              {save.isPending ? 'Saving…' : isEdit ? 'Save changes' : 'Create product'}
            </button>
            <button type="button" onClick={() => navigate('/vendor')} style={{
              padding: '11px 20px', background: 'none', border: '1.5px solid var(--line)',
              borderRadius: 'var(--r-sm)', fontWeight: 600, fontSize: 15,
            }}>
              Cancel
            </button>
          </div>
        </form>
      </main>
    </>
  )
}

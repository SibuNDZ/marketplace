import React, { FormEvent, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, CategoryOption, ProductRequest, ProductResponse, categories as categoriesApi, fieldErrorsFrom, uploadProductImage } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { ErrorSurface } from '../components/ui/ErrorSurface'

const MAX_IMAGE_BYTES = 5 * 1024 * 1024
const ACCEPTED_IMAGE_TYPES = 'image/jpeg,image/png,image/webp'

const EMPTY: ProductRequest = {
  name: '', description: '', sku: '', price: '', stock: 0,
  categorySlug: '', handmade: false, tags: [],
}

const MAX_TAGS = 10

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

  const [imageFile, setImageFile] = useState<File | null>(null)
  const [imagePreview, setImagePreview] = useState<string | null>(null)
  const [imageError, setImageError] = useState<string>()

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
        categorySlug: existing.categorySlug,
        handmade: existing.handmade,
        tags: existing.tags ?? [],
      })
    }
  }, [existing])

  // Object URLs must be revoked or they leak for the tab's lifetime.
  useEffect(() => {
    if (!imageFile) return
    const url = URL.createObjectURL(imageFile)
    setImagePreview(url)
    return () => URL.revokeObjectURL(url)
  }, [imageFile])

  const set = <K extends keyof ProductRequest>(key: K, value: ProductRequest[K]) =>
    setForm(f => ({ ...f, [key]: value }))

  // includeEmpty=true: a brand-new category has no products yet and still
  // has to be selectable, otherwise nothing could ever become the first
  // product in it. The shopper-facing tree uses the opposite default.
  const { data: categoryOptions } = useQuery<CategoryOption[]>({
    queryKey: ['category-options'],
    queryFn: () => categoriesApi.options(),
    staleTime: 5 * 60 * 1000,
  })
  const options = categoryOptions ?? []
  const roots = options.filter(c => c.parentSlug === null)
  const childrenOf = (slug: string) => options.filter(c => c.parentSlug === slug)

  const [tagDraft, setTagDraft] = useState('')

  const commitTag = () => {
    // Normalised the same way the backend does on write, so what the vendor
    // sees in the chip is exactly what gets stored and filtered on.
    const tag = tagDraft.trim().toLowerCase()
    setTagDraft('')
    if (!tag || form.tags.includes(tag) || form.tags.length >= MAX_TAGS) return
    set('tags', [...form.tags, tag])
  }

  const onTagKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    // Enter must not submit the form — in a single-input row the browser
    // treats Enter as submit, which would save a half-filled product.
    if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); commitTag() }
    else if (e.key === 'Backspace' && !tagDraft && form.tags.length > 0) {
      set('tags', form.tags.slice(0, -1))
    }
  }

  const removeTag = (tag: string) => set('tags', form.tags.filter(t => t !== tag))

  const onImageChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    setImageError(undefined)
    if (!file) { setImageFile(null); return }
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
      setImageError('Only JPEG, PNG, or WebP images are allowed')
      e.target.value = ''
      return
    }
    if (file.size > MAX_IMAGE_BYTES) {
      setImageError('Image too large — 5MB max')
      e.target.value = ''
      return
    }
    setImageFile(file)
  }

  const save = useMutation({
    mutationFn: () => isEdit
      ? api<ProductResponse>(`/api/v1/products/${id}`, { method: 'PUT', body: form })
      : api<ProductResponse>('/api/v1/products', { method: 'POST', body: form }),
    onSuccess: async (saved) => {
      qc.invalidateQueries({ queryKey: ['vendor-products'] })
      qc.invalidateQueries({ queryKey: ['products'] })

      if (imageFile) {
        // A failed image upload does NOT mean the product save failed — the
        // product already exists. Navigate away regardless; pass a notice
        // through router state pointing back at Edit rather than blocking
        // on the retry (there's no toast system in this app to show it here
        // — the dashboard renders whatever notice arrives in location.state).
        try {
          await uploadProductImage(saved.id, imageFile)
          qc.invalidateQueries({ queryKey: ['product', String(saved.id)] })
        } catch {
          navigate('/vendor', { state: { notice: 'Product saved, but the image failed to upload — retry from Edit.' } })
          return
        }
      }
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

          <Field label="Photo" error={imageError ? [imageError] : undefined}>
            {(imagePreview ?? existing?.imageUrl) && (
              <img src={imagePreview ?? existing!.imageUrl!} alt="" style={{
                width: 120, height: 90, objectFit: 'cover', borderRadius: 'var(--r-sm)', marginBottom: 6,
              }} />
            )}
            <input type="file" accept={ACCEPTED_IMAGE_TYPES} onChange={onImageChange}
              style={{ fontSize: 13 }} />
          </Field>

          <div style={{ display: 'flex', gap: 12 }}>
            <div style={{ flex: 1 }}>
              <Field label="SKU" error={fieldErrors.sku}>
                <input required value={form.sku} onChange={e => set('sku', e.target.value)} style={inputStyle(!!fieldErrors.sku)} />
              </Field>
            </div>
            <div style={{ flex: 1 }}>
              <Field label="Category" error={fieldErrors.categorySlug}>
                {/* Grouped by parent so a 41-entry list stays navigable, and
                    roots are selectable too — a product that genuinely does
                    not fit any subcategory should not be forced into a wrong
                    one just to satisfy the picker. */}
                <select required value={form.categorySlug}
                  onChange={e => set('categorySlug', e.target.value)}
                  style={inputStyle(!!fieldErrors.categorySlug)}>
                  <option value="" disabled>Choose a category…</option>
                  {roots.map(root => (
                    <optgroup key={root.slug} label={root.name}>
                      <option value={root.slug}>{root.name} (general)</option>
                      {childrenOf(root.slug).map(c => (
                        <option key={c.slug} value={c.slug}>{c.name}</option>
                      ))}
                    </optgroup>
                  ))}
                </select>
              </Field>
            </div>
          </div>

          <Field label="Tags" error={fieldErrors.tags}>
            <input
              value={tagDraft}
              onChange={e => setTagDraft(e.target.value)}
              onKeyDown={onTagKeyDown}
              onBlur={commitTag}
              placeholder={form.tags.length >= MAX_TAGS
                ? `Maximum ${MAX_TAGS} tags`
                : 'Type a tag and press Enter'}
              disabled={form.tags.length >= MAX_TAGS}
              style={inputStyle(!!fieldErrors.tags)} />
            <span style={{ fontSize: 12, fontWeight: 400, color: 'var(--ink-soft)' }}>
              For anything the categories do not cover — “vegan”, “gluten-free”, “gift”.
            </span>
            {form.tags.length > 0 && (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
                {form.tags.map(tag => (
                  <button key={tag} type="button" onClick={() => removeTag(tag)} style={{
                    display: 'flex', alignItems: 'center', gap: 6,
                    padding: '4px 10px', borderRadius: 'var(--r-pill)',
                    border: '1px solid var(--line)', background: 'var(--paper)',
                    fontSize: 12, fontWeight: 500,
                  }}>
                    {tag}<span aria-label={`Remove ${tag}`} style={{ color: 'var(--ink-soft)' }}>×</span>
                  </button>
                ))}
              </div>
            )}
          </Field>

          <label style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 13, fontWeight: 500 }}>
            <input type="checkbox" checked={form.handmade}
              onChange={e => set('handmade', e.target.checked)}
              style={{ width: 16, height: 16 }} />
            <span>
              Handmade
              <span style={{ display: 'block', fontWeight: 400, fontSize: 12, color: 'var(--ink-soft)' }}>
                Independent of category — a handmade necklace still files under Fashion / Jewellery.
              </span>
            </span>
          </label>

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

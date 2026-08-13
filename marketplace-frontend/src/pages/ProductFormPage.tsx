import React, { FormEvent, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, CategoryOption, ProductRequest, ProductResponse, categories as categoriesApi, draftListingFromPhoto, fieldErrorsFrom, uploadProductImage } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { ErrorSurface } from '../components/ui/ErrorSurface'
import { imageUrlAt } from '../lib/productImage'

const MAX_IMAGE_BYTES = 5 * 1024 * 1024
const ACCEPTED_IMAGE_TYPES = 'image/jpeg,image/png,image/webp'

const EMPTY: ProductRequest = {
  name: '', description: '', sku: '', price: '', originalPrice: null, stock: 0,
  categorySlug: '', handmade: false, tags: [],
}

const MAX_TAGS = 10
// Mirrors ProductImageService.MAX_IMAGES. The backend is the enforcer; this
// is only so the form can say the number out loud before a vendor hits it.
const MAX_IMAGES = 8

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

// A field still holding unedited AI text. Tinted rather than outlined so it
// reads as "provisional", not "invalid" — the clay error border already owns
// the "something is wrong" signal and the two must not be confused.
const draftedStyle = (drafted: boolean, hasError?: boolean): React.CSSProperties =>
  drafted && !hasError
    ? { ...inputStyle(hasError), borderColor: 'var(--aloe)', background: 'var(--aloe-tint)' }
    : inputStyle(hasError)

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
        // Round-trips the current sale, so opening the form on a discounted
        // listing and saving does not silently end the discount.
        originalPrice: existing.originalPrice ?? null,
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
      setImageError('Image too large: 5MB max')
      e.target.value = ''
      return
    }
    setImageFile(file)
    // A new photo invalidates the previous draft: the fields on screen
    // describe an image the vendor just replaced.
    setDraftedFields(new Set())
    setDraftApplied(false)
    setTouchedDraft(false)
    setReviewed(false)
    setDraftError(undefined)
  }

  // ---- AI listing drafter -------------------------------------------------
  // Which fields currently hold AI-written text. A field leaves this set the
  // moment the vendor edits it, which is what turns "reviewed" from an
  // assumption into an observed action.
  const [draftedFields, setDraftedFields] = useState<Set<string>>(new Set())
  const [reviewed, setReviewed] = useState(false)
  const [draftError, setDraftError] = useState<string>()
  // A draft landed at some point. Distinct from draftedFields, which shrinks
  // as the vendor edits — this stays true so the banner does not vanish
  // mid-review.
  const [draftApplied, setDraftApplied] = useState(false)
  // The vendor has edited at least one drafted field. That is a review action
  // in itself, so it satisfies the gate without needing the checkbox too.
  const [touchedDraft, setTouchedDraft] = useState(false)

  const clearDrafted = (field: string) =>
    setDraftedFields(prev => {
      if (!prev.has(field)) return prev
      const next = new Set(prev)
      next.delete(field)
      return next
    })

  // set() that also marks a drafted field as vendor-touched.
  const setAndReview = <K extends keyof ProductRequest>(key: K, value: ProductRequest[K]) => {
    if (draftedFields.has(key as string)) setTouchedDraft(true)
    clearDrafted(key as string)
    set(key, value)
  }

  // Deleting a photo hits the API immediately rather than staging until
  // save: the object is already uploaded, so there is no draft state for it
  // to live in, and a vendor who removes a photo and then abandons the form
  // would otherwise be surprised to find it still there.
  const removeImage = useMutation({
    mutationFn: (imageId: number) =>
      api(`/api/v1/products/${id}/images/${imageId}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['product', id] }),
    onError: (e) => { if (e instanceof ApiError) setGenericError(e) },
  })

  const draft = useMutation({
    mutationFn: () => draftListingFromPhoto(imageFile!),
    onSuccess: (d) => {
      setForm(f => ({
        ...f,
        name: d.name,
        description: d.description,
        categorySlug: d.categorySlug,
        // price, stock, sku, tags and handmade are NEVER drafted — a
        // hallucinated price is the one that actually costs money, and the
        // rest are cheap for a vendor to set and expensive to get wrong.
      }))
      setDraftedFields(new Set(['name', 'description', 'categorySlug']))
      setDraftApplied(true)
      setTouchedDraft(false)
      setReviewed(false)
      setDraftError(undefined)
    },
    onError: (e) => {
      // The manual path stays fully usable — drafting is an accelerant, not
      // a gate — so this is a message, not a blocking error surface.
      if (e instanceof ApiError && e.status === 429) {
        setDraftError(e.detail || "You have used this hour's drafting allowance.")
      } else {
        setDraftError("Drafting didn't work. Fill the form manually or try again.")
      }
    },
  })

  const hasDraft = draftApplied
  // The review gate: once a draft lands, creating is blocked until the vendor
  // has either edited AT LEAST ONE drafted field or explicitly ticked the box.
  // Silently-accepted AI text on a commerce listing is the failure mode worth
  // engineering against, so review is an action rather than an assumption.
  //
  // "At least one", not "all three", on purpose. Requiring every field to be
  // retyped punishes a vendor whose draft was mostly right and pushes them
  // toward the checkbox as the faster path — which is the outcome the gate
  // exists to avoid. Editing one field proves they read the output.
  const awaitingReview = draftApplied && !touchedDraft && !reviewed

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
          navigate('/vendor', { state: { notice: 'Product saved, but the image failed to upload. Retry from Edit.' } })
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

          {/* Photo first in create mode: the drafter reads the photo, so
              asking for it up front is what makes "draft from photo" the
              natural next action rather than a feature buried mid-form. */}
          <Field label="Photos" error={imageError ? [imageError] : undefined}>
            {/* Existing gallery, editable in place. Only in edit mode: on a
                new product there is nothing uploaded yet, and the file input
                below is the whole story. */}
            {isEdit && (existing?.images?.length ?? 0) > 0 && (
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
                {existing!.images!.map((img, i) => (
                  <div key={img.id} style={{ position: 'relative' }}>
                    <img src={imageUrlAt(img.url, 240)} alt="" style={{
                      width: 96, height: 96, objectFit: 'cover',
                      borderRadius: 'var(--r-sm)', display: 'block',
                      border: i === 0 ? '2px solid var(--ink)' : '1px solid var(--line)',
                    }} />
                    {/* The first photo is the one every card, cart row and
                        rail shows, so say which one that is rather than
                        leaving the vendor to discover it. */}
                    {i === 0 && (
                      <span style={{
                        position: 'absolute', bottom: 4, left: 4, fontSize: 10, fontWeight: 700,
                        background: 'var(--ink)', color: '#fff', padding: '1px 5px',
                        borderRadius: 'var(--r-pill)',
                      }}>Cover</span>
                    )}
                    <button
                      type="button"
                      aria-label={`Remove photo ${i + 1}`}
                      disabled={removeImage.isPending}
                      onClick={() => removeImage.mutate(img.id)}
                      style={{
                        position: 'absolute', top: -6, right: -6, width: 22, height: 22,
                        borderRadius: '50%', border: '1px solid var(--line)',
                        background: 'var(--card)', cursor: 'pointer', fontSize: 12, lineHeight: 1,
                      }}
                    >×</button>
                  </div>
                ))}
              </div>
            )}

            {imagePreview && (
              <img src={imagePreview} alt="" style={{
                width: 120, height: 90, objectFit: 'cover', borderRadius: 'var(--r-sm)', marginBottom: 6,
              }} />
            )}
            <input type="file" accept={ACCEPTED_IMAGE_TYPES} onChange={onImageChange}
              style={{ fontSize: 13 }} />
            <span style={{ fontSize: 12, color: 'var(--ink-soft)', fontWeight: 400 }}>
              {isEdit
                ? `Adds another photo. The first one is the cover shown on cards. Up to ${MAX_IMAGES} per product.`
                : `You can add more photos after saving. Up to ${MAX_IMAGES} per product.`}
            </span>
          </Field>

          {!isEdit && (
            <>
              <button type="button"
                onClick={() => draft.mutate()}
                disabled={!imageFile || draft.isPending}
                style={{
                  background: 'transparent', color: 'var(--ink)',
                  border: '1.5px solid var(--line)', borderRadius: 'var(--r-sm)',
                  padding: '10px', fontWeight: 600, fontSize: 14,
                  opacity: !imageFile || draft.isPending ? 0.5 : 1,
                  cursor: !imageFile || draft.isPending ? 'default' : 'pointer',
                }}>
                {draft.isPending ? 'Drafting…' : 'Draft listing from photo'}
              </button>

              {draftError && (
                <p style={{ fontSize: 13, color: 'var(--clay)', margin: 0 }}>{draftError}</p>
              )}

              {hasDraft && (
                <div style={{
                  background: 'var(--aloe-tint)', border: '1px solid var(--aloe)',
                  borderRadius: 'var(--r-sm)', padding: '10px 14px', fontSize: 13,
                  lineHeight: 1.5,
                }}>
                  <strong>AI draft: please review.</strong> Written from your photo, so
                  check it describes what you are actually selling. Edit anything that is
                  wrong, then confirm below.
                </div>
              )}
            </>
          )}

          <Field label="Name" error={fieldErrors.name}>
            <input required value={form.name} onChange={e => setAndReview('name', e.target.value)}
              style={draftedStyle(draftedFields.has('name'), !!fieldErrors.name)} />
          </Field>

          <Field label="Description" error={fieldErrors.description}>
            <textarea rows={3} value={form.description} onChange={e => setAndReview('description', e.target.value)}
              style={{ ...draftedStyle(draftedFields.has('description'), !!fieldErrors.description), resize: 'vertical', fontFamily: 'var(--body)' }} />
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
                  onChange={e => setAndReview('categorySlug', e.target.value)}
                  style={draftedStyle(draftedFields.has('categorySlug'), !!fieldErrors.categorySlug)}>
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
              For anything the categories do not cover: “vegan”, “gluten-free”, “gift”.
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
                Independent of category: a handmade necklace still files under Fashion / Jewellery.
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

          {/* Optional, and left blank by default. The helper text is not
              boilerplate: under the Consumer Protection Act an advertised
              former price is a representation that the goods were actually
              offered at it, and the vendor is the only person who knows
              whether that is true. The system can enforce the arithmetic and
              nothing else, so it says so plainly here rather than letting a
              vendor discover it later. */}
          <div style={{ flex: 1 }}>
            <Field label="Original price (R) — optional" error={fieldErrors.originalPrice}>
              <input
                type="number" min="0.01" step="0.01"
                placeholder="Leave blank if not on sale"
                value={form.originalPrice ?? ''}
                onChange={e => set('originalPrice', e.target.value === '' ? null : e.target.value)}
                style={inputStyle(!!fieldErrors.originalPrice)}
              />
              <span style={{ fontSize: 12, color: 'var(--ink-soft)', fontWeight: 400 }}>
                Shows as a struck-through “was” price with a saving. Only use a
                price you actually sold at — advertising a former price you did
                not charge is prohibited under the Consumer Protection Act.
              </span>
            </Field>
          </div>

          {/* The review gate. Shown only while unedited AI text is still on
              screen; editing any drafted field clears it on its own, so a
              vendor who actually reviewed never has to tick anything. */}
          {awaitingReview && (
            <label style={{
              display: 'flex', alignItems: 'flex-start', gap: 8, fontSize: 13,
              lineHeight: 1.5, background: 'var(--paper)', border: '1px solid var(--line)',
              borderRadius: 'var(--r-sm)', padding: '10px 12px',
            }}>
              <input type="checkbox" checked={reviewed}
                onChange={e => setReviewed(e.target.checked)}
                style={{ marginTop: 2 }} />
              <span>
                I have reviewed this draft and confirm it describes my product accurately.
              </span>
            </label>
          )}

          <div style={{ display: 'flex', gap: 10, marginTop: 6 }}>
            <button type="submit" disabled={save.isPending || awaitingReview} style={{
              flex: 1, padding: '11px 20px', background: 'var(--flame-gradient)', color: '#fff',
              border: 'none', borderRadius: 'var(--r-sm)', fontWeight: 700, fontSize: 15,
              opacity: save.isPending || awaitingReview ? 0.5 : 1,
              cursor: awaitingReview ? 'default' : 'pointer',
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

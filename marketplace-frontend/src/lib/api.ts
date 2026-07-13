// src/lib/api.ts — the ONE module that talks HTTP.
// Owns: base URL, auth header injection, 401→refresh→retry (single-flight),
// RFC 7807 parsing, X-Request-Id exposure.

const BASE = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

// ---------- token store ----------
// Access token in MEMORY only (XSS can't lift it; worst case = 15 min window).
// Refresh token in localStorage — rotate-on-use + server-side reuse detection
// mean a stolen refresh token is detected and nukes all sessions on replay.
let accessToken: string | null = null
const REFRESH_KEY = 'mk.refresh'

export function setSession(access: string, refresh: string) {
  accessToken = access
  localStorage.setItem(REFRESH_KEY, refresh)
}
export function clearSession() {
  accessToken = null
  localStorage.removeItem(REFRESH_KEY)
}
export function hasRefreshToken() {
  return localStorage.getItem(REFRESH_KEY) !== null
}
export function getAccessToken() { return accessToken }

// ---------- RFC 7807 ----------
export interface Shortage {
  productId: number
  productName: string
  requested: number
  available: number
}
export class ApiError extends Error {
  constructor(
    public status: number,
    public title: string,
    public detail: string,
    public requestId?: string,
    public shortages?: Shortage[],
    public fieldErrors?: Record<string, string[]>,
  ) {
    super(detail || title)
  }
}

/**
 * Field-keyed errors for inline form rendering. Two sources, both real:
 *  - 400 validation: body.errors is already field-keyed (GlobalExceptionHandler).
 *  - 409 DuplicateSkuException: NOT a field error in the backend's shape —
 *    it's a plain detail string ("SKU already in use: X") on a Conflict
 *    response, because the backend's field-error envelope is specific to
 *    @Valid body validation. Special-cased here by status+title match so
 *    the create-product form can still point at the SKU input rather than
 *    falling back to a generic toast for the one error a vendor filling
 *    out the form is most likely to actually hit.
 */
export function fieldErrorsFrom(error: ApiError): Record<string, string[]> {
  if (error.fieldErrors) return error.fieldErrors
  if (error.status === 409 && error.title === 'Duplicate SKU') {
    return { sku: [error.detail || 'SKU already in use'] }
  }
  return {}
}

async function toApiError(res: Response): Promise<ApiError> {
  const requestId = res.headers.get('X-Request-Id') ?? undefined
  try {
    const body = await res.json()
    return new ApiError(
      res.status,
      body.title ?? 'Request failed',
      body.detail ?? '',
      body.requestId ?? requestId,
      body.shortages,
      body.errors,
    )
  } catch {
    return new ApiError(res.status, 'Request failed', res.statusText, requestId)
  }
}

// ---------- refresh (single-flight) ----------
// Multiple concurrent 401s must share ONE refresh call: the server rotates the
// token on use, so a second concurrent refresh replays an already-rotated token
// which the backend treats as theft and revokes every session. Single-flight is
// correctness, not just optimisation.
let refreshing: Promise<boolean> | null = null

async function refreshSession(): Promise<boolean> {
  refreshing ??= (async () => {
    const refreshToken = localStorage.getItem(REFRESH_KEY)
    if (!refreshToken) return false
    try {
      const res = await fetch(`${BASE}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      })
      if (!res.ok) { clearSession(); return false }
      const body = await res.json()
      setSession(body.accessToken, body.refreshToken)
      return true
    } catch {
      return false
    } finally {
      queueMicrotask(() => { refreshing = null })
    }
  })()
  return refreshing
}

export async function bootstrapSession(): Promise<boolean> {
  return hasRefreshToken() ? refreshSession() : false
}

// ---------- core request ----------
interface Options {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  auth?: boolean
  _retried?: boolean
}

export async function api<T>(path: string, opts: Options = {}): Promise<T> {
  const { method = 'GET', body, auth = true, _retried } = opts
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (auth && accessToken) headers['Authorization'] = `Bearer ${accessToken}`

  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  if (res.status === 401 && auth && !_retried) {
    if (await refreshSession()) return api<T>(path, { ...opts, _retried: true })
    clearSession()
    window.dispatchEvent(new Event('mk:logout'))
    throw await toApiError(res)
  }
  if (!res.ok) throw await toApiError(res)
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

// ---------- multipart upload ----------
// Deliberately NOT routed through api(): a JSON Content-Type header would
// break the multipart boundary the browser sets automatically from the
// FormData body. Shares the same 401→refresh→retry logic as api() instead
// of duplicating it via a second bespoke path.
export async function uploadProductImage(productId: number, file: File, _retried = false): Promise<{ imageUrl: string }> {
  const headers: Record<string, string> = {}
  if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`

  const body = new FormData()
  body.append('file', file)

  const res = await fetch(`${BASE}/api/v1/products/${productId}/image`, {
    method: 'POST',
    headers,
    body,
  })

  if (res.status === 401 && !_retried) {
    if (await refreshSession()) return uploadProductImage(productId, file, true)
    clearSession()
    window.dispatchEvent(new Event('mk:logout'))
    throw await toApiError(res)
  }
  if (!res.ok) throw await toApiError(res)
  return res.json()
}

// ---------- DTOs (mirrors backend) ----------
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface AuthResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  refreshToken: string
  refreshExpiresInSeconds: number
  userId: number
  email: string
  role: 'CUSTOMER' | 'VENDOR' | 'ADMIN'
}

// Exact match to backend ProductCategory enum names — no translation layer.
export type ProductCategoryKey = 'PRODUCE' | 'PANTRY' | 'CRAFTS' | 'HOME' | 'OTHER'

export interface ProductResponse {
  id: number
  name: string
  description?: string
  sku?: string
  price: string
  stock: number
  vendorId?: number
  vendorName?: string
  deletedAt?: string | null  // null = live, string = soft-deleted timestamp
  // Real signals from the product_popularity read model (hourly rebuild).
  // Zeros mean "no activity yet" — the truthful state, not missing data.
  avgRating: string          // BigDecimal serializes as string; 0 when unreviewed
  reviewCount: number
  soldCount: number          // kept sales only (refunds excluded)
  createdAt: string          // real recency — feeds the "New in" chip
  category: ProductCategoryKey
  imageUrl: string | null    // null until a vendor uploads one — frontend falls back to a placeholder
}

/** POST/PUT /api/v1/products body — mirrors backend ProductDtos.ProductRequest exactly. */
export interface ProductRequest {
  name: string
  description?: string
  sku: string
  price: string
  stock: number
  category: ProductCategoryKey
}

/** GET /api/v1/products/categories — live counts per category, for the sidebar. */
export interface CategoryCount {
  category: ProductCategoryKey
  count: number
}

/** Live aggregate from GET /products/{id}/reviews/summary — exact, not hourly. */
export interface ReviewSummary {
  productId: number
  averageRating: number
  reviewCount: number
}

export interface CartLine {
  productId: number
  productName: string
  unitPrice: string
  quantity: number
  lineTotal: string
  availableStock: number
}
export interface CartResponse {
  items: CartLine[]
  subtotal: string
}

export interface OrderItemResponse {
  productId?: number
  productName: string
  unitPrice: string
  quantity: number
  lineTotal: string
}

/**
 * Submitted once, at pay-time — matches ShippingDtos.ShippingAddressRequest
 * field-for-field. addressLine2 is the only optional field.
 */
export interface ShippingAddress {
  recipientName: string
  phone: string
  addressLine1: string
  addressLine2?: string | null
  city: string
  province: string
  postalCode: string
}

export interface OrderResponse {
  id: number
  status: string
  total: string
  createdAt: string
  items: OrderItemResponse[]
  // null until submitted at pay-time, and masked entirely by the backend
  // for admin viewers on orders that aren't PAID-or-later — the frontend
  // trusts that masking completely rather than re-deriving it here.
  shippingAddress?: ShippingAddress | null
}

/** Admin list projection — no items by design (paged-fetch trap); drill into detail/history. */
export interface AdminOrderSummary {
  id: number
  orderNumber: string
  customerEmail: string
  status: string
  total: string
  createdAt: string
}

export interface ReviewResponse {
  id: number
  productId: number
  reviewerId: number
  rating: number
  comment?: string
  createdAt: string
}

// ---------- typed endpoints ----------
export const auth = {
  async login(email: string, password: string) {
    const r = await api<AuthResponse>('/api/v1/auth/login', {
      method: 'POST', body: { email, password }, auth: false,
    })
    setSession(r.accessToken, r.refreshToken)
    return r
  },
  async register(input: { email: string; password: string; firstName: string; lastName: string; role: 'CUSTOMER' | 'VENDOR' }) {
    // Backend contract (AuthDtos.RegisterRequest) takes a single fullName —
    // two form fields are a UX choice, joined here at the API boundary.
    const { firstName, lastName, ...rest } = input
    const r = await api<AuthResponse>('/api/v1/auth/register', {
      method: 'POST',
      body: { ...rest, fullName: `${firstName.trim()} ${lastName.trim()}`.trim() },
      auth: false,
    })
    setSession(r.accessToken, r.refreshToken)
    return r
  },
  /** Who am I — used to rehydrate the user after a silent refresh on reload. */
  async me() {
    return api<{ userId: number; email: string; role: 'CUSTOMER' | 'VENDOR' | 'ADMIN' }>('/api/v1/auth/me')
  },
  async logout() {
    const refreshToken = localStorage.getItem(REFRESH_KEY)
    if (refreshToken) {
      await api('/api/v1/auth/logout', { method: 'POST', body: { refreshToken } }).catch(() => {})
    }
    clearSession()
  },
}

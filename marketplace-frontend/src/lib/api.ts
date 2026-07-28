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
    // Machine-readable discriminator the backend attaches to responses whose
    // status alone is ambiguous. EMAIL_NOT_VERIFIED is the first: a 403 on
    // login could be several things, and the UI has to tell that one apart
    // to offer a resend rather than a dead end.
    public code?: string,
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
      body.code,
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

  // fetch() rejects with a bare TypeError for every transport-level failure:
  // DNS, connection reset, offline, and — the one that bit us — a rejected
  // CORS preflight. Left unwrapped it escapes as a non-ApiError, so callers
  // fall through to their generic "something went wrong" branch and every
  // such failure looks identical. Wrapping it keeps ONE error type on the way
  // out and lets the UI say something true. status 0 = never reached the server.
  let res: Response
  try {
    res = await fetch(`${BASE}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
  } catch {
    throw new ApiError(0, 'Network error',
      "Couldn't reach the server. Check your connection and try again.")
  }

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

/**
 * What registration returns now that it no longer logs you in.
 *
 * emailSent=false means the account WAS created but the provider rejected
 * the message. The UI has to say so and offer a resend — telling the user
 * to check an inbox nothing was sent to strands them on an account they
 * cannot log into and cannot re-register.
 */
export interface RegisterResponse {
  email: string
  emailSent: boolean
}

// Categories are a table now (backend V14), not an enum, so there is no
// union type to mirror — the taxonomy can change without a frontend deploy,
// which was the entire point. Slugs are the identifier everywhere.

/** GET /api/v1/categories — the browse tree, two levels deep. */
export interface CategoryNode {
  id: number
  slug: string
  name: string
  icon: string | null
  /**
   * SUBTREE total. A root's count already includes everything filed under
   * its children, so do NOT sum a parent and its children to get a total —
   * that double-counts.
   */
  productCount: number
  children: CategoryNode[]
}

/** GET /api/v1/categories/options — flat list for the vendor form picker. */
export interface CategoryOption {
  id: number
  slug: string
  name: string
  parentSlug: string | null
}

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
  categorySlug: string
  categoryName: string
  parentCategorySlug: string | null  // null when filed directly on a top-level category
  handmade: boolean
  tags: string[]
  imageUrl: string | null    // null until a vendor uploads one — frontend falls back to a placeholder
}

/** POST/PUT /api/v1/products body — mirrors backend ProductDtos.ProductRequest exactly. */
export interface ProductRequest {
  name: string
  description?: string
  sku: string
  price: string
  stock: number
  categorySlug: string
  handmade: boolean
  tags: string[]
}

// The old /products/categories count endpoint is gone — counts now ride
// along on the category tree itself, so the sidebar needs one request
// instead of two and the counts cannot disagree with the tree they label.

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
export const categories = {
  /**
   * includeEmpty defaults false so shoppers are never offered a category
   * that leads to an empty page. The vendor form passes true — a brand-new
   * category has no products yet and still has to be selectable, or nothing
   * could ever become the first product in it.
   */
  tree(includeEmpty = false) {
    return api<CategoryNode[]>(
      `/api/v1/categories?includeEmpty=${includeEmpty}`, { auth: false })
  },
  options() {
    return api<CategoryOption[]>('/api/v1/categories/options', { auth: false })
  },
}

export const auth = {
  async login(email: string, password: string) {
    const r = await api<AuthResponse>('/api/v1/auth/login', {
      method: 'POST', body: { email, password }, auth: false,
    })
    setSession(r.accessToken, r.refreshToken)
    return r
  },
  /**
   * Returns a receipt, NOT a session. Login is gated on email verification,
   * so there is no token to store here and the caller must route to the
   * "check your inbox" screen rather than into the app.
   *
   * firstName/lastName go over the wire as-is. They used to be joined into
   * a fullName the server re-split on the first space, which lost mononyms
   * and mangled two-word first names.
   */
  async register(input: {
    email: string; password: string; firstName: string; lastName: string
    username: string; role: 'CUSTOMER' | 'VENDOR'
  }) {
    return api<RegisterResponse>('/api/v1/auth/register', {
      method: 'POST', body: input, auth: false,
    })
  },
  async verifyEmail(token: string) {
    return api<void>('/api/v1/auth/verify-email', {
      method: 'POST', body: { token }, auth: false,
    })
  },
  async resendVerification(email: string) {
    return api<void>('/api/v1/auth/resend-verification', {
      method: 'POST', body: { email }, auth: false,
    })
  },
  async forgotPassword(email: string) {
    return api<void>('/api/v1/auth/forgot-password', {
      method: 'POST', body: { email }, auth: false,
    })
  },
  async resetPassword(token: string, password: string) {
    return api<void>('/api/v1/auth/reset-password', {
      method: 'POST', body: { token, password }, auth: false,
    })
  },
  async usernameAvailable(username: string) {
    return api<{ username: string; available: boolean }>(
      `/api/v1/auth/username-available?username=${encodeURIComponent(username)}`,
      { auth: false },
    )
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

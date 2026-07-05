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
}

export interface CartLine {
  productId: number
  productName: string
  unitPrice: string
  quantity: number
  availableStock: number
}
export interface CartResponse {
  lines: CartLine[]
  total: string
}

export interface OrderItemResponse {
  productId?: number
  productName: string
  unitPrice: string
  quantity: number
  lineTotal: string
}
export interface OrderResponse {
  id: number
  status: string
  total: string
  createdAt: string
  items: OrderItemResponse[]
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
    const r = await api<AuthResponse>('/api/v1/auth/register', {
      method: 'POST', body: input, auth: false,
    })
    setSession(r.accessToken, r.refreshToken)
    return r
  },
  async logout() {
    const refreshToken = localStorage.getItem(REFRESH_KEY)
    if (refreshToken) {
      await api('/api/v1/auth/logout', { method: 'POST', body: { refreshToken } }).catch(() => {})
    }
    clearSession()
  },
}

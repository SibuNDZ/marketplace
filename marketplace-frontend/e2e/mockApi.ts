import { Page, Route } from '@playwright/test'

const API = /\/api\/v1\//

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

const tree = [
  {
    id: 1, slug: 'fashion', name: 'Fashion', icon: null, productCount: 1,
    children: [
      { id: 2, slug: 'jewellery', name: 'Jewellery', icon: null, productCount: 1, children: [] },
    ],
  },
]

const variantProduct = {
  id: 10,
  name: 'Cotton tee',
  price: '80.00',
  originalPrice: null,
  stock: 4,
  sku: 'TEE-1',
  vendorId: 1,
  vendorName: 'Stall',
  avgRating: 0,
  reviewCount: 0,
  soldCount: 0,
  createdAt: '2026-01-01T00:00:00Z',
  categorySlug: 'fashion',
  categoryName: 'Fashion',
  parentCategorySlug: null,
  handmade: false,
  tags: [],
  imageUrl: null,
  images: [],
  deletedAt: null,
  variants: [
    { id: 101, label: 'Small', price: '80.00', stock: 2, imageUrl: null },
    { id: 102, label: 'Large', price: '85.00', stock: 2, imageUrl: null },
  ],
}

export async function mockApi(page: Page) {
  await page.route(API, async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()

    if (path.endsWith('/auth/refresh')) return json(route, {
      accessToken: 'test-access', refreshToken: 'test-refresh',
    })
    if (path.endsWith('/auth/me')) return json(route, {
      userId: 1, email: 'shopper@example.com', role: 'CUSTOMER',
    })
    if (path.includes('/categories')) return json(route, tree)
    if (path.endsWith('/products/popular')) return json(route, [])
    if (path.endsWith('/products') && method === 'GET') {
      const category = url.searchParams.get('category')
      const content = category === 'jewellery' ? [] : [variantProduct]
      return json(route, { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 })
    }
    if (path.includes('/products/10/reviews')) return json(route, { averageRating: 0, reviewCount: 0 })
    if (path.includes('/products/10/demand')) return json(route, { recentBuyers: null })
    if (path.includes('/products/10/similar')) return json(route, [])
    if (path.endsWith('/products/10')) return json(route, variantProduct)
    if (path.endsWith('/cart') && method === 'GET') {
      return json(route, { items: [], subtotal: '0.00' })
    }
    if (path.endsWith('/cart/items') && method === 'POST') {
      const body = route.request().postDataJSON() as { variantId?: number }
      if (body.variantId == null) {
        return json(route, { title: 'Variant required', detail: 'Choose an option' }, 400)
      }
      return json(route, { ok: true })
    }
    if (path.includes('/orders') && method === 'POST' && !path.includes('/pay')) {
      return json(route, { id: 99 })
    }
    if (path.endsWith('/pay') && method === 'POST') {
      return json(route, {
        type: 'https://erestyu.com/problems/payments:provider-misconfigured',
        title: 'Payment provider unavailable',
        detail: 'Payment provider unavailable',
        status: 502,
      }, 502)
    }
    return json(route, {})
  })
}

export async function signIn(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('mk.refresh', 'test-refresh')
  })
}

import { expect, test } from '@playwright/test'
import { mockApi, signIn } from './mockApi'

test.beforeEach(async ({ page }) => {
  await signIn(page)
  await mockApi(page)
})

test('add-variant-to-cart posts the chosen variantId', async ({ page }) => {
  const posted: unknown[] = []
  await page.route(/\/api\/v1\/cart\/items$/, async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    posted.push(route.request().postDataJSON())
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/products/10')
  await page.getByRole('radio', { name: /Small/ }).click()
  await page.getByRole('button', { name: 'Add to cart' }).click()
  await expect.poll(() => posted).toEqual([
    expect.objectContaining({ productId: 10, variantId: 101, quantity: 1 }),
  ])
})

test('pay-error surface shows the RFC 7807 type', async ({ page }) => {
  await page.route(/\/api\/v1\/cart$/, async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [{
          productId: 10, productName: 'Cotton tee', variantId: 101, variantLabel: 'Small',
          unitPrice: '80.00', quantity: 1, lineTotal: '80.00', availableStock: 2, imageUrl: null,
        }],
        subtotal: '80.00',
      }),
    })
  })

  // Placing the order is a prerequisite, not the subject: the shipping form
  // only renders once /api/v1/orders has answered. This was unmocked, so the
  // POST fell through to the dev server, the mutation failed, and the test
  // sat waiting for a form that could never appear.
  await page.route(/\/api\/v1\/orders$/, async route => {
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 5001,
        status: 'PENDING',
        total: '80.00',
        createdAt: '2026-08-23T10:00:00Z',
        items: [{ productId: 10, productName: 'Cotton tee', quantity: 1, unitPrice: '80.00', lineTotal: '80.00' }],
        deliveryFees: [],
      }),
    })
  })

  // The actual subject: pay returns RFC 7807 and the surface shows the type.
  await page.route(/\/api\/v1\/orders\/\d+\/pay$/, async route => {
    await route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        type: 'payments:provider-misconfigured',
        title: 'Payment provider is not configured',
        status: 503,
        detail: 'The payment provider is missing credentials.',
      }),
    })
  })

  await page.goto('/cart')
  await page.getByRole('button', { name: /Continue to payment/ }).click()
  await page.getByLabel('Recipient name').fill('Ada')
  await page.getByLabel('Phone').fill('0820000000')
  await page.getByLabel('Address line 1').fill('1 Market St')
  await page.getByLabel('City').fill('Cape Town')
  await page.getByLabel('Province').fill('WC')
  await page.getByLabel('Postal code').fill('8001')
  await page.getByRole('button', { name: /Continue to payment/ }).click()
  await expect(page.getByText(/payments:provider-misconfigured/)).toBeVisible()
})

test('category filter requests the selected slug', async ({ page }) => {
  const seen: string[] = []
  await page.route(/\/api\/v1\/products\?/, async route => {
    seen.push(route.request().url())
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }),
    })
  })

  await page.goto('/?category=jewellery')
  await expect.poll(() => seen.some(u => u.includes('category=jewellery'))).toBe(true)
})

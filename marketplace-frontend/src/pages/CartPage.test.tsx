import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CartPage } from '../pages/CartPage'
import { renderWithApp } from '../test/render'

const api = vi.fn()

vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, api: (...args: unknown[]) => api(...args) }
})

vi.mock('../components/layout/SiteHeader', () => ({
  SiteHeader: () => <div data-testid="header" />,
}))

const small = {
  productId: 10,
  productName: 'Tee',
  variantId: 1,
  variantLabel: 'Small',
  unitPrice: '50.00',
  quantity: 1,
  lineTotal: '50.00',
  availableStock: 5,
  imageUrl: null,
}
const large = {
  ...small,
  variantId: 2,
  variantLabel: 'Large',
  quantity: 2,
  lineTotal: '100.00',
}

describe('CartPage variant lines', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockImplementation((path: string) => {
      if (path === '/api/v1/cart') {
        return Promise.resolve({ items: [small, large], subtotal: '150.00' })
      }
      return Promise.resolve({})
    })
  })

  it('keys lines by product and variant and targets that variant on qty/remove', async () => {
    const user = userEvent.setup()
    renderWithApp(<CartPage />)

    await screen.findByText('Small', { exact: false })
    expect(screen.getByText('Large', { exact: false })).toBeInTheDocument()

    const plusButtons = screen.getAllByRole('button', { name: '+' })
    await user.click(plusButtons[0])
    await waitFor(() => {
      expect(api).toHaveBeenCalledWith(
        '/api/v1/cart/items/10?variantId=1',
        expect.objectContaining({ method: 'PUT', body: { quantity: 2 } }),
      )
    })

    const removeButtons = screen.getAllByRole('button', { name: '×' })
    await user.click(removeButtons[1])
    await waitFor(() => {
      expect(api).toHaveBeenCalledWith(
        '/api/v1/cart/items/10?variantId=2',
        expect.objectContaining({ method: 'DELETE' }),
      )
    })
  })
})

import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RightCartPanel } from './RightCartPanel'
import { renderWithApp } from '../../test/render'

const api = vi.fn()

vi.mock('../../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../../lib/api')>('../../lib/api')
  return { ...actual, api: (...args: unknown[]) => api(...args) }
})

vi.mock('../../context/AuthContext', () => ({
  useAuth: () => ({
    user: { userId: 1, email: 'shopper@example.com', role: 'CUSTOMER' },
    loading: false,
    login: async () => {},
    register: async () => ({ email: '', emailSent: false }),
    logout: async () => {},
  }),
}))

vi.mock('../../hooks/useProductPool', () => ({
  usePopular: () => ({ data: [] }),
  useBargains: () => ({ data: { content: [] } }),
}))

const line = {
  productId: 7,
  productName: 'Honey',
  variantId: null,
  variantLabel: null,
  unitPrice: '40.00',
  quantity: 1,
  lineTotal: '40.00',
  availableStock: 8,
  imageUrl: null,
}

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

describe('RightCartPanel', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockImplementation((path: string) => {
      if (path === '/api/v1/cart') {
        return Promise.resolve({ items: [line], subtotal: '40.00' })
      }
      return Promise.resolve({})
    })
  })

  it('has no line tick boxes, and quantity changes the cart line', async () => {
    const user = userEvent.setup()
    renderWithApp(
      <RightCartPanel activeFilters={new Set()} onHighlight={() => {}} />,
    )

    await screen.findByLabelText('Quantity for Honey')
    // An order is placed from the whole cart, so the panel offers no
    // selection that checkout would ignore.
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Quantity for Honey'), '3')
    await waitFor(() => {
      expect(api).toHaveBeenCalledWith(
        '/api/v1/cart/items/7',
        expect.objectContaining({ method: 'PUT', body: { quantity: 3 } }),
      )
    })
  })

  it('keeps two variants of one product as separate lines', async () => {
    api.mockImplementation((path: string) => {
      if (path === '/api/v1/cart') {
        return Promise.resolve({ items: [small, large], subtotal: '150.00' })
      }
      return Promise.resolve({})
    })
    const user = userEvent.setup()
    renderWithApp(
      <RightCartPanel activeFilters={new Set()} onHighlight={() => {}} />,
    )

    await screen.findByText('Small', { exact: false })
    expect(screen.getByText('Large', { exact: false })).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Quantity for Tee · Small'), '3')
    await waitFor(() => {
      expect(api).toHaveBeenCalledWith(
        '/api/v1/cart/items/10?variantId=1',
        expect.objectContaining({ method: 'PUT', body: { quantity: 3 } }),
      )
    })
  })

  it('shows the server subtotal for the whole cart and no minimum-order rule', async () => {
    api.mockImplementation((path: string) => {
      if (path === '/api/v1/cart') {
        return Promise.resolve({ items: [small, large], subtotal: '150.00' })
      }
      return Promise.resolve({})
    })
    renderWithApp(
      <RightCartPanel activeFilters={new Set()} onHighlight={() => {}} />,
    )

    expect(await screen.findByText('R150.00')).toBeInTheDocument()
    expect(screen.getByText('Subtotal (excl. delivery)')).toBeInTheDocument()
    // Nothing enforces a minimum, so nothing may claim one.
    expect(screen.queryByText(/min\. to checkout/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })
})

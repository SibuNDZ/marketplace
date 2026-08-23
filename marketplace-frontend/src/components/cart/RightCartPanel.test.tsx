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

  it('select-all and quantity change the cart line', async () => {
    const user = userEvent.setup()
    renderWithApp(
      <RightCartPanel activeFilters={new Set()} onHighlight={() => {}} />,
    )

    const selectAll = await screen.findByRole('checkbox', { name: /select all/i })
    expect(selectAll).toBeChecked()
    await user.click(selectAll)
    expect(selectAll).not.toBeChecked()
    await user.click(selectAll)
    expect(selectAll).toBeChecked()

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

    await user.click(screen.getByLabelText('Select Tee · Small'))
    const selectAll = screen.getByRole('checkbox', { name: /select all/i })
    expect(selectAll).not.toBeChecked()
    expect(screen.getByLabelText('Select Tee · Large')).toBeChecked()

    await user.selectOptions(screen.getByLabelText('Quantity for Tee · Small'), '3')
    await waitFor(() => {
      expect(api).toHaveBeenCalledWith(
        '/api/v1/cart/items/10?variantId=1',
        expect.objectContaining({ method: 'PUT', body: { quantity: 3 } }),
      )
    })
  })
})

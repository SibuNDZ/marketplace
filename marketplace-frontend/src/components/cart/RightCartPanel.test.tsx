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
})

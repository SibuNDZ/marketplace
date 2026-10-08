import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AdminListingsPage } from './AdminListingsPage'
import { renderWithApp } from '../test/render'

const api = vi.fn()
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, api: (...args: unknown[]) => api(...args) }
})
vi.mock('../components/layout/SiteHeader', () => ({ SiteHeader: () => <div data-testid="header" /> }))

let role: 'ADMIN' | 'VENDOR' = 'ADMIN'
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: { userId: 1, email: 'admin@test.local', role } }),
}))

const pumps = {
  id: 22, name: 'Jimmy Choo Pumps', price: '30165.71', stock: 2, vendorId: 29,
  vendorName: 'Cavioure Designers', categoryName: 'Shoes', imageUrl: null,
  createdAt: '2026-08-01T10:00:00', avgRating: '0', reviewCount: 0, soldCount: 0,
}

describe('AdminListingsPage', () => {
  beforeEach(() => {
    role = 'ADMIN'
    api.mockReset()
    api.mockImplementation((path: string) => path.startsWith('/api/v1/products?')
      ? Promise.resolve({ content: [pumps], totalElements: 1, totalPages: 1, number: 0, size: 50 })
      : Promise.resolve(undefined))
  })

  it('links every listing to its product page and its store', async () => {
    renderWithApp(<AdminListingsPage />)
    const product = await screen.findByRole('link', { name: 'Jimmy Choo Pumps' })
    expect(product.getAttribute('href')).toBe('/products/22')
    expect(screen.getByRole('link', { name: 'Cavioure Designers' }).getAttribute('href')).toBe('/shop/29')
  })

  it('removes a listing only after the admin confirms', async () => {
    const user = userEvent.setup()
    const confirm = vi.spyOn(window, 'confirm')
    renderWithApp(<AdminListingsPage />)
    const button = await screen.findByRole('button', { name: 'Remove Jimmy Choo Pumps' })

    confirm.mockReturnValueOnce(false)
    await user.click(button)
    expect(api).not.toHaveBeenCalledWith('/api/v1/products/22', expect.anything())

    confirm.mockReturnValueOnce(true)
    await user.click(button)
    await waitFor(() => expect(api).toHaveBeenCalledWith('/api/v1/products/22', { method: 'DELETE' }))
    expect(confirm.mock.calls[1][0]).toContain('Cavioure Designers')
    confirm.mockRestore()
  })

  it('shows nothing removable to a non-admin', () => {
    role = 'VENDOR'
    renderWithApp(<AdminListingsPage />)
    expect(screen.getByText('This page is for eRestyu admins.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Remove/ })).toBeNull()
    expect(api).not.toHaveBeenCalled()
  })
})

import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AdminVendorsPage } from './AdminVendorsPage'
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

const FULL_ACCOUNT_NUMBER = '1122336789'

const cavioure = {
  vendorId: 29, businessName: 'Cavioure Designers', email: 'cavioure@test.local',
  submittedAt: '2026-10-18T09:00:00',
  accountHolderName: 'T Dlamini', bankCode: '198765', bankName: 'Nedbank', accountLast4: '···6789',
  accountType: 'CHEQUE', documentType: 'ID_NUMBER', documentLast4: '···4321',
  providerResult: 'NOT_RUN', verificationMessage: null,
  accountHolderMatch: null, accountOpen: null, accountOpen3m: null, acceptsCredits: null,
  listings: [
    { id: 22, name: 'Jimmy Choo Pumps', price: '30165.71', imageUrl: 'https://img.test/pumps.jpg' },
    { id: 23, name: 'Silk Scarf', price: '450.00', imageUrl: null },
  ],
  // Never sent by the server (there is no such field); present here to prove
  // the page would not render it even if it were.
  accountNumber: FULL_ACCOUNT_NUMBER,
}

const mismatched = {
  ...cavioure, vendorId: 30, businessName: 'Mismatch Store', email: 'mismatch@test.local',
  accountHolderName: 'M Mabaso', bankCode: '470010', bankName: 'Capitec', accountLast4: '···0001',
  documentType: 'PASSPORT', documentLast4: '···0002',
  providerResult: 'MISMATCH', verificationMessage: 'Account name does not match', listings: [],
  accountHolderMatch: false, accountOpen: true, accountOpen3m: null, acceptsCredits: null,
}

describe('AdminVendorsPage', () => {
  beforeEach(() => {
    role = 'ADMIN'
    api.mockReset()
    api.mockImplementation((path: string) => path === '/api/v1/admin/vendors/pending'
      ? Promise.resolve([cavioure, mismatched])
      : Promise.resolve({ outcome: 'APPROVED' }))
  })

  it('shows masked banking and identity, links the listings, and never renders a full account number', async () => {
    renderWithApp(<AdminVendorsPage />)
    expect((await screen.findByRole('link', { name: 'Cavioure Designers' })).getAttribute('href')).toBe('/shop/29')
    expect(screen.getByText('T Dlamini')).toBeTruthy()
    expect(screen.getByText(/Nedbank \(198765\) ···6789 · Cheque/)).toBeTruthy()
    expect(screen.getByText('SA ID number')).toBeTruthy()
    expect(screen.getByText('···4321')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Jimmy Choo Pumps' }).getAttribute('href')).toBe('/products/22')
    expect(screen.getByRole('link', { name: 'Silk Scarf' }).getAttribute('href')).toBe('/products/23')
    expect(screen.queryByText(new RegExp(FULL_ACCOUNT_NUMBER))).toBeNull()
    expect(document.body.textContent).not.toContain(FULL_ACCOUNT_NUMBER)
  })

  it('approves only after the admin confirms', async () => {
    const user = userEvent.setup()
    const confirm = vi.spyOn(window, 'confirm')
    renderWithApp(<AdminVendorsPage />)
    const button = await screen.findByRole('button', { name: 'Approve Cavioure Designers' })

    confirm.mockReturnValueOnce(false)
    await user.click(button)
    expect(api).not.toHaveBeenCalledWith('/api/v1/admin/vendors/29/approve', expect.anything())

    confirm.mockReturnValueOnce(true)
    await user.click(button)
    await waitFor(() => expect(api).toHaveBeenCalledWith('/api/v1/admin/vendors/29/approve', { method: 'POST', body: {} }))
    confirm.mockRestore()
  })

  it('reject opens a note field and posts the note', async () => {
    const user = userEvent.setup()
    renderWithApp(<AdminVendorsPage />)
    await user.click(await screen.findByRole('button', { name: 'Reject Cavioure Designers' }))

    const confirmReject = screen.getByRole('button', { name: 'Confirm reject' })
    expect((confirmReject as HTMLButtonElement).disabled).toBe(true) // no note yet
    await user.type(screen.getByLabelText('Rejection note'), 'Holder name does not match the store')
    await user.click(confirmReject)
    await waitFor(() => expect(api).toHaveBeenCalledWith('/api/v1/admin/vendors/29/reject',
      { method: 'POST', body: { note: 'Holder name does not match the store' } }))
  })

  it('does not offer Approve when the provider result forbids it, shows the flags, and offers a re-run', async () => {
    const user = userEvent.setup()
    renderWithApp(<AdminVendorsPage />)
    const button = await screen.findByRole('button', { name: 'Approve Mismatch Store' })
    expect((button as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText(/Bank check: name mismatch · Account name does not match/)).toBeTruthy()
    expect(screen.getByText('holder matches ✗ · open ✓')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Re-run check for Cavioure Designers' })).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Re-run check for Mismatch Store' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/api/v1/admin/vendors/30/recheck', { method: 'POST', body: {} }))
  })

  it('shows nothing actionable to a non-admin', () => {
    role = 'VENDOR'
    renderWithApp(<AdminVendorsPage />)
    expect(screen.getByText('This page is for eRestyu admins.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Approve/ })).toBeNull()
    expect(api).not.toHaveBeenCalled()
  })
})

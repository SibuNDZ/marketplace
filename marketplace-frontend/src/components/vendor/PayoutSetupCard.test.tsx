import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PayoutSetupCard } from './PayoutSetupCard'
import { renderWithApp } from '../../test/render'

const api = vi.fn()
vi.mock('../../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../../lib/api')>('../../lib/api')
  return { ...actual, api: (...args: unknown[]) => api(...args) }
})

const status = {
  termsText: 'eRestyu remits your share…', termsVersion: 3, acceptedVersion: null, acceptedAt: null,
  termsCurrent: false, banking: { bankName: null, accountNumberLast4: null, complete: false },
  gateEnabled: false, sellable: true, approved: false, verificationState: 'NONE',
}
const banks = [
  { name: 'Absa Bank', code: '632005' },
  { name: 'Capitec Bank', code: '470010' },
  { name: 'Nedbank', code: '198765' },
]

let banksFail = false
let splitEnabled = false

describe('PayoutSetupCard bank picker', () => {
  beforeEach(() => {
    banksFail = false
    splitEnabled = false
    api.mockReset()
    api.mockImplementation((path: string, opts?: { method?: string }) => {
      if (path === '/api/v1/fees') {
        return Promise.resolve({ commissionLive: true, commissionPercent: '10', payoutWindowDays: 7, sellingGateEnabled: false, splitEnabled })
      }
      if (path === '/api/v1/vendor/settings/payouts' && !opts?.method) return Promise.resolve(status)
      if (path === '/api/v1/vendor/settings/payouts/banks') {
        return banksFail ? Promise.reject(new Error('503')) : Promise.resolve(banks)
      }
      if (path === '/api/v1/vendor/settings/payouts/banking') return Promise.resolve(status)
      return Promise.reject(new Error('unexpected ' + path))
    })
  })

  it('offers the provider banks by name and sends the picked code and name', async () => {
    const user = userEvent.setup()
    renderWithApp(<PayoutSetupCard />)
    await user.click(await screen.findByRole('button', { name: 'Add details' }))

    const select = await screen.findByLabelText('Bank') as HTMLSelectElement
    await waitFor(() => expect(select.disabled).toBe(false))
    expect(screen.getByRole('option', { name: 'Nedbank' })).toBeTruthy()
    expect(screen.queryByLabelText('Bank code')).toBeNull() // no free-text code any more

    await user.type(screen.getByLabelText('Account holder name'), 'Thandi Dlamini')
    await user.selectOptions(select, '198765')
    await user.type(screen.getByLabelText('Account number'), '1122336789')
    await user.type(screen.getByLabelText('Branch code'), '198765')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api).toHaveBeenCalledWith('/api/v1/vendor/settings/payouts/banking', {
      method: 'PUT',
      body: {
        accountHolderName: 'Thandi Dlamini', bankName: 'Nedbank', accountNumber: '1122336789',
        branchCode: '198765', bankCode: '198765', accountType: 'CHEQUE',
      },
    }))
  })

  it('shows no identity inputs while the split is off', async () => {
    const user = userEvent.setup()
    renderWithApp(<PayoutSetupCard />)
    await user.click(await screen.findByRole('button', { name: 'Add details' }))
    await screen.findByLabelText('Bank')
    expect(screen.queryByLabelText('Identity document number')).toBeNull()
    expect(document.body.textContent).not.toContain('Used once to verify your bank account')
  })

  it('with the split on, offers the identity inputs with the exact sentence and sends the number once', async () => {
    splitEnabled = true
    const user = userEvent.setup()
    renderWithApp(<PayoutSetupCard />)
    await user.click(await screen.findByRole('button', { name: 'Add details' }))
    const select = await screen.findByLabelText('Bank') as HTMLSelectElement
    await waitFor(() => expect(select.disabled).toBe(false))
    expect(await screen.findByText('Used once to verify your bank account with our payment provider. We keep only the last four characters.')).toBeTruthy()

    await user.type(screen.getByLabelText('Account holder name'), 'Thandi Dlamini')
    await user.selectOptions(select, '198765')
    await user.type(screen.getByLabelText('Account number'), '1122336789')
    await user.type(screen.getByLabelText('Branch code'), '198765')
    await user.selectOptions(screen.getByLabelText('Identity document type'), 'PASSPORT')
    await user.type(screen.getByLabelText('Identity document number'), 'A1234567')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api).toHaveBeenCalledWith('/api/v1/vendor/settings/payouts/banking', {
      method: 'PUT',
      body: {
        accountHolderName: 'Thandi Dlamini', bankName: 'Nedbank', accountNumber: '1122336789',
        branchCode: '198765', bankCode: '198765', accountType: 'CHEQUE',
        identity: { type: 'PASSPORT', number: 'A1234567' },
      },
    }))
  })

  it('with the bank list unavailable, says so and does not let the form save', async () => {
    banksFail = true
    const user = userEvent.setup()
    renderWithApp(<PayoutSetupCard />)
    await user.click(await screen.findByRole('button', { name: 'Add details' }))

    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.getByText('Bank list unavailable, try again shortly.')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true)
    expect(api).not.toHaveBeenCalledWith('/api/v1/vendor/settings/payouts/banking', expect.anything())
  })
})

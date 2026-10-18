import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SellerTermsPage, HOW_YOU_ARE_PAID_SPLIT } from './SellerTermsPage'
import { renderWithApp } from '../../test/render'

const api = vi.fn()
vi.mock('../../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../../lib/api')>('../../lib/api')
  return { ...actual, api: (...args: unknown[]) => api(...args) }
})
vi.mock('../../components/layout/SiteHeader', () => ({ SiteHeader: () => <div data-testid="header" /> }))

describe('SellerTermsPage', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockImplementation((path: string) => path === '/api/v1/fees'
      ? Promise.resolve({ commissionLive: true, commissionPercent: '10', payoutWindowDays: 7 })
      : Promise.reject(new Error('unexpected ' + path)))
  })

  it('renders the commission and payout window from the fees response, and never names Paystack', async () => {
    renderWithApp(<SellerTermsPage />)
    expect(await screen.findByText('10%')).toBeTruthy()
    expect(screen.getByText(/item total minus 10% commission/)).toBeTruthy()
    expect(screen.getByText(/within 7 days of the weekly payout run following delivery confirmation/)).toBeTruthy()
    expect(document.body.textContent).not.toContain('Paystack')
    // The split-settlement wording is committed but not rendered yet.
    expect(document.body.textContent).not.toContain('settlement timing')
    expect(HOW_YOU_ARE_PAID_SPLIT).not.toContain('Paystack')
  })

  it('shows no fee figure at all when the fees request fails', async () => {
    api.mockImplementation(() => Promise.reject(new Error('down')))
    renderWithApp(<SellerTermsPage />)
    expect(await screen.findByText('The fee figures could not be loaded. Refresh to try again.')).toBeTruthy()
    expect(screen.queryByText(/%/)).toBeNull()
  })
})

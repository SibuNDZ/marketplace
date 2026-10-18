import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SellerTermsPage, HOW_YOU_ARE_PAID_SPLIT, SET_OFF_SPLIT } from './SellerTermsPage'
import { renderWithApp } from '../../test/render'

const api = vi.fn()
vi.mock('../../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../../lib/api')>('../../lib/api')
  return { ...actual, api: (...args: unknown[]) => api(...args) }
})
vi.mock('../../components/layout/SiteHeader', () => ({ SiteHeader: () => <div data-testid="header" /> }))

let gateOn = false

describe('SellerTermsPage', () => {
  beforeEach(() => {
    gateOn = false
    api.mockReset()
    api.mockImplementation((path: string) => path === '/api/v1/fees'
      ? Promise.resolve({ commissionLive: true, commissionPercent: '10', payoutWindowDays: 7, sellingGateEnabled: gateOn })
      : Promise.reject(new Error('unexpected ' + path)))
  })

  it('with the gate off, says nothing about suspended selling, re-acceptance or share reduction', async () => {
    renderWithApp(<SellerTermsPage />)
    await screen.findByText('10%')
    const text = document.body.textContent ?? ''
    expect(screen.queryByRole('heading', { name: 'Verification' })).toBeNull()
    expect(text).not.toContain('suspends selling')
    expect(text).not.toContain('selling continues only after you accept')
    expect(text).not.toContain('reducing your share')
    expect(text).toContain('withhold or recover it from amounts owed to you')
    expect(SET_OFF_SPLIT).toContain('reducing your share')
  })

  it('with the gate on, renders the verification section and the re-acceptance sentence', async () => {
    gateOn = true
    renderWithApp(<SellerTermsPage />)
    expect(await screen.findByRole('heading', { name: 'Verification' })).toBeTruthy()
    const text = document.body.textContent ?? ''
    expect(text).toContain('suspends selling until the new details are reviewed')
    expect(text).toContain('selling continues only after you accept the current version')
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

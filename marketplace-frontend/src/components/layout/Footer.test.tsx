import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { Footer } from './Footer'
import { SOCIAL_LINKS, publishableSocialLinks } from '../../data/socialLinks'

const renderFooter = (socialLinks = SOCIAL_LINKS) =>
  render(<MemoryRouter><Footer socialLinks={socialLinks} /></MemoryRouter>)

describe('Footer social links', () => {
  it('renders no Follow us column while no page is configured', () => {
    // The shipped state until the owner supplies real URLs. An empty heading
    // is a dead end, which the footer's no-dead-links rule forbids.
    renderFooter([])
    expect(screen.queryByText('Follow us')).toBeNull()
  })

  it('renders configured pages as external links that cannot reach back into ours', () => {
    renderFooter([
      { label: 'TikTok', href: 'https://www.tiktok.com/@example' },
      { label: 'Facebook', href: 'https://www.facebook.com/example' },
    ])

    expect(screen.getByText('Follow us')).toBeTruthy()
    const tiktok = screen.getByRole('link', { name: 'TikTok' })
    expect(tiktok.getAttribute('href')).toBe('https://www.tiktok.com/@example')
    expect(tiktok.getAttribute('target')).toBe('_blank')
    expect(tiktok.getAttribute('rel')).toContain('noopener')
    expect(screen.getByRole('link', { name: 'Facebook' })).toBeTruthy()
  })

  it('never shows the heading when every configured entry is rejected', () => {
    renderFooter([{ label: 'TikTok', href: 'http://www.tiktok.com/@example' }])
    expect(screen.queryByText('Follow us')).toBeNull()
  })
})

describe('publishableSocialLinks', () => {
  it('keeps https pages on the allowed platforms, including subdomains', () => {
    const ok = [
      { label: 'TikTok', href: 'https://www.tiktok.com/@erestyu' },
      { label: 'Facebook', href: 'https://m.facebook.com/erestyu' },
      { label: 'Instagram', href: 'https://instagram.com/erestyu' },
    ]
    expect(publishableSocialLinks(ok)).toEqual(ok)
  })

  it('drops anything that could send a shopper somewhere other than our own pages', () => {
    expect(publishableSocialLinks([
      { label: 'Plain http', href: 'http://www.facebook.com/erestyu' },
      { label: 'Shortener', href: 'https://bit.ly/erestyu' },
      // Suffix match must be on a dot boundary, or this lookalike passes.
      { label: 'Lookalike', href: 'https://notfacebook.com/erestyu' },
      { label: 'Host in path', href: 'https://evil.example/facebook.com' },
      { label: 'Not a URL', href: 'facebook.com/erestyu' },
      { label: '   ', href: 'https://www.tiktok.com/@erestyu' },
    ])).toEqual([])
  })
})

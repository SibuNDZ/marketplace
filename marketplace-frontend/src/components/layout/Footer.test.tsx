import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { Footer } from './Footer'
import { SOCIAL_LINKS, SocialLink, publishableSocialLinks } from '../../data/socialLinks'

const renderFooter = (socialLinks: SocialLink[] = SOCIAL_LINKS) =>
  render(<MemoryRouter><Footer socialLinks={socialLinks} /></MemoryRouter>)

describe('Footer social links', () => {
  it('renders no Follow us column when there are no entries at all', () => {
    // An empty heading is a dead end, which the footer's no-dead-links rule
    // forbids.
    renderFooter([])
    expect(screen.queryByText('Follow us')).toBeNull()
  })

  it('renders live pages as icon links named for the platform, opening safely', () => {
    renderFooter([
      { label: 'TikTok', icon: 'tiktok', href: 'https://www.tiktok.com/@example' },
      { label: 'Facebook', icon: 'facebook', href: 'https://www.facebook.com/example' },
    ])

    expect(screen.getByText('Follow us')).toBeTruthy()
    // Icon-only, so the accessible name must come from aria-label. Without
    // it a screen reader announces an unnamed link.
    const tiktok = screen.getByRole('link', { name: 'TikTok' })
    expect(tiktok.getAttribute('href')).toBe('https://www.tiktok.com/@example')
    expect(tiktok.getAttribute('target')).toBe('_blank')
    expect(tiktok.getAttribute('rel')).toContain('noopener')
    expect(tiktok.querySelector('svg')).toBeTruthy()
    expect(tiktok.textContent).toBe('')
    expect(screen.getByRole('link', { name: 'Facebook' })).toBeTruthy()
  })

  it('renders a placeholder as a labelled image, never as a link', () => {
    renderFooter([{ label: 'Instagram', icon: 'instagram' }])

    // The column shows, because a placeholder is a real entry.
    expect(screen.getByText('Follow us')).toBeTruthy()
    // But nothing in it is clickable: a link with nowhere to go is a dead end.
    expect(screen.queryByRole('link', { name: /instagram/i })).toBeNull()
    const placeholder = screen.getByRole('img', { name: 'Instagram, coming soon' })
    expect(placeholder.closest('a')).toBeNull()
  })

  it('ships Facebook and TikTok live, and Instagram and X as placeholders', () => {
    renderFooter()
    expect(screen.getByRole('link', { name: 'Facebook' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'TikTok' })).toBeTruthy()
    expect(screen.getByRole('img', { name: 'Instagram, coming soon' })).toBeTruthy()
    expect(screen.getByRole('img', { name: 'X, coming soon' })).toBeTruthy()
  })

  it('never shows the heading when every configured entry is rejected', () => {
    renderFooter([{ label: 'TikTok', icon: 'tiktok', href: 'http://www.tiktok.com/@example' }])
    expect(screen.queryByText('Follow us')).toBeNull()
  })
})

describe('publishableSocialLinks', () => {
  it('keeps https pages on the allowed platforms, including subdomains', () => {
    const ok: SocialLink[] = [
      { label: 'TikTok', icon: 'tiktok', href: 'https://www.tiktok.com/@erestyu' },
      { label: 'Facebook', icon: 'facebook', href: 'https://m.facebook.com/erestyu' },
      { label: 'Instagram', icon: 'instagram', href: 'https://instagram.com/erestyu' },
      { label: 'X', icon: 'x', href: 'https://x.com/erestyu' },
      { label: 'X (old domain)', icon: 'x', href: 'https://twitter.com/erestyu' },
    ]
    expect(publishableSocialLinks(ok)).toEqual(ok)
  })

  it('keeps placeholders, which have no href by definition', () => {
    const placeholder: SocialLink = { label: 'X', icon: 'x' }
    expect(publishableSocialLinks([placeholder])).toEqual([placeholder])
  })

  it('drops anything that could send a shopper somewhere other than our own pages', () => {
    expect(publishableSocialLinks([
      { label: 'Plain http', icon: 'facebook', href: 'http://www.facebook.com/erestyu' },
      { label: 'Shortener', icon: 'x', href: 'https://bit.ly/erestyu' },
      // Suffix match must be on a dot boundary, or this lookalike passes.
      { label: 'Lookalike', icon: 'facebook', href: 'https://notfacebook.com/erestyu' },
      { label: 'Lookalike X', icon: 'x', href: 'https://notx.com/erestyu' },
      { label: 'Host in path', icon: 'facebook', href: 'https://evil.example/facebook.com' },
      { label: 'Not a URL', icon: 'facebook', href: 'facebook.com/erestyu' },
      // Present-but-empty is a broken URL, not a placeholder: it must not be
      // quietly downgraded to "coming soon".
      { label: 'Empty href', icon: 'tiktok', href: '' },
      { label: '   ', icon: 'tiktok', href: 'https://www.tiktok.com/@erestyu' },
    ])).toEqual([])
  })
})

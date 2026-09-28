// eRestyu's official social pages, shown in the footer's "Follow us" column.
//
// Only ever add URLs supplied by the owner and copied from the page itself,
// never a guessed handle: a wrong one puts someone else's account (a
// lookalike, an abandoned page, an impostor) in the footer of the store. If
// this list is emptied, the footer column stops rendering entirely: same rule
// as the rest of the footer, no dead links.
//
// The Facebook entry is a numeric profile.php?id= URL because the page has no
// vanity username yet. If one is claimed later, the numeric URL keeps working,
// so there is no rush to change it.
export interface SocialLink {
  label: string
  href: string
}

// Supplied by the owner, 2026-09-28.
export const SOCIAL_LINKS: SocialLink[] = [
  { label: 'Facebook', href: 'https://www.facebook.com/profile.php?id=61591684209144' },
  { label: 'TikTok', href: 'https://www.tiktok.com/@erestyu' },
]

// The platforms the footer is allowed to link to. A pasted URL on any other
// host (a link shortener, a tracking redirect, a typo'd domain) is dropped
// rather than published, because this column's only job is to send people to
// our own pages on these sites.
const ALLOWED_HOSTS = ['tiktok.com', 'facebook.com', 'instagram.com']

/**
 * The entries that are safe to render: https only, on an allowed platform
 * (the bare domain or a subdomain such as www. or m.). Everything else is
 * dropped silently here and caught by the test, not shipped.
 */
export function publishableSocialLinks(links: SocialLink[]): SocialLink[] {
  return links.filter(({ label, href }) => {
    if (!label.trim()) return false
    let url: URL
    try {
      url = new URL(href)
    } catch {
      return false
    }
    if (url.protocol !== 'https:') return false
    const host = url.hostname.toLowerCase()
    return ALLOWED_HOSTS.some(h => host === h || host.endsWith(`.${h}`))
  })
}

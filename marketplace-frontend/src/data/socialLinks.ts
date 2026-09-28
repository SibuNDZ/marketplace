// eRestyu's official social pages, shown in the footer's "Follow us" column.
//
// EMPTY ON PURPOSE until the owner supplies the exact page URLs. The pages
// exist, but no URL for them is recorded anywhere, and a guessed handle risks
// the footer of the store linking to someone else's account (a lookalike, an
// abandoned page, an impostor). While this list is empty the footer column
// does not render at all: same rule as the rest of the footer, no dead links.
//
// To publish: add an entry with the full https URL copied from the page
// itself, e.g.
//   { label: 'TikTok', href: 'https://www.tiktok.com/@<handle>' },
//   { label: 'Facebook', href: 'https://www.facebook.com/<page>' },
export interface SocialLink {
  label: string
  href: string
}

export const SOCIAL_LINKS: SocialLink[] = []

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

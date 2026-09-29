// eRestyu's official social pages, shown as icons in the footer's
// "Follow us" column.
//
// Only ever add URLs supplied by the owner and copied from the page itself,
// never a guessed handle: a wrong one puts someone else's account (a
// lookalike, an abandoned page, an impostor) in the footer of the store.
//
// An entry with NO href is a placeholder: its icon shows, dimmed, with a
// "coming soon" label, but it is not a link. That is how a platform we intend
// to be on is shown without breaking the footer's no-dead-links rule, since a
// placeholder never pretends to go anywhere. Adding its href is the whole
// change needed to make it live.
//
// The Facebook entry is a numeric profile.php?id= URL because the page has no
// vanity username yet. If one is claimed later, the numeric URL keeps working,
// so there is no rush to change it.
export type SocialIconKey = 'facebook' | 'tiktok' | 'instagram' | 'x'

export interface SocialLink {
  label: string
  icon: SocialIconKey
  /** Absent for a placeholder. */
  href?: string
}

// Facebook and TikTok supplied by the owner, 2026-09-28. Instagram and X are
// placeholders at the owner's request, pending accounts.
export const SOCIAL_LINKS: SocialLink[] = [
  { label: 'Facebook', icon: 'facebook', href: 'https://www.facebook.com/profile.php?id=61591684209144' },
  { label: 'TikTok', icon: 'tiktok', href: 'https://www.tiktok.com/@erestyu' },
  { label: 'Instagram', icon: 'instagram' },
  { label: 'X', icon: 'x' },
]

// The platforms the footer is allowed to link to. A pasted URL on any other
// host (a link shortener, a tracking redirect, a typo'd domain) is dropped
// rather than published, because this column's only job is to send people to
// our own pages on these sites. twitter.com is kept alongside x.com because
// old profile links still use it and still resolve.
const ALLOWED_HOSTS = ['tiktok.com', 'facebook.com', 'instagram.com', 'x.com', 'twitter.com']

function isAllowedUrl(href: string): boolean {
  let url: URL
  try {
    url = new URL(href)
  } catch {
    return false
  }
  if (url.protocol !== 'https:') return false
  const host = url.hostname.toLowerCase()
  return ALLOWED_HOSTS.some(h => host === h || host.endsWith(`.${h}`))
}

/**
 * The entries that are safe to render, in order: placeholders (no href) and
 * live links that are https on an allowed platform (the bare domain or a
 * subdomain such as www. or m.).
 *
 * An href that is present but fails the check is dropped entirely rather
 * than shown as a placeholder. A typo in a URL should be caught by the test
 * and fixed, not quietly downgraded to "coming soon" on a page we do have.
 */
export function publishableSocialLinks(links: SocialLink[]): SocialLink[] {
  return links.filter(({ label, href }) => {
    if (!label.trim()) return false
    if (href === undefined) return true
    return isAllowedUrl(href)
  })
}

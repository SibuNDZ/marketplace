import { siFacebook, siInstagram, siTiktok, siX } from 'simple-icons'
import type { SocialIconKey } from '../../data/socialLinks'

/**
 * Brand marks for the footer's social links, from simple-icons (CC0), which
 * tracks each brand's official glyph. Imported by name so the bundle carries
 * these four paths, not the whole icon set.
 *
 * Monochrome on purpose, in currentColor: every brand's guidelines allow a
 * single-colour mark, and four full-colour logos would be the loudest thing
 * in a footer whose other links are all muted text.
 */
const PATHS: Record<SocialIconKey, string> = {
  facebook: siFacebook.path,
  tiktok: siTiktok.path,
  instagram: siInstagram.path,
  x: siX.path,
}

export function SocialIcon({ icon, size = 20 }: { icon: SocialIconKey; size?: number }) {
  // aria-hidden: the accessible name lives on the link or placeholder around
  // the icon, so screen readers hear "TikTok", not "TikTok, image".
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} fill="currentColor" aria-hidden="true" focusable="false">
      <path d={PATHS[icon]} />
    </svg>
  )
}

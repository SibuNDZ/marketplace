import React from 'react'

/**
 * A store's profile picture, or its initial when it has none. Used on the
 * spotlight, the shop page and the vendor's own profile page, so a store
 * looks the same everywhere it appears.
 *
 * Decorative (alt=""): the store's name is always printed right beside it,
 * so announcing "Karoo Honey Co" twice would only be noise to a screen reader.
 */
export function StoreAvatar({ name, url, size = 56 }: { name: string; url: string | null; size?: number }) {
  const initial = (name || '?').trim().charAt(0).toUpperCase()
  const common: React.CSSProperties = {
    width: size, height: size, borderRadius: '50%', flexShrink: 0,
    border: '2px solid var(--line)',
  }
  if (url) {
    return <img src={url} alt="" width={size} height={size}
      style={{ ...common, objectFit: 'cover', background: 'var(--card)' }} />
  }
  return (
    <span aria-hidden="true" style={{
      ...common, display: 'inline-grid', placeItems: 'center',
      background: 'var(--flame-tint)', color: 'var(--flame-deep)',
      fontFamily: 'var(--display)', fontWeight: 800, fontSize: Math.round(size * 0.42),
    }}>
      {initial}
    </span>
  )
}

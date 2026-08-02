import React, { useId } from 'react'

/**
 * The eRestyu brand mark: a bold lowercase "e" in paper, on a flame-gradient
 * rounded tile — same geometry as public/favicon.svg (hand-kept in sync).
 *
 * Flame, not aloe: the wordmark beside this mark is itself flame-gradient
 * text, and eRestyu's dominant identity color is the warm flame gradient,
 * not the aloe green (aloe is a secondary accent — category active-states,
 * success messages). A monogram in the wordmark's own gradient, of the
 * wordmark's own first letter, reads as one identity, not two.
 *
 * Reads at 24px in the header and 16px as a favicon.
 */
export function LogoMark({ size = 24 }: { size?: number }) {
  // SiteHeader renders this component up to three times at once (desktop,
  // mobile, drawer) — some just CSS-hidden, not unmounted. A hardcoded
  // gradient id would collide across instances (SVG ids are document-global,
  // not scoped to the <svg> that declares them), so the second and third
  // <rect fill="url(#...)"> would silently resolve against nothing. useId
  // gives every mounted instance its own id.
  const gradientId = `logoMarkFlame-${useId()}`
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      aria-hidden="true"
      style={{ flexShrink: 0 }}
    >
      <defs>
        <linearGradient id={gradientId} x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#FF9A3D" />
          <stop offset="60%" stopColor="#FF4626" />
          <stop offset="100%" stopColor="#E31C3D" />
        </linearGradient>
      </defs>
      <rect width="24" height="24" rx="5.28" fill={`url(#${gradientId})`} />
      <text
        x="12" y="17.3"
        textAnchor="middle"
        fontFamily="Georgia, 'Times New Roman', serif"
        fontSize="16.5"
        fontWeight="700"
        fill="var(--paper)"
      >
        e
      </text>
    </svg>
  )
}

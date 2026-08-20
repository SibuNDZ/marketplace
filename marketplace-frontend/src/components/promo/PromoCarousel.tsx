import React, { useEffect, useState } from 'react'
import { BannerConfig, HERO_BANNERS, TILE_BANNERS } from '../../data/banners'
import { useTheme } from '../../context/ThemeContext'

// Editorial banners only — no discount claims, no countdowns, no urgency.
// The old slides claimed "up to 40% off" and ticked toward a sale that
// didn't exist anywhere in the backend; these are seasonal shelf labels.
//
// One carousel, two kinds of slide: the 3 editorial heroes and the 5
// category banners, interleaved hero/category until the heroes run out
// (H1 C1 H2 C2 H3 C3 C4 C5). Category slides are live controls — the whole
// slide applies the category filter and the CTA deep-links to the mapped
// subcategory — while editorial slides stay decorative, as before.
function interleave(): BannerConfig[] {
  const out: BannerConfig[] = []
  for (let i = 0; i < Math.max(HERO_BANNERS.length, TILE_BANNERS.length); i++) {
    if (HERO_BANNERS[i]) out.push(HERO_BANNERS[i])
    if (TILE_BANNERS[i]) out.push(TILE_BANNERS[i])
  }
  return out
}
const SLIDES = interleave()

interface Props {
  /**
   * Category filter handler (same one the nav uses). Optional so the
   * carousel still renders as pure decoration anywhere it lacks a grid
   * to filter.
   */
  onSelect?: (slug: string) => void
}

export function PromoCarousel({ onSelect }: Props) {
  const [index, setIndex] = useState(0)
  const [paused, setPaused] = useState(false)

  // Rotation pauses while the pointer or keyboard focus is on the carousel:
  // slides are click targets now, and a banner that swaps out from under a
  // click sends the shopper to the wrong category.
  useEffect(() => {
    if (paused) return
    const id = setInterval(() => setIndex(i => (i + 1) % SLIDES.length), 5000)
    return () => clearInterval(id)
  }, [paused])

  const { theme } = useTheme()
  const slide = SLIDES[index]
  const clickable = !!(slide.category && onSelect)
  // Gradients are data, not CSS, so the theme fork happens here: the same
  // slide composition on vivid daylight stops or obsidian-metallic ones.
  const gradient = theme === 'dark' ? slide.gradientDark : slide.gradient

  return (
    <div
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocusCapture={() => setPaused(true)}
      onBlurCapture={() => setPaused(false)}
      style={{
        position: 'relative', borderRadius: 'var(--r)', overflow: 'hidden',
        height: 200, marginBottom: 28, boxShadow: 'var(--shadow)',
      }}>
      <div key={index} style={{
        position: 'absolute', inset: 0, background: gradient,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '0 40px', animation: 'carousel-fade 0.4s ease',
      }}>
        {/* Floating translucent geometry — pure decoration, so hidden from
            AT and click-through (.hero-shape is pointer-events: none). */}
        <span aria-hidden className="hero-shape" style={{
          width: 120, height: 120, right: 180, top: 24, borderRadius: 18,
          ['--tilt' as string]: '14deg',
        }} />
        <span aria-hidden className="hero-shape hero-shape--magenta" style={{
          width: 70, height: 70, right: 320, bottom: 18, borderRadius: '50%',
          animationDelay: '-4s',
        }} />
        <span aria-hidden className="hero-shape" style={{
          width: 44, height: 44, right: 90, bottom: 42, borderRadius: 8,
          ['--tilt' as string]: '-18deg', animationDelay: '-7s',
        }} />
        {/* Stretched-button pattern: the whole category slide is one click
            target, and the CTA is a second, independently focusable control
            layered above it (nested <button>s are invalid HTML). */}
        {clickable && (
          <button
            aria-label={`${slide.title}. ${slide.subtitle}. Browse the category`}
            onClick={() => onSelect!(slide.category!)}
            style={{ position: 'absolute', inset: 0, border: 0, background: 'transparent', cursor: 'pointer' }}
          />
        )}
        <div style={{ maxWidth: 420, position: 'relative', pointerEvents: clickable ? 'none' : undefined }}>
          <span className="hero-badge">{slide.badge}</span>
          <h2 className="chroma-text" style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 30, lineHeight: 1.15, marginBottom: 6 }}>
            {slide.title}
          </h2>
          <p style={{ color: 'rgba(255,255,255,0.82)', fontSize: 14, marginBottom: 14 }}>{slide.subtitle}</p>
          <button
            className="hero-cta"
            onClick={clickable ? () => onSelect!(slide.subcategory ?? slide.category!) : undefined}
            style={{ pointerEvents: clickable ? 'auto' : undefined }}>
            {slide.cta} →
          </button>
        </div>
        <div style={{ fontSize: 96, opacity: 0.35, lineHeight: 1, pointerEvents: 'none' }} aria-hidden>{slide.icon}</div>
      </div>

      <div style={{ position: 'absolute', bottom: 8, left: 24, display: 'flex', zIndex: 1 }}>
        {SLIDES.map((_, i) => (
          <button
            key={i}
            type="button"
            className={`carousel-dot${i === index ? ' is-active' : ''}`}
            onClick={() => setIndex(i)}
            aria-label={`Slide ${i + 1} of ${SLIDES.length}`}
            aria-current={i === index || undefined}
          />
        ))}
      </div>
    </div>
  )
}

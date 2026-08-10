import React, { useEffect, useState } from 'react'
import { BannerConfig, HERO_BANNERS, TILE_BANNERS } from '../../data/banners'

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

  const slide = SLIDES[index]
  const clickable = !!(slide.category && onSelect)

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
        position: 'absolute', inset: 0, background: slide.gradient,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '0 40px', animation: 'carousel-fade 0.4s ease',
      }}>
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
          <span style={{
            display: 'inline-block',
            background: 'rgba(255,255,255,0.22)', color: '#fff', fontSize: 11, fontWeight: 700,
            padding: '3px 10px', borderRadius: 'var(--r-pill)', textTransform: 'uppercase', letterSpacing: '0.04em',
            marginBottom: 8,
          }}>{slide.badge}</span>
          <h2 style={{ fontFamily: 'var(--display)', fontWeight: 800, fontSize: 30, color: '#fff', lineHeight: 1.15, marginBottom: 6 }}>
            {slide.title}
          </h2>
          <p style={{ color: 'rgba(255,255,255,0.9)', fontSize: 14, marginBottom: 14 }}>{slide.subtitle}</p>
          <button
            onClick={clickable ? () => onSelect!(slide.subcategory ?? slide.category!) : undefined}
            style={{
              background: '#fff', color: 'var(--ink)', border: 'none',
              padding: '10px 22px', borderRadius: 'var(--r-pill)', fontWeight: 700, fontSize: 14,
              pointerEvents: clickable ? 'auto' : undefined,
            }}>
            {slide.cta} →
          </button>
        </div>
        <div style={{ fontSize: 96, opacity: 0.35, lineHeight: 1, pointerEvents: 'none' }} aria-hidden>{slide.icon}</div>
      </div>

      {/* dots */}
      <div style={{ position: 'absolute', bottom: 14, left: 40, display: 'flex', gap: 6, zIndex: 1 }}>
        {SLIDES.map((_, i) => (
          <button key={i} onClick={() => setIndex(i)} aria-label={`Slide ${i + 1}`} style={{
            width: i === index ? 20 : 7, height: 7, borderRadius: 'var(--r-pill)',
            background: i === index ? '#fff' : 'rgba(255,255,255,0.5)', border: 'none',
            transition: 'width 0.2s',
          }} />
        ))}
      </div>
    </div>
  )
}

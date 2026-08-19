import React, { useEffect, useState } from 'react'
import { HERO_BANNERS } from '../../data/banners'

const SLIDES = HERO_BANNERS

/**
 * Editorial hero only. Category banners live in CategoryBannerRow underneath.
 * Autoplay respects prefers-reduced-motion. Hero CTAs are labels, not
 * buttons — they have nowhere to go.
 */
export function PromoCarousel() {
  const [index, setIndex] = useState(0)
  const [paused, setPaused] = useState(false)
  const reducedMotion = typeof window !== 'undefined'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches

  useEffect(() => {
    if (paused || reducedMotion || SLIDES.length <= 1) return
    const id = setInterval(() => setIndex(i => (i + 1) % SLIDES.length), 5000)
    return () => clearInterval(id)
  }, [paused, reducedMotion])

  const slide = SLIDES[index]

  return (
    <div
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocusCapture={() => setPaused(true)}
      onBlurCapture={() => setPaused(false)}
      style={{
        position: 'relative', borderRadius: 'var(--r)', overflow: 'hidden',
        height: 200, marginBottom: 16, boxShadow: 'var(--shadow)',
      }}>
      <div key={index} style={{
        position: 'absolute', inset: 0, background: slide.gradient,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '0 40px', animation: reducedMotion ? undefined : 'carousel-fade 0.4s ease',
      }}>
        <div style={{ maxWidth: 420, position: 'relative' }}>
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
          <span style={{
            display: 'inline-block',
            background: '#fff', color: 'var(--ink)',
            padding: '10px 22px', borderRadius: 'var(--r-pill)', fontWeight: 700, fontSize: 14,
          }}>
            {slide.cta}
          </span>
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

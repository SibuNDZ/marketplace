import React, { useEffect, useState } from 'react'
import { HERO_BANNERS } from '../../data/banners'
import { useTheme } from '../../context/ThemeContext'

const SLIDES = HERO_BANNERS

/**
 * Editorial hero only. Category banners live in CategoryBannerRow underneath,
 * which is where the clickable category tiles went. Autoplay respects
 * prefers-reduced-motion.
 *
 * The CTA is a label, not a button: a hero slide has nowhere to go, and a
 * button with no handler is a dead control. The slide dots below are the only
 * interactive elements here.
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

  const { theme } = useTheme()
  const slide = SLIDES[index]
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
        height: 200, marginBottom: 16, boxShadow: 'var(--shadow)',
      }}>
      <div key={index} style={{
        position: 'absolute', inset: 0, background: gradient,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '0 40px', animation: reducedMotion ? undefined : 'carousel-fade 0.4s ease',
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
        <div style={{ maxWidth: 420, position: 'relative' }}>
          <span className="hero-badge">{slide.badge}</span>
          <h2 className="chroma-text" style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 30, lineHeight: 1.15, marginBottom: 6 }}>
            {slide.title}
          </h2>
          <p style={{ color: 'rgba(255,255,255,0.82)', fontSize: 14, marginBottom: 14 }}>{slide.subtitle}</p>
          <span className="hero-cta">{slide.cta} →</span>
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

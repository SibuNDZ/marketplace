import React, { useEffect, useState } from 'react'

// Editorial banners only — no discount claims, no countdowns, no urgency.
// The old slides claimed "up to 40% off" and ticked toward a sale that
// didn't exist anywhere in the backend; these are seasonal shelf labels.
interface Slide {
  eyebrow: string
  title: string
  sub: string
  cta: string
  gradient: string
  emoji: string
}

const SLIDES: Slide[] = [
  {
    eyebrow: 'Seasonal',
    title: 'Braai Season Essentials',
    sub: 'Tongs, boerewors spirals & firelighters from local stalls',
    cta: 'Shop the braai',
    gradient: 'linear-gradient(120deg, #FF7A18 0%, #FF4626 55%, #AF2896 100%)',
    emoji: '🔥',
  },
  {
    eyebrow: 'New in',
    title: 'Winter Warmers',
    sub: 'Wool blankets, rooibos, and hand-knit beanies from local stalls',
    cta: 'Explore winter picks',
    gradient: 'linear-gradient(120deg, #2E5CA6 0%, #1E6FE0 55%, #46B4D6 100%)',
    emoji: '🧣',
  },
  {
    eyebrow: 'Local',
    title: 'Weekend Market Picks',
    sub: 'Hand-picked from Cape Town stalls, delivered to your door',
    cta: 'Browse the market',
    gradient: 'linear-gradient(120deg, #C97D00 0%, #FFB020 55%, #FFD76A 100%)',
    emoji: '🧺',
  },
]

export function PromoCarousel() {
  const [index, setIndex] = useState(0)

  useEffect(() => {
    const id = setInterval(() => setIndex(i => (i + 1) % SLIDES.length), 5000)
    return () => clearInterval(id)
  }, [])

  const slide = SLIDES[index]

  return (
    <div style={{
      position: 'relative', borderRadius: 'var(--r)', overflow: 'hidden',
      height: 200, marginBottom: 28, boxShadow: 'var(--shadow)',
    }}>
      <div key={index} style={{
        position: 'absolute', inset: 0, background: slide.gradient,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '0 40px', animation: 'carousel-fade 0.4s ease',
      }}>
        <div style={{ maxWidth: 420 }}>
          <span style={{
            display: 'inline-block',
            background: 'rgba(255,255,255,0.22)', color: '#fff', fontSize: 11, fontWeight: 700,
            padding: '3px 10px', borderRadius: 'var(--r-pill)', textTransform: 'uppercase', letterSpacing: '0.04em',
            marginBottom: 8,
          }}>{slide.eyebrow}</span>
          <h2 style={{ fontFamily: 'var(--display)', fontWeight: 800, fontSize: 30, color: '#fff', lineHeight: 1.15, marginBottom: 6 }}>
            {slide.title}
          </h2>
          <p style={{ color: 'rgba(255,255,255,0.9)', fontSize: 14, marginBottom: 14 }}>{slide.sub}</p>
          <button style={{
            background: '#fff', color: 'var(--ink)', border: 'none',
            padding: '10px 22px', borderRadius: 'var(--r-pill)', fontWeight: 700, fontSize: 14,
          }}>
            {slide.cta} →
          </button>
        </div>
        <div style={{ fontSize: 96, opacity: 0.35, lineHeight: 1 }} aria-hidden>{slide.emoji}</div>
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

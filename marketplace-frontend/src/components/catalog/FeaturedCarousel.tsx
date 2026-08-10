import React, { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { ProductCard } from '../product/ProductCard'
import { featured, useProductPool } from '../../hooks/useProductPool'

const AUTOPLAY_MS = 5000
const FEATURED_LIMIT = 8

/**
 * Horizontal product slider under the expanded categories: scroll-snap
 * based (native touch drag/swipe for free), responsive slides-per-view via
 * CSS (4 desktop / 2 tablet / 1 mobile), arrows, dots, and ~5s autoplay
 * that pauses on hover/focus and never runs under prefers-reduced-motion.
 *
 * "Featured" = most sold, then best rated, then newest — real aggregates
 * only, matching the catalogue's honest-signals rule. Renders nothing when
 * the pool is empty rather than padding the shelf with guesses.
 */
export function FeaturedCarousel() {
  const { data } = useProductPool()
  const items = featured(data?.content ?? [], FEATURED_LIMIT)

  const trackRef = useRef<HTMLDivElement>(null)
  const [pages, setPages] = useState(1)
  const [page, setPage] = useState(0)
  const [paused, setPaused] = useState(false)
  const reducedMotion = typeof window !== 'undefined'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches

  const measure = useCallback(() => {
    const el = trackRef.current
    if (!el || el.clientWidth === 0) return
    setPages(Math.max(1, Math.round(el.scrollWidth / el.clientWidth)))
    setPage(Math.min(
      Math.round(el.scrollLeft / el.clientWidth),
      Math.max(0, Math.round(el.scrollWidth / el.clientWidth) - 1),
    ))
  }, [])

  // A ResizeObserver rather than a one-shot measure: the observer fires once
  // when observation starts (after layout has settled) and again whenever the
  // track's size changes, so the page count is right on first paint without
  // racing the browser's layout pass.
  useEffect(() => {
    const el = trackRef.current
    if (!el) return
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    window.addEventListener('resize', measure)
    return () => {
      ro.disconnect()
      window.removeEventListener('resize', measure)
    }
  }, [measure, items.length])

  const goTo = useCallback((next: number) => {
    const el = trackRef.current
    if (!el) return
    const target = ((next % pages) + pages) % pages
    el.scrollTo({ left: target * el.clientWidth, behavior: reducedMotion ? 'auto' : 'smooth' })
  }, [pages, reducedMotion])

  useEffect(() => {
    if (paused || reducedMotion || pages <= 1) return
    const id = setInterval(() => goTo(page + 1), AUTOPLAY_MS)
    return () => clearInterval(id)
  }, [paused, reducedMotion, pages, page, goTo])

  if (items.length === 0) return null

  return (
    <section
      className="featured-carousel"
      aria-roledescription="carousel"
      aria-label="Featured products"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocusCapture={() => setPaused(true)}
      onBlurCapture={() => setPaused(false)}
    >
      <div className="featured-carousel__heading">
        <span aria-hidden>✨</span>
        <h2>Featured products</h2>
        <div aria-hidden />
      </div>

      <div className="featured-carousel__viewport">
        <button
          className="featured-carousel__arrow featured-carousel__arrow--prev"
          aria-label="Previous featured products"
          disabled={page === 0}
          onClick={() => goTo(page - 1)}
        >
          <ChevronLeft size={19} strokeWidth={1.75} />
        </button>

        <div ref={trackRef} className="featured-carousel__track" onScroll={measure}>
          {items.map(p => (
            <div key={p.id} className="featured-carousel__slide">
              <div style={{ position: 'relative' }}>
                <span className="featured-badge">Featured</span>
                <ProductCard product={p} />
              </div>
            </div>
          ))}
        </div>

        <button
          className="featured-carousel__arrow featured-carousel__arrow--next"
          aria-label="Next featured products"
          disabled={page >= pages - 1}
          onClick={() => goTo(page + 1)}
        >
          <ChevronRight size={19} strokeWidth={1.75} />
        </button>
      </div>

      {pages > 1 && (
        <div className="featured-carousel__dots">
          {Array.from({ length: pages }).map((_, i) => (
            <button
              key={i}
              className={i === page ? 'is-active' : ''}
              aria-label={`Go to featured page ${i + 1}`}
              aria-current={i === page || undefined}
              onClick={() => goTo(i)}
            />
          ))}
        </div>
      )}
    </section>
  )
}

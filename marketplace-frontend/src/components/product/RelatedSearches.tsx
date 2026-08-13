import React from 'react'
import { Link } from 'react-router-dom'
import { ProductResponse } from '../../lib/api'

/**
 * "Explore related searches" chips.
 *
 * Built ONLY from fields the vendor actually filled in: the product's own
 * tags, plus its category. Nothing is generated, guessed, or expanded from a
 * keyword list — a chip that leads to an empty result page is worse than no
 * chip, and invented search terms are how a young catalogue starts looking
 * like a content farm.
 *
 * Tags go to the catalogue's name search, which is what the search box
 * already drives, so a chip lands somewhere a shopper could have reached by
 * typing. Renders nothing when a product has no tags and no category.
 */
export function RelatedSearches({ product }: { product: ProductResponse }) {
  const chips: { label: string; to: string }[] = []

  if (product.categorySlug) {
    chips.push({
      label: product.categoryName ?? product.categorySlug,
      to: `/?category=${product.categorySlug}`,
    })
  }

  for (const tag of product.tags ?? []) {
    const clean = tag.trim()
    // Single characters and pure numbers are not searches anyone would run.
    if (clean.length < 2 || /^\d+$/.test(clean)) continue
    chips.push({ label: clean, to: `/?name=${encodeURIComponent(clean)}` })
    if (chips.length >= 8) break
  }

  if (chips.length === 0) return null

  return (
    <section style={{ marginTop: 32, marginBottom: 8 }} aria-label="Explore related searches">
      <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 16, marginBottom: 12 }}>
        Explore related searches
      </h2>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        {chips.map(c => (
          <Link
            key={c.to}
            to={c.to}
            style={{
              padding: '7px 14px', borderRadius: 'var(--r-pill)',
              border: '1px solid var(--line)', fontSize: 13, color: 'var(--ink)',
              background: 'var(--card)',
            }}
          >
            {c.label}
          </Link>
        ))}
      </div>
    </section>
  )
}

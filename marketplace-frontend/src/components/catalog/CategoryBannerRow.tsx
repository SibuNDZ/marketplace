import React from 'react'
import { TILE_BANNERS } from '../../data/banners'

interface Props {
  onSelect: (slug: string) => void
}

/**
 * The five department tiles as their own row under the editorial hero.
 * Click goes to the mapped subcategory when one exists (Fashion → Jewellery,
 * etc.) so the tile matches the sidebar drill-down rather than an empty
 * parent with everything filed one level down.
 */
export function CategoryBannerRow({ onSelect }: Props) {
  return (
    <div className="category-tiles" aria-label="Shop by category">
      {TILE_BANNERS.map(tile => {
        const slug = tile.subcategory ?? tile.category!
        return (
          <button
            key={tile.category}
            type="button"
            className="category-tile"
            style={{ background: tile.gradient }}
            onClick={() => onSelect(slug)}
          >
            <span className="category-tile__badge">{tile.badge}</span>
            <span className="category-tile__title">{tile.title}</span>
            <span className="category-tile__sub">{tile.subtitle}</span>
            <span className="category-tile__cta">{tile.cta} →</span>
            <span className="category-tile__icon" aria-hidden>{tile.icon}</span>
          </button>
        )
      })}
    </div>
  )
}

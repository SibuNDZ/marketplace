import React from 'react'
import { CategoryNode } from '../../lib/api'
import { ALL_SLUG, iconFor } from '../../data/categories'

interface Props {
  tree: CategoryNode[]
  active: string
  onSelect: (slug: string) => void
}

/**
 * Two-level nav. Counts come from the tree itself rather than a separate
 * endpoint, so a category and the number beside it can never disagree —
 * they arrive in the same response.
 *
 * Only the SELECTED root expands. Showing all 33 subcategories at once
 * turns a 220px column into a wall of links, and the sidebar's job is
 * orientation, not a full sitemap.
 */
export function CategorySidebar({ tree, active, onSelect }: Props) {
  const activeRoot = tree.find(
    r => r.slug === active || r.children.some(c => c.slug === active),
  )

  const rowStyle = (isActive: boolean): React.CSSProperties => ({
    width: '100%', display: 'flex', alignItems: 'center', gap: 10,
    padding: '8px 16px',
    background: isActive ? 'var(--flame-tint)' : 'transparent',
    borderLeft: isActive ? '3px solid var(--flame)' : '3px solid transparent',
    textAlign: 'left', fontSize: 13,
    fontWeight: isActive ? 700 : 500,
    color: isActive ? 'var(--flame-deep)' : 'var(--ink)',
  })

  return (
    <nav style={{
      width: 220, flexShrink: 0,
      background: 'var(--card)', borderRadius: 'var(--r)', boxShadow: 'var(--shadow)',
      padding: '10px 0', height: 'fit-content', position: 'sticky',
      top: 'calc(var(--trustbar-h) + var(--topbar-h) + var(--catrail-total) + 16px)',
    }}>
      <p style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase', color: 'var(--ink-soft)', padding: '4px 16px 8px' }}>
        Categories
      </p>

      <button onClick={() => onSelect(ALL_SLUG)} style={rowStyle(active === ALL_SLUG)}>
        <span aria-hidden style={{ fontSize: 15 }}>🛍️</span>
        <span style={{ flex: 1 }}>All</span>
      </button>

      {tree.map(root => {
        const isActive = root.slug === active
        const isExpanded = activeRoot?.slug === root.slug
        // The catalogue requests the full tree, so departments with no stock
        // still appear — visibly, but inert. Clicking into an empty category
        // is the dead end that hiding them was meant to avoid; greying them
        // keeps the breadth of the marketplace legible without it.
        const isEmpty = root.productCount === 0
        return (
          <div key={root.slug}>
            <button onClick={() => onSelect(root.slug)}
              disabled={isEmpty}
              aria-disabled={isEmpty}
              title={isEmpty ? `${root.name} — nothing listed yet` : undefined}
              style={{
                ...rowStyle(isActive),
                opacity: isEmpty ? 0.45 : 1,
                cursor: isEmpty ? 'default' : 'pointer',
              }}>
              <span aria-hidden style={{ fontSize: 15 }}>{iconFor(root)}</span>
              <span style={{ flex: 1 }}>{root.name}</span>
              {root.productCount > 0 && (
                <span className="num" style={{ fontSize: 11, color: 'var(--ink-soft)' }}>
                  {root.productCount}
                </span>
              )}
            </button>

            {isExpanded && root.children.map(child => {
              const childActive = child.slug === active
              return (
                <button key={child.slug} onClick={() => onSelect(child.slug)} style={{
                  ...rowStyle(childActive),
                  // Indented past the parent's icon column so the hierarchy
                  // reads without giving subcategories icons of their own.
                  padding: '6px 16px 6px 45px',
                  fontSize: 12.5,
                  fontWeight: childActive ? 700 : 400,
                  color: childActive ? 'var(--flame-deep)' : 'var(--ink-soft)',
                }}>
                  <span style={{ flex: 1 }}>{child.name}</span>
                  {child.productCount > 0 && (
                    <span className="num" style={{ fontSize: 11 }}>{child.productCount}</span>
                  )}
                </button>
              )
            })}
          </div>
        )
      })}
    </nav>
  )
}

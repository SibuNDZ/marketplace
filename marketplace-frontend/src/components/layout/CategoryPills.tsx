import React, { useEffect } from 'react'
import { CategoryNode } from '../../lib/api'
import { ALL_PILL, ALL_SLUG, iconFor } from '../../data/categories'

interface Props {
  tree: CategoryNode[]
  active: string
  onSelect: (slug: string) => void
}

/**
 * Top-level chips only. Eight roots plus All still fits a 375px rail with
 * horizontal scroll; putting all 33 subcategories in one row would make it
 * a scroll marathon nobody reaches the end of.
 *
 * A second row of subcategory chips appears once a root is selected, which
 * is what makes the drill-down reachable on mobile, where the sidebar is
 * not rendered at all.
 */
export function CategoryPills({ tree, active, onSelect }: Props) {
  const activeRoot = tree.find(
    r => r.slug === active || r.children.some(c => c.slug === active),
  )
  const showsSubRow = Boolean(activeRoot && activeRoot.children.length > 0)

  // This bar is position:fixed, so nothing below it knows it just got
  // taller. --catrail-total is what .page-shell and the sidebar offset
  // against; without this the subcategory row overlaps the first row of
  // product cards the moment a category is selected.
  useEffect(() => {
    const root = document.documentElement
    root.style.setProperty(
      '--catrail-total',
      showsSubRow ? 'calc(var(--catrail-h) * 2)' : 'var(--catrail-h)',
    )
    // Braces matter: removeProperty returns a string, and an arrow returning
    // one is not a valid effect destructor.
    return () => { root.style.removeProperty('--catrail-total') }
  }, [showsSubRow])

  /**
   * `empty` renders the chip visibly but inert.
   *
   * The catalogue asks for the FULL tree, so a shopper sees all eight
   * departments from the first visit rather than only the ones that happen
   * to have stock today. A marketplace showing one category reads as a shop
   * that sells one thing; showing eight with most awaiting stock reads as a
   * marketplace at day one, which is the accurate impression.
   *
   * Inert rather than hidden, and inert rather than clickable: clicking
   * through to "no products" is the dead end the whole includeEmpty rule
   * exists to prevent. disabled + aria-disabled so it is skipped by keyboard
   * and announced by screen readers, not just greyed for sighted users.
   */
  const pill = (slug: string, name: string, icon: string, isActive: boolean, empty = false) => (
    <button key={slug} onClick={() => onSelect(slug)}
      disabled={empty}
      aria-disabled={empty}
      title={empty ? `${name} — nothing listed yet` : undefined}
      style={{
        flexShrink: 0,
        display: 'flex', alignItems: 'center', gap: 6,
        padding: '7px 14px',
        borderRadius: 'var(--r-pill)',
        border: isActive ? 'none' : '1px solid var(--line)',
        background: isActive ? 'var(--flame-gradient)' : 'var(--card)',
        color: isActive ? '#fff' : 'var(--ink)',
        fontWeight: isActive ? 700 : 500,
        fontSize: 13,
        opacity: empty ? 0.4 : 1,
        cursor: empty ? 'default' : 'pointer',
      }}>
      <span aria-hidden>{icon}</span>{name}
    </button>
  )

  const railStyle: React.CSSProperties = {
    display: 'flex', alignItems: 'center', gap: 8,
    height: 'var(--catrail-h)',
    maxWidth: 'var(--content-max)', width: '100%', margin: '0 auto',
    padding: '0 var(--gutter)', whiteSpace: 'nowrap',
  }

  return (
    <div style={{
      position: 'fixed', top: 'calc(var(--trustbar-h) + var(--topbar-h))', left: 0, right: 0, zIndex: 99,
      background: 'var(--card)', borderBottom: '1px solid var(--line)',
    }}>
      <div className="scroll-rail" style={railStyle}>
        {pill(ALL_SLUG, ALL_PILL.name, ALL_PILL.icon, active === ALL_SLUG)}
        {tree.map(root =>
          pill(root.slug, root.name, iconFor(root), root.slug === active, root.productCount === 0))}
      </div>

      {activeRoot && activeRoot.children.length > 0 && (
        <div className="scroll-rail" style={{ ...railStyle, borderTop: '1px solid var(--line)' }}>
          {/* Re-selecting the root clears the subcategory without clearing
              the category — "all of Fashion", not "all products". */}
          {pill(activeRoot.slug, `All ${activeRoot.name}`,
                iconFor(activeRoot), activeRoot.slug === active)}
          {activeRoot.children.map(child =>
            pill(child.slug, child.name, iconFor(child, activeRoot), child.slug === active))}
        </div>
      )}
    </div>
  )
}

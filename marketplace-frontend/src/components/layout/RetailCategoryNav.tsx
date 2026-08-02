import React, { KeyboardEvent, useRef, useState } from 'react'
import { ChevronDown, MoreHorizontal } from 'lucide-react'
import { CategoryNode } from '../../lib/api'
import { ALL_SLUG } from '../../data/categories'

interface Props {
  tree: CategoryNode[]
  active: string
  onSelect: (slug: string) => void
}

const PRIMARY_LIMIT = 7

export function RetailCategoryNav({ tree, active, onSelect }: Props) {
  const [openSlug, setOpenSlug] = useState<string | null>(null)
  const navRef = useRef<HTMLElement>(null)
  const stocked = [...tree].filter(root => root.productCount > 0)
    .sort((a, b) => b.productCount - a.productCount || a.name.localeCompare(b.name))
  const primary = stocked.slice(0, PRIMARY_LIMIT)
  // Zero-count departments used to ride along here as disabled "Awaiting
  // listings" rows. That is still a zero-count category rendering in the
  // UI, just dressed up — the fix applies to the More menu too.
  const overflow = stocked.slice(PRIMARY_LIMIT)
  const openRoot = tree.find(root => root.slug === openSlug)

  const close = (returnFocus = false) => {
    const trigger = navRef.current?.querySelector<HTMLElement>(`[data-category="${openSlug}"]`)
    setOpenSlug(null)
    if (returnFocus) trigger?.focus()
  }

  const select = (slug: string) => {
    onSelect(slug)
    setOpenSlug(null)
  }

  const moveTriggerFocus = (event: KeyboardEvent<HTMLButtonElement>) => {
    const triggers = Array.from(navRef.current?.querySelectorAll<HTMLButtonElement>('[data-category]') ?? [])
    const index = triggers.indexOf(event.currentTarget)
    if (event.key === 'ArrowRight') {
      event.preventDefault()
      triggers[(index + 1) % triggers.length]?.focus()
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault()
      triggers[(index - 1 + triggers.length) % triggers.length]?.focus()
    } else if (event.key === 'ArrowDown' && event.currentTarget.getAttribute('aria-expanded') === 'true') {
      event.preventDefault()
      navRef.current?.querySelector<HTMLButtonElement>('.subcategory-panel button:not([disabled])')?.focus()
    } else if (event.key === 'Escape') {
      close(true)
    }
  }

  const movePanelFocus = (event: KeyboardEvent<HTMLButtonElement>) => {
    const items = Array.from(navRef.current?.querySelectorAll<HTMLButtonElement>('.subcategory-panel button:not([disabled])') ?? [])
    const index = items.indexOf(event.currentTarget)
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      items[(index + 1) % items.length]?.focus()
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      items[(index - 1 + items.length) % items.length]?.focus()
    } else if (event.key === 'Escape') {
      close(true)
    }
  }

  const trigger = (root: CategoryNode) => {
    const selected = root.slug === active || root.children.some(child => child.slug === active)
    const hasChildren = root.children.some(child => child.productCount > 0)
    return (
      <button
        key={root.slug}
        data-category={root.slug}
        className={selected ? 'is-active' : undefined}
        aria-expanded={hasChildren ? openSlug === root.slug : undefined}
        aria-controls={hasChildren ? 'subcategory-panel' : undefined}
        onMouseEnter={() => hasChildren && setOpenSlug(root.slug)}
        onFocus={() => hasChildren && setOpenSlug(root.slug)}
        onClick={() => hasChildren ? setOpenSlug(current => current === root.slug ? null : root.slug) : select(root.slug)}
        onKeyDown={moveTriggerFocus}
      >
        {root.name}{hasChildren && <ChevronDown size={13} strokeWidth={1.75} aria-hidden />}
      </button>
    )
  }

  return (
    <nav ref={navRef} className="category-bar" aria-label="Product categories" onMouseLeave={() => close()}>
      <div className="category-bar__inner">
        <button data-category={ALL_SLUG} className={`category-all-pill${active === ALL_SLUG ? ' is-active' : ''}`} onClick={() => select(ALL_SLUG)} onKeyDown={moveTriggerFocus}>All</button>
        {primary.map(trigger)}
        {overflow.length > 0 && (
          <button
            data-category="__more__"
            aria-expanded={openSlug === '__more__'}
            aria-controls="subcategory-panel"
            onClick={() => setOpenSlug(current => current === '__more__' ? null : '__more__')}
            onMouseEnter={() => setOpenSlug('__more__')}
            onFocus={() => setOpenSlug('__more__')}
            onKeyDown={moveTriggerFocus}
          ><MoreHorizontal size={17} strokeWidth={1.75} aria-hidden />More</button>
        )}
      </div>

      {openSlug && (
        <div id="subcategory-panel" className="subcategory-panel">
          <div className="subcategory-panel__inner">
            {openSlug === '__more__' ? (
              <>
                <div className="subcategory-panel__heading"><span>More departments</span></div>
                <ul className="subcategory-panel__more">
                  {overflow.map(root => (
                    <li key={root.slug}>
                      <button onClick={() => select(root.slug)} onKeyDown={movePanelFocus}>
                        <span>{root.name}</span><span className="num">{root.productCount}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </>
            ) : openRoot && (
              <>
                <div className="subcategory-panel__heading">
                  <span>{openRoot.name}</span>
                  <button onClick={() => select(openRoot.slug)} onKeyDown={movePanelFocus}>Shop all <span className="num">{openRoot.productCount}</span></button>
                </div>
                <ul>
                  {openRoot.children.filter(child => child.productCount > 0).map(child => (
                    <li key={child.slug}><button onClick={() => select(child.slug)} onKeyDown={movePanelFocus}>
                      <span>{child.name}</span><span className="num">{child.productCount}</span>
                    </button></li>
                  ))}
                </ul>
              </>
            )}
          </div>
        </div>
      )}
    </nav>
  )
}

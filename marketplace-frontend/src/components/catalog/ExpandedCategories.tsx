import React, { useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { CategoryNode } from '../../lib/api'
import { iconFor } from '../../data/categories'

interface Props {
  tree: CategoryNode[]
  active: string
  /** The catalogue's selectCategory — writes ?category=, so this block,
      the sidebar, and the header nav can never disagree. */
  onSelect: (slug: string) => void
}

/**
 * The expanded categories block: every stocked top-level category with its
 * subcategory chips surfaced inline, instead of hiding them in the sidebar.
 * Chips are visible by default on desktop; on mobile each group collapses
 * to an accordion row (chips behind a chevron) to keep the page short.
 */
export function ExpandedCategories({ tree, active, onSelect }: Props) {
  const [open, setOpen] = useState<Set<string>>(new Set())

  const toggle = (slug: string) => setOpen(prev => {
    const next = new Set(prev)
    next.has(slug) ? next.delete(slug) : next.add(slug)
    return next
  })

  const stocked = tree.filter(root => root.productCount > 0)
  if (stocked.length === 0) return null

  return (
    <section className="expanded-categories" aria-label="All categories">
      <h2>Shop by department</h2>
      <div className="expanded-categories__grid">
        {stocked.map(root => {
          const children = root.children.filter(child => child.productCount > 0)
          const isOpen = open.has(root.slug)
          return (
            <div key={root.slug} className={`expanded-categories__group${isOpen ? ' is-open' : ''}`}>
              <div className="expanded-categories__head">
                <button
                  className={`expanded-categories__root${active === root.slug ? ' is-active' : ''}`}
                  onClick={() => onSelect(root.slug)}
                >
                  <span aria-hidden>{iconFor(root)}</span>
                  <span>{root.name}</span>
                  <span className="num">{root.productCount}</span>
                </button>
                {children.length > 0 && (
                  <button
                    className="expanded-categories__toggle"
                    aria-expanded={isOpen}
                    aria-label={`Show ${root.name} subcategories`}
                    onClick={() => toggle(root.slug)}
                  >
                    <ChevronDown size={17} strokeWidth={1.75} />
                  </button>
                )}
              </div>
              {children.length > 0 && (
                <div className="expanded-categories__chips">
                  {children.map(child => (
                    <button
                      key={child.slug}
                      className={`cat-chip${active === child.slug ? ' is-active' : ''}`}
                      onClick={() => onSelect(child.slug)}
                    >
                      {child.name} <span className="num">{child.productCount}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </section>
  )
}

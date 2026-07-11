import React from 'react'
import { PILL_CATEGORIES } from '../../data/categories'

interface Props {
  active: string
  onSelect: (key: string) => void
}

export function CategoryPills({ active, onSelect }: Props) {
  return (
    <div style={{
      position: 'fixed', top: 'calc(var(--trustbar-h) + var(--topbar-h))', left: 0, right: 0, zIndex: 99,
      height: 'var(--catrail-h)', background: 'var(--card)', borderBottom: '1px solid var(--line)',
      display: 'flex', alignItems: 'center',
    }}>
      <div className="scroll-rail" style={{
        display: 'flex', alignItems: 'center', gap: 8,
        maxWidth: 'var(--content-max)', width: '100%', margin: '0 auto',
        padding: '0 var(--gutter)', whiteSpace: 'nowrap',
      }}>
        {PILL_CATEGORIES.map(c => {
          const isActive = c.key === active
          return (
            <button key={c.key} onClick={() => onSelect(c.key)} style={{
              flexShrink: 0,
              display: 'flex', alignItems: 'center', gap: 6,
              padding: '7px 14px',
              borderRadius: 'var(--r-pill)',
              border: isActive ? 'none' : '1px solid var(--line)',
              background: isActive ? 'var(--flame-gradient)' : 'var(--card)',
              color: isActive ? '#fff' : 'var(--ink)',
              fontWeight: isActive ? 700 : 500,
              fontSize: 13,
            }}>
              <span aria-hidden>{c.icon}</span>{c.label}
            </button>
          )
        })}
      </div>
    </div>
  )
}

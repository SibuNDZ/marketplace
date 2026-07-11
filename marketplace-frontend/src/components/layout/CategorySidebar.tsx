import React from 'react'
import { CATEGORIES } from '../../data/categories'

interface Props {
  active: string
  onSelect: (key: string) => void
  counts: Record<string, number>
}

export function CategorySidebar({ active, onSelect, counts }: Props) {
  return (
    <nav style={{
      width: 220, flexShrink: 0,
      background: 'var(--card)', borderRadius: 'var(--r)', boxShadow: 'var(--shadow)',
      padding: '10px 0', height: 'fit-content', position: 'sticky',
      top: 'calc(var(--trustbar-h) + var(--topbar-h) + var(--catrail-h) + 16px)',
    }}>
      <p style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase', color: 'var(--ink-soft)', padding: '4px 16px 8px' }}>
        Categories
      </p>
      {CATEGORIES.map(c => {
        const isActive = c.key === active
        const count = counts[c.key] ?? 0
        return (
          <button key={c.key} onClick={() => onSelect(c.key)} style={{
            width: '100%', display: 'flex', alignItems: 'center', gap: 10,
            padding: '8px 16px',
            background: isActive ? 'var(--flame-tint)' : 'transparent',
            borderLeft: isActive ? '3px solid var(--flame)' : '3px solid transparent',
            textAlign: 'left', fontSize: 13,
            fontWeight: isActive ? 700 : 500,
            color: isActive ? 'var(--flame-deep)' : 'var(--ink)',
          }}>
            <span aria-hidden style={{ fontSize: 15 }}>{c.icon}</span>
            <span style={{ flex: 1 }}>{c.label}</span>
            {count > 0 && (
              <span className="num" style={{ fontSize: 11, color: 'var(--ink-soft)' }}>{count}</span>
            )}
          </button>
        )
      })}
    </nav>
  )
}

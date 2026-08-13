import React from 'react'
import { VariantResponse } from '../../lib/api'

interface Props {
  variants: VariantResponse[]
  selectedId: number | null
  onSelect: (id: number) => void
}

/**
 * Option picker for a product that has variants.
 *
 * Buttons rather than a dropdown. Each option carries its own price and its
 * own stock, and a <select> can show one line of text per option without
 * revealing either — so a shopper would have to click through every option
 * to discover that the one they want is sold out or costs more.
 *
 * NOTHING is preselected. Defaulting to the first option means a shopper who
 * does not notice the picker buys whichever one happened to be created
 * first, at that option's price. Making them choose costs one click and
 * removes a whole class of "I ordered the wrong size" complaint.
 *
 * Sold-out options stay visible and disabled rather than being hidden: "we
 * do not have that one" is useful information, and a silently shorter list
 * looks like the option never existed.
 */
export function VariantSelector({ variants, selectedId, onSelect }: Props) {
  return (
    <div role="radiogroup" aria-label="Choose an option" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <span style={{ fontSize: 13, fontWeight: 600 }}>Options</span>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        {variants.map(v => {
          const soldOut = v.stock <= 0
          const selected = v.id === selectedId
          return (
            <button
              key={v.id}
              type="button"
              role="radio"
              aria-checked={selected}
              disabled={soldOut}
              onClick={() => onSelect(v.id)}
              style={{
                display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 2,
                padding: '8px 12px', borderRadius: 'var(--r-sm)', background: 'var(--card)',
                border: selected ? '2px solid var(--ink)' : '1.5px solid var(--line)',
                cursor: soldOut ? 'not-allowed' : 'pointer',
                opacity: soldOut ? 0.5 : 1,
                textAlign: 'left',
              }}
            >
              <span style={{ fontSize: 13, fontWeight: 600 }}>{v.label}</span>
              <span style={{ fontSize: 12, color: 'var(--ink-soft)' }}>
                <span style={{ fontFamily: 'var(--mono)' }}>R</span>
                <span className="num">{Number(v.price).toFixed(2)}</span>
                {soldOut && ' · sold out'}
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}

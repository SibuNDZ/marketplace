import React, { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { HelpCircle, X } from 'lucide-react'
import { FAQ_ENTRIES } from '../../data/faqContent'
import { useAuth } from '../../context/AuthContext'

/**
 * A static Q&A browser in the corner.
 *
 * It occupies the slot a real conversational assistant will eventually take,
 * and is deliberately NOT pretending to be one. There is no text input in
 * here on purpose: an input invites a real question, and a canned list has no
 * answer for most of them. A box that takes typing and returns nothing is a
 * worse experience than a box that never asked.
 *
 * For the same reason there is no fuzzy matching against the canned answers.
 * A "closest guess" against an unrelated entry is a wrong-answer machine, and
 * confidently wrong is the one failure mode this project keeps refusing. When
 * nothing fits, it says so and hands over to a human.
 *
 * Every answer comes from FAQ_ENTRIES, which the /help page also renders, so
 * the two can never disagree.
 *
 * Closed by default, always. No auto-open, no delayed pop-up: the house tone
 * is invited, not interruptive — the same instinct that removed countdown
 * timers and fabricated urgency.
 */
export function FaqWidget() {
  const { user } = useAuth()
  const [open, setOpen] = useState(false)
  // A Set, not a single index: opening one answer never closes another. A
  // one-at-a-time accordion would fight a shopper comparing two answers, and
  // collapsing something they did not touch reads as a glitch.
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)

  // Escape closes and returns focus to the trigger, so a keyboard user is not
  // dropped at the top of the document.
  useEffect(() => {
    if (!open) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { setOpen(false); triggerRef.current?.focus() }
    }
    const onPointerDown = (e: PointerEvent) => {
      const t = e.target as Node
      if (!panelRef.current?.contains(t) && !triggerRef.current?.contains(t)) setOpen(false)
    }
    document.addEventListener('keydown', onKeyDown)
    document.addEventListener('pointerdown', onPointerDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('pointerdown', onPointerDown)
    }
  }, [open])

  // Signed-out visitors cannot reach /feedback — it is RequireAuth-gated and
  // would bounce them to a login screen they did not ask for. /contact is the
  // open door, and it carries the same inbox.
  const fallbackTo = user ? '/feedback' : '/contact'

  return (
    <>
      <button
        ref={triggerRef}
        className="faq-fab"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        aria-controls="faq-panel"
        aria-label={open ? 'Close common questions' : 'Open common questions'}
      >
        {open ? <X size={22} strokeWidth={1.75} /> : <HelpCircle size={24} strokeWidth={1.75} />}
      </button>

      {open && (
        <div
          ref={panelRef}
          id="faq-panel"
          className="faq-panel"
          role="region"
          aria-label="Frequently asked questions"
        >
          <div className="faq-panel__bar">
            {/* Says what it is. NOT "Ask eRestyu anything" — nothing here
                understands a question, and the header should not imply it. */}
            <span className="faq-panel__title">Common questions</span>
            <button
              className="faq-panel__close"
              onClick={() => { setOpen(false); triggerRef.current?.focus() }}
              aria-label="Close common questions"
            >
              <X size={18} strokeWidth={1.75} />
            </button>
          </div>

          <ul className="faq-panel__list">
            {FAQ_ENTRIES.map((entry, i) => {
              const isOpen = expanded.has(i)
              return (
                <li key={entry.question}>
                  <button
                    className="faq-item__q"
                    onClick={() => setExpanded(prev => {
                      const next = new Set(prev)
                      if (next.has(i)) next.delete(i); else next.add(i)
                      return next
                    })}
                    aria-expanded={isOpen}
                  >
                    <span>{entry.question}</span>
                    <span className="faq-item__chevron" aria-hidden>{isOpen ? '−' : '+'}</span>
                  </button>
                  {isOpen && (
                    <div className="faq-item__a">
                      <p>{entry.answer}</p>
                      {entry.link && (
                        <Link to={entry.link.to} onClick={() => setOpen(false)}>
                          {entry.link.label} →
                        </Link>
                      )}
                    </div>
                  )}
                </li>
              )
            })}
          </ul>

          {/* The honest no-match path. No "typically replies within X" — no
              such guarantee exists, and the contact page says so plainly
              rather than inventing an SLA. */}
          <div className="faq-panel__fallback">
            Didn&rsquo;t find your answer?{' '}
            <Link to={fallbackTo} onClick={() => setOpen(false)}>Ask us directly →</Link>
          </div>
        </div>
      )}
    </>
  )
}

import React from 'react'
import { Moon, Sun } from 'lucide-react'
import { useTheme } from '../../context/ThemeContext'

/**
 * The light/dark switch in the header. Both icons are always mounted,
 * stacked in one 38px well; tokens.css cross-rotates them on the data-theme
 * flip (sun spins out as the moon spins in) with a springy curve. Keeping
 * both in the DOM is what makes the swap a transition rather than a remount
 * blink, and it costs two 20px SVGs.
 *
 * aria-pressed carries the state ("dark mode on/off" as a toggle button);
 * the label names the ACTION, not the state, so a screen reader hears what
 * the press will do. Focus ring comes from the global :focus-visible rule.
 */
export function ThemeToggle({ mobile = false }: { mobile?: boolean }) {
  const { theme, toggle } = useTheme()
  const dark = theme === 'dark'
  return (
    <button
      type="button"
      className={mobile ? 'mobile-icon theme-toggle' : 'theme-toggle theme-toggle--desktop'}
      onClick={toggle}
      aria-pressed={dark}
      aria-label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
      title={dark ? 'Switch to light mode' : 'Switch to dark mode'}
    >
      <Sun size={20} strokeWidth={1.75} className="theme-toggle__icon theme-toggle__sun" aria-hidden />
      <Moon size={20} strokeWidth={1.75} className="theme-toggle__icon theme-toggle__moon" aria-hidden />
    </button>
  )
}

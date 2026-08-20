import React, { createContext, useContext, useEffect, useState } from 'react'

/**
 * Light/dark theme state. The mechanism is ONE attribute — data-theme on
 * <html> — that tokens.css keys every palette override off. Three parties
 * cooperate around it:
 *
 *  - index.html runs an inline script BEFORE first paint that sets the
 *    attribute from localStorage, falling back to prefers-color-scheme.
 *    That script is the flicker guard: by the time React mounts, the right
 *    palette is already painted, so this provider only has to READ the
 *    attribute for its initial state, never to correct it.
 *  - This provider owns changes after mount: the toggle, and following the
 *    OS preference live for users who never chose manually.
 *  - localStorage ('mk.theme') records only a MANUAL choice. No entry means
 *    "follow the OS", which is why toggle() writes and the OS-listener
 *    checks for absence before acting.
 *
 * The 300ms palette cross-fade is opt-in per switch: a .theme-switching
 * class on <html> enables transitions globally (see tokens.css), then
 * leaves. Making transitions permanent instead would smear every ordinary
 * hover and focus change.
 */

type Theme = 'light' | 'dark'
const STORAGE_KEY = 'mk.theme'

const ThemeContext = createContext<{ theme: Theme; toggle: () => void }>({
  theme: 'light',
  toggle: () => {},
})

const readInitial = (): Theme =>
  document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setTheme] = useState<Theme>(readInitial)

  useEffect(() => {
    document.documentElement.dataset.theme = theme
  }, [theme])

  // Follow the OS live, but only while the user has never chosen manually.
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = (e: MediaQueryListEvent) => {
      if (localStorage.getItem(STORAGE_KEY) === null) {
        setTheme(e.matches ? 'dark' : 'light')
      }
    }
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  const toggle = () => {
    const root = document.documentElement
    root.classList.add('theme-switching')
    window.setTimeout(() => root.classList.remove('theme-switching'), 350)
    setTheme(t => {
      const next: Theme = t === 'dark' ? 'light' : 'dark'
      localStorage.setItem(STORAGE_KEY, next)
      return next
    })
  }

  return <ThemeContext.Provider value={{ theme, toggle }}>{children}</ThemeContext.Provider>
}

export function useTheme() {
  return useContext(ThemeContext)
}

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installStaleBuildReload } from './staleBuildReload'

// The listener is installed once per test file run; installing it again in
// each test would stack handlers, so it is installed once at import time here.
installStaleBuildReload()

const fire = () => {
  const event = new Event('vite:preloadError', { cancelable: true })
  window.dispatchEvent(event)
  return event
}

describe('stale build reload', () => {
  let reload: ReturnType<typeof vi.fn>

  beforeEach(() => {
    sessionStorage.clear()
    reload = vi.fn()
    // jsdom does not implement navigation; replace reload with a spy.
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...window.location, reload },
    })
  })

  afterEach(() => vi.useRealTimers())

  it('reloads when a chunk from an old build fails to load', () => {
    const event = fire()
    expect(reload).toHaveBeenCalledTimes(1)
    // Swallowed, so React does not also report it while the page reloads.
    expect(event.defaultPrevented).toBe(true)
  })

  it('does not loop when the chunk still fails right after the reload', () => {
    fire()
    // The reloaded page fails again at once: a genuinely broken build.
    const second = fire()
    expect(reload).toHaveBeenCalledTimes(1)
    // Let it through so the failure is visible rather than hidden.
    expect(second.defaultPrevented).toBe(false)
  })

  it('recovers again from a later deploy, once the window has passed', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-29T10:00:00Z'))
    fire()
    vi.setSystemTime(new Date('2026-09-29T12:00:00Z'))
    fire()
    expect(reload).toHaveBeenCalledTimes(2)
  })
})

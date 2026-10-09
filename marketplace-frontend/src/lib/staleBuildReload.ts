/**
 * Recovers a tab that is still running a previous build after a deploy.
 *
 * Every page except the home page is a lazy chunk with a hashed file name
 * (RegisterPage-CaC9Ng5S.js). A deploy replaces those files. A tab opened
 * before the deploy still holds the OLD index script, so the next time it
 * navigates it asks for an OLD chunk that no longer exists. Pages' SPA rule
 * (/* -> /index.html 200) then answers with the HTML page instead of a 404,
 * the browser refuses it as a module ("MIME type text/html"), and the route
 * renders nothing. That blanked /register for the owner on 2026-09-29, and it
 * would do the same to any shopper or seller mid-visit during a deploy.
 *
 * Vite dispatches `vite:preloadError` when a dynamic import fails. The fix is
 * the one Vite documents: reload, which fetches the current index.html and
 * with it the current chunk names. The URL is kept, so the visitor lands on
 * the page they were going to.
 *
 * Guarded to ONE reload per short window. If the chunk still fails right
 * after a reload, the build itself is broken, and reloading again would
 * just loop; the error is allowed through instead so it is visible.
 */
const KEY = 'mk.staleBuildReloadAt'
const WINDOW_MS = 10_000

export function installStaleBuildReload(): void {
  window.addEventListener('vite:preloadError', event => {
    let last = 0
    try {
      last = Number(sessionStorage.getItem(KEY)) || 0
    } catch {
      // Storage blocked (private mode, strict settings). Without a guard a
      // reload could loop, so do nothing and let the error surface.
      return
    }
    if (Date.now() - last < WINDOW_MS) return

    try {
      sessionStorage.setItem(KEY, String(Date.now()))
    } catch {
      return
    }
    // Stop the failure from also reaching React as an unhandled error while
    // the page is going away anyway.
    event.preventDefault()
    window.location.reload()
  })
}

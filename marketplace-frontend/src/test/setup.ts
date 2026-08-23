import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'

// Vitest does not put afterEach on globalThis unless globals: true, so
// Testing Library will not auto-unmount between tests in the same file.
afterEach(() => cleanup())

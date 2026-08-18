import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, RenderOptions } from '@testing-library/react'
import React, { ReactElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { RightPanelProvider } from '../context/RightPanelContext'

export function createTestClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

export function renderWithApp(
  ui: ReactElement,
  options?: { route?: string } & Omit<RenderOptions, 'wrapper'>,
) {
  const client = createTestClient()
  const route = options?.route ?? '/'
  function Wrapper({ children }: { children: React.ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[route]}>
          <RightPanelProvider>{children}</RightPanelProvider>
        </MemoryRouter>
      </QueryClientProvider>
    )
  }
  return { ...render(ui, { wrapper: Wrapper, ...options }), client }
}

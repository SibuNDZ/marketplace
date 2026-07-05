import { QueryClient } from '@tanstack/react-query'
import { ApiError } from './api'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 60_000,
      retry: (count, err) => {
        if (err instanceof ApiError && err.status < 500) return false
        return count < 2
      },
    },
  },
})

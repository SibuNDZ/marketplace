import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { favorites } from '../lib/api'
import { useAuth } from '../context/AuthContext'

/**
 * Heart-state for the whole UI: ONE request caches every favorited product
 * id as a Set, so a catalogue of N cards asks the server nothing per card.
 * Signed-out users get an empty set and no request at all.
 */
export function useFavoriteIds() {
  const { user } = useAuth()
  const { data } = useQuery({
    queryKey: ['favorites', 'ids'],
    queryFn: favorites.ids,
    enabled: !!user,
    staleTime: 60 * 1000,
    select: (ids: number[]) => new Set(ids),
  })
  return data ?? new Set<number>()
}

/**
 * Optimistic toggle: the heart fills the instant it is tapped, and rolls
 * back only if the server refuses. Safe because both server verbs are
 * idempotent - a raced double-tap cannot corrupt anything.
 */
export function useToggleFavorite() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ productId, next }: { productId: number; next: boolean }) =>
      next ? favorites.add(productId) : favorites.remove(productId),
    onMutate: async ({ productId, next }) => {
      await qc.cancelQueries({ queryKey: ['favorites', 'ids'] })
      const previous = qc.getQueryData<number[]>(['favorites', 'ids'])
      qc.setQueryData<number[]>(['favorites', 'ids'], (ids = []) =>
        next ? [...ids.filter(id => id !== productId), productId]
             : ids.filter(id => id !== productId))
      return { previous }
    },
    onError: (_e, _v, context) => {
      if (context?.previous !== undefined) {
        qc.setQueryData(['favorites', 'ids'], context.previous)
      }
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['favorites'] })
    },
  })
}

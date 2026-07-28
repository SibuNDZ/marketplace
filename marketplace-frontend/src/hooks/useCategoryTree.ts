import { useQuery } from '@tanstack/react-query'
import { CategoryNode, categories } from '../lib/api'

/**
 * The browse tree, shared by the chip row and the sidebar.
 *
 * staleTime is deliberately long: the taxonomy changes when an admin adds a
 * category, not per page view, and refetching it on every catalogue mount
 * would be a request per navigation for data that is effectively static.
 * The product COUNTS ride along on this response and are therefore equally
 * stale — acceptable, because a sidebar count being a few minutes behind is
 * invisible, whereas the extra request on every mount is not.
 */
export function useCategoryTree(includeEmpty = false) {
  return useQuery<CategoryNode[]>({
    queryKey: ['categories', includeEmpty],
    queryFn: () => categories.tree(includeEmpty),
    staleTime: 5 * 60 * 1000,
  })
}

/** Finds a node and its parent by slug, at either level. */
export function findBySlug(
  tree: CategoryNode[],
  slug: string,
): { node: CategoryNode; parent?: CategoryNode } | undefined {
  for (const root of tree) {
    if (root.slug === slug) return { node: root }
    const child = root.children.find(c => c.slug === slug)
    if (child) return { node: child, parent: root }
  }
  return undefined
}

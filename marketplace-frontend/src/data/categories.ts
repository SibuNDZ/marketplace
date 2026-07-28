// The taxonomy itself no longer lives here.
//
// It used to be a hardcoded list of five keys that had to be kept in exact
// sync with a Java enum — adding a category meant editing this file, the
// enum, and a migration, then deploying both halves together. Categories
// are a table now (backend V14), served by GET /api/v1/categories, so this
// module keeps only the things that are genuinely frontend concerns: the
// synthetic "All" pill, and the icon fallback for subcategories.
//
// Anything importing CATEGORIES or PILL_CATEGORIES from here wants
// useCategoryTree() instead.

import { CategoryNode } from '../lib/api'

/**
 * Not a real category — a UI affordance for "clear the filter". It carries
 * the ALL_SLUG sentinel rather than a real slug so the catalogue can tell
 * "no filter" from "a category that happens to be named all".
 */
export const ALL_SLUG = '__all__'

export const ALL_PILL = { slug: ALL_SLUG, name: 'All', icon: '🛍️' }

/**
 * Subcategories are seeded without icons — 33 emoji chosen badly is worse
 * than none, and the parent's icon already carries the visual weight in the
 * chip row. This falls back to the parent's so a subcategory chip is never
 * a bare label next to iconed siblings.
 */
export function iconFor(node: CategoryNode, parent?: CategoryNode): string {
  return node.icon ?? parent?.icon ?? '📦'
}

/** Flattens the tree to "Parent / Child" labels, for breadcrumbs and pickers. */
export function pathLabel(node: CategoryNode, parent?: CategoryNode): string {
  return parent ? `${parent.name} / ${node.name}` : node.name
}

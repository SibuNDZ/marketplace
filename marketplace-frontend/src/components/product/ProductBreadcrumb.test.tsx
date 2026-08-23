import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ProductBreadcrumb } from './ProductBreadcrumb'
import { ProductResponse } from '../../lib/api'

vi.mock('../../hooks/useCategoryTree', async () => {
  const { findBySlug } = await vi.importActual<typeof import('../../hooks/useCategoryTree')>(
    '../../hooks/useCategoryTree',
  )
  const tree = [
    {
      id: 1, slug: 'fashion', name: 'Fashion', icon: null, productCount: 2,
      children: [
        { id: 2, slug: 'jewellery', name: 'Jewellery', icon: null, productCount: 2, children: [] },
      ],
    },
  ]
  return {
    findBySlug,
    useCategoryTree: () => ({ data: tree }),
  }
})

const product = {
  id: 1,
  name: 'Ring',
  parentCategorySlug: 'fashion',
  categorySlug: 'jewellery',
  categoryName: 'Jewellery',
} as ProductResponse

describe('ProductBreadcrumb', () => {
  it('resolves the parent display name from the category tree, not the slug', () => {
    render(
      <MemoryRouter>
        <ProductBreadcrumb product={product} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: 'Fashion' })).toHaveAttribute('href', '/?category=fashion')
    expect(screen.queryByText('fashion')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Jewellery' })).toBeInTheDocument()
  })
})

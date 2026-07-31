import { CategoryNode } from '../../lib/api'
import { ALL_SLUG } from '../../data/categories'

interface Props {
  tree: CategoryNode[]
  active: string
  onSelect: (slug: string) => void
}

export function CategoryPane({ tree, active, onSelect }: Props) {
  return (
    <aside className="category-pane" aria-label="Browse categories">
      <h2>Categories</h2>
      <nav>
        <button
          className={active === ALL_SLUG ? 'is-active' : ''}
          onClick={() => onSelect(ALL_SLUG)}
        >
          <span>All products</span>
        </button>
        {tree.map(root => (
          <div className="category-pane__group" key={root.slug}>
            <button
              className={active === root.slug ? 'is-active' : ''}
              onClick={() => onSelect(root.slug)}
            >
              <span>{root.name}</span>
              <span className="num">{root.productCount}</span>
            </button>
            {root.children.length > 0 && (
              <ul>
                {root.children.map(child => (
                  <li key={child.slug}>
                    <button
                      className={active === child.slug ? 'is-active' : ''}
                      onClick={() => onSelect(child.slug)}
                    >
                      <span>{child.name}</span>
                      <span className="num">{child.productCount}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))}
      </nav>
    </aside>
  )
}
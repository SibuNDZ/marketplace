import React from 'react'

type Stock = { stock: number; deletedAt?: string | null }

interface Props { product: Stock; className?: string }

export function StockBadge({ product, className = '' }: Props) {
  if (product.deletedAt) {
    return <span className={`stock gone ${className}`}>No longer available</span>
  }
  if (product.stock === 0) {
    return <span className={`stock gone ${className}`}>Out of stock</span>
  }
  if (product.stock <= 5) {
    return (
      <span className={`stock low ${className}`}>
        Only <span className="num">{product.stock}</span> left
      </span>
    )
  }
  return <span className={`stock ok ${className}`}>In stock</span>
}

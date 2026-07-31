import { ProductResponse } from './api'
import { getImageSeed } from './marketplaceSignals'

const HONEY_IMAGE = 'https://images.unsplash.com/photo-1587049352846-4a222e784d38?auto=format&fit=crop'

export function productImageUrl(product: ProductResponse, width: number, height: number): string {
  if (product.imageUrl) return product.imageUrl

  if (/\bhoney\b/i.test(product.name)) {
    return `${HONEY_IMAGE}&w=${width}&h=${height}&q=85`
  }

  return `https://picsum.photos/seed/${getImageSeed(product.id)}/${width}/${height}`
}

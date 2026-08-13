import { ProductResponse } from './api'
import { getImageSeed } from './marketplaceSignals'

const HONEY_IMAGE = 'https://images.unsplash.com/photo-1587049352846-4a222e784d38?auto=format&fit=crop'

/**
 * Only OUR images can be transformed. Cloudflare image transformations are
 * enabled on the erestyu.com zone with sources restricted to that zone and
 * its subdomains, so R2 assets on images.erestyu.com qualify and nothing
 * else does.
 *
 * Matching on the host suffix rather than an exact string is what keeps the
 * placeholder services (picsum, unsplash) and a local MinIO bucket out of
 * the transform path — prefixing /cdn-cgi/image/ onto any of those produces
 * a 404, because the prefix is only meaningful to Cloudflare.
 */
function isTransformable(url: string): boolean {
  try {
    return new URL(url).hostname.endsWith('.erestyu.com')
  } catch {
    return false
  }
}

/**
 * Rewrites an R2 URL into a Cloudflare transformation URL:
 *
 *   https://images.erestyu.com/products/20/abc.jpg
 *   https://images.erestyu.com/cdn-cgi/image/width=400,.../products/20/abc.jpg
 *
 * format=auto negotiates AVIF or WebP from the Accept header, which is where
 * most of the saving comes from: the 4.1MB JPEG on the watch listing comes
 * back as 21KB of AVIF at card size.
 *
 * fit=scale-down is Cloudflare's default and is stated explicitly because
 * this pipeline DEPENDS on it. Five of the catalogue's images are smaller
 * than the boxes they render in (down to 328px), and scale-down means they
 * come back at their intrinsic size rather than stretched. Verified against
 * production: a 328px source asked for width=1280 returns 328x328.
 *
 * Keep the option string stable. Cloudflare bills per UNIQUE transformation,
 * so every distinct option string is a separate billable variant of the same
 * image, and a cache miss for every visitor.
 */
function transform(url: string, width: number): string {
  const u = new URL(url)
  return `${u.origin}/cdn-cgi/image/width=${width},quality=85,format=auto,fit=scale-down${u.pathname}`
}

/**
 * Width ladders per surface. Each entry is roughly 1x and 2x of what the
 * element actually renders at, because a 2x screen asking for 2x of a 44px
 * thumbnail should get 88px, not the 3024px original.
 *
 * These are the ONLY widths requested anywhere, which keeps the unique
 * transformation count at about (catalogue size x 9). At 11 products that is
 * ~99 against a 5,000/month free allowance.
 */
export const IMAGE_WIDTHS = {
  card: [320, 480, 640],
  hero: [640, 960, 1280, 1600],
  thumb: [88, 176],
} as const

/**
 * The `sizes` attribute tells the browser how big the image will BE before
 * layout happens, so it can pick from srcset on the first pass. Getting it
 * wrong is worse than omitting srcset entirely: too large and you ship the
 * waste you were trying to remove, too small and it looks soft.
 */
export const IMAGE_SIZES = {
  card: '(max-width: 640px) 100vw, (max-width: 1024px) 50vw, 260px',
  /**
   * Tracks .pdp-main in tokens.css exactly. Three regimes, because the page
   * now has a breakpoint and a max-width where it previously had neither:
   *
   *   <= 900px   stacked, image spans the shell: 100vw - 48 gutters
   *   <= 1328px  two columns, container is 100vw - 48, 40px gap, 55% share
   *    > 1328px  container pinned at 1280, so the column is a fixed 682px
   *
   * 1328 is 1280 + the 48px of gutters, the width at which the container
   * stops growing. Written with media queries rather than a min() inside
   * calc() so it parses everywhere: an unparseable `sizes` silently falls
   * back to 100vw, which is the over-fetch this exists to prevent.
   *
   * A browser probe already caught one wrong version of this attribute,
   * where a declared 846px against a 330px element pulled the 1600w variant.
   * If .pdp-main's proportions change, this changes with it.
   */
  hero: '(max-width: 900px) calc(100vw - 48px), (max-width: 1328px) calc((100vw - 88px) * 0.55), 682px',
  thumb: '44px',
} as const

/** The plain src. Still needed: it is what a browser without srcset support
 *  uses, and what everything falls back to for placeholder images. */
export function productImageUrl(product: ProductResponse, width: number, height: number): string {
  if (product.imageUrl) {
    return isTransformable(product.imageUrl)
      ? transform(product.imageUrl, width)
      : product.imageUrl
  }

  if (/\bhoney\b/i.test(product.name)) {
    return `${HONEY_IMAGE}&w=${width}&h=${height}&q=85`
  }

  return `https://picsum.photos/seed/${getImageSeed(product.id)}/${width}/${height}`
}

/**
 * srcset for a product image, or undefined when the source cannot be
 * transformed. Undefined rather than a single-entry srcset on purpose: React
 * omits the attribute entirely for undefined, and a srcset listing one
 * unchanging URL at four widths would lie to the browser about having
 * choices, making it download the same bytes whatever it picked.
 */
export function productImageSrcSet(
  product: ProductResponse,
  widths: readonly number[],
): string | undefined {
  if (!product.imageUrl || !isTransformable(product.imageUrl)) return undefined
  const url = product.imageUrl
  return widths.map(w => `${transform(url, w)} ${w}w`).join(', ')
}

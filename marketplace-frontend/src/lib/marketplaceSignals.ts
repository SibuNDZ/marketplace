// REMAINING FABRICATION: placeholder product photography, and ONLY for
// products a vendor hasn't uploaded a real photo for yet (V11 added
// ProductResponse.imageUrl + the upload pipeline — ProductCard and
// ProductDetailPage both try the real image first: `product.imageUrl ??
// picsum placeholder`). imageSeed stays alive as the fallback for
// pending-first-real-photo inventory, not because the pipeline is missing.
//
// Everything else this module once fabricated is gone:
//   - ratings / review counts / sold counts → real, from ProductResponse
//     (product_popularity read model) and the live /reviews/summary endpoint.
//   - countdown / flash sale / verified badge / free shipping / MOQ /
//     shipping origin → deleted outright, no replacement (see the
//     honest-signals slice commit).
//   - discount % / was-prices → deleted in that same slice, then a real
//     vendor-set model was added in V23 (products.original_price) and
//     PAUSED on 2026-08-13 before any vendor used it. The guardrail only
//     checked that the "was" price exceeded the price, which says nothing
//     about whether it was ever charged — the one trust-sensitive number
//     here that was self-reported rather than derived. Nothing renders a
//     strikethrough today; a price-history-derived version is the intended
//     replacement.
//   - category assignment → real, ProductResponse.categorySlug. Categories
//     are a table since V14, served as a two-level tree by
//     GET /api/v1/categories with subtree product counts included, so the
//     sidebar no longer needs a separate count request. Catalog filtering
//     is GET /api/v1/products?category=<slug>&handmade=<bool>.

export function getImageSeed(productId: number): string {
  return `mk-${productId}`
}

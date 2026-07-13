// REMAINING FABRICATION: placeholder product photography ONLY. Dies with
// the image-upload slice.
//
// Everything else this module once fabricated is gone:
//   - ratings / review counts / sold counts → real, from ProductResponse
//     (product_popularity read model) and the live /reviews/summary endpoint.
//   - discount % / was-prices / countdown / flash sale / verified badge /
//     free shipping / MOQ / shipping origin → deleted outright, no
//     replacement (see the honest-signals slice commit).
//   - category assignment → real, ProductResponse.category (V10). Catalog
//     filtering and the sidebar's counts both come from the backend now
//     (GET /api/v1/products?category=, GET /api/v1/products/categories).
//
// imageSeed stays because Product has no image field and there is no
// upload pipeline — picsum placeholders are presentational, not a claim
// about anything, but they are still stand-ins for inventory that has no
// real photo, which is why this file exists at all.

export function getImageSeed(productId: number): string {
  return `mk-${productId}`
}

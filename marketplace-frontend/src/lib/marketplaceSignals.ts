// Fabricated product photography is gone. Missing vendor photos render the
// branded empty well (see CartLineImage / .image-well), never picsum or
// Unsplash. This module stays as the historical note for what used to live
// here so V10 and the honest-signals commits still have a place to point.
//
// Everything else this module once fabricated is gone:
//   - ratings / review counts / sold counts → real, from ProductResponse
//     (product_popularity read model) and the live /reviews/summary endpoint.
//   - countdown / flash sale / verified badge / free shipping / MOQ /
//     shipping origin → deleted outright, no replacement (see the
//     honest-signals slice commit).
//   - discount % / was-prices → deleted in that same slice, then a real
//     vendor-set model was added in V23 (products.original_price) and
//     PAUSED on 2026-08-13 before any vendor used it.
//   - category assignment → real, ProductResponse.categorySlug.

export {}

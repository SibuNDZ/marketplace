# Product variants (colour / size)

Status: **Shipped** (V20 table + vendor CRUD, V25 variant-aware cart, PDP
selector, right panel and `/cart` line identity). Two-axis size×colour
matrices are **out of scope** — a vendor who needs both enters `"Black / XL"`
as one label. See §8 for remaining gaps.

## 1. Why this is not a normal feature slice

Variants change what "stock" means, and stock is the one invariant this
codebase guards hardest: `OrderService` holds an explicit contract that
stock never goes negative under any concurrency, enforced with pessimistic
row locks acquired in ascending id order, a forced `entityManager.refresh`
after locking (there is a long comment explaining why the refresh is not
optional), and a real-threads test in `OrderServiceConcurrencyTest`.

Every other feature shipped here has been additive at the edges. This one
edits the middle of the money path. That is the whole reason it gets a
written design first.

## 2. Data model

```sql
CREATE TABLE product_variants (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    label       VARCHAR(100) NOT NULL,   -- what the buyer picks: "Black", "XL", "2pcs black"
    sku         VARCHAR(100),            -- optional, vendor's own
    price       NUMERIC(10,2) NOT NULL,  -- ABSOLUTE, not a delta (see below)
    stock       INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),
    image_key   VARCHAR(500),            -- optional per-variant photo (R2 key)
    position    INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT product_variants_label_unique UNIQUE (product_id, label)
);
CREATE INDEX idx_product_variants_product ON product_variants (product_id);
```

**Absolute price, not a delta.** A delta looks tidy until a vendor discounts
the base product and every variant silently moves with it. The buyer sees an
absolute number, so store the absolute number.

**One axis, not two.** `label` is a single flat choice ("Black", "XL"), not
colour × size as a matrix. Two axes multiply into a combinatorial editor the
vendor has to fill in, and the market vendors this platform is being sold to
at stalls will not. If a vendor genuinely needs colour × size they enter
"Black / XL" as one label. Revisit only when a real vendor asks.

## 3. The stock rule

> A product either has NO variants, or its variants are the only place stock
> and price live.

- No variants: today's behaviour exactly. `products.stock`, `products.price`.
- Has variants: `products.stock` is **ignored**, never written, and
  `ProductResponse.stock` is computed as the SUM of variant stock at read
  time. `ProductResponse.price` becomes the MINIMUM variant price, so the
  card can honestly show "from R120".

Deliberately NOT maintaining `products.stock` as a stored sum. That is a
dual write, and dual writes drift; the sum is cheap to compute and cannot
disagree with itself.

## 4. Touch points, in dependency order

| # | Area | Change |
|---|---|---|
| 1 | Migration V20 | table above |
| 2 | `ProductVariant` entity + repository | `findByProductIdOrderByPosition`, `findAllByIdForUpdate` |
| 3 | `ProductDtos.ProductResponse` | `List<VariantResponse> variants`, computed stock/price |
| 4 | Vendor CRUD | add/edit/delete variants on own product; `@PreAuthorize` + ownership like `ProductService` already does |
| 5 | `CartItem` | nullable `variant_id` FK; unique (cart_id, product_id, variant_id) |
| 6 | `CartService` | add/update take an optional `variantId`; validate it belongs to the product |
| 7 | `OrderItem` | snapshot `variant_label_at_purchase` (like `product_name_at_purchase`), nullable `variant_id` |
| 8 | **`OrderService.placeOrder`** | the risky one — see §5 |
| 9 | `OrderService.cancelOrder` / expiry | restore stock to the VARIANT, not the product |
| 10 | Frontend | variant selector on detail page, variant editor in the vendor product form, variant label on cart/order lines |

## 5. The locking change (the part to get right)

Today: `lockAndRefresh(productIds)` → `findAllByIdForUpdate` → refresh →
check stock → decrement.

With variants the lock target differs per line. Two options:

**A. Lock variants when present, products otherwise (mixed).**
Correct but the lock set spans two tables, so the ascending-id deadlock
discipline has to hold per table, and a cart containing both kinds takes
locks in both. Workable, more moving parts.

**B. Always lock the PRODUCT row, decrement the variant. ✅**
One lock target, so the existing ascending-product-id ordering is unchanged
and the deadlock argument still holds verbatim. The product row acts as the
mutex for all of its variants. Slightly coarser (two buyers of different
colours of the same product serialise), which at this scale is free.

**Recommendation: B.** It preserves the existing, tested deadlock discipline
instead of replacing it, and the comment explaining the refresh stays true.
Variants are then loaded and mutated inside the lock the product already
provides.

Acceptance for this step is not "tests pass" but specifically:
`OrderServiceConcurrencyTest` gains a variant case — N threads buying the
same variant of the same product oversell nothing, and stock lands exactly
at zero.

## 6. Suggested sequencing

1. Migration + entity + read model + vendor CRUD. **Inert**: nothing in the
   buy path changes, no selector shown yet. Shippable on its own.
2. Cart + order + stock (§5) with the concurrency test. The money path.
3. Frontend: vendor variant editor, then the buyer selector. The selector
   ships LAST on purpose — a selector that does not actually determine what
   gets bought is a lie to the buyer, which is worse than no selector.

Do not ship step 3's buyer selector before step 2 is green.

## 7. Open questions

1. Per-variant images: the detail page currently shows one image. Worth it
   for colour, pointless for size. Ship without, add when a vendor asks?
2. Should a product be allowed to have variants added AFTER it has orders?
   (Yes, but existing order lines keep `variant_id = NULL` and their
   snapshot label stays empty — history is not rewritten.)
3. Delivery fees are per vendor, unaffected. Confirmed, no change needed.

## 8. Remaining gaps (do not rebuild the money path)

- Per-variant gallery photos (open question §7.1) — not shipped.
- Two-axis size×colour picker — rejected for stall vendors; do not add.
- `/cart` qty/remove must keep passing `?variantId=` (fixed in audit 0.2).
- Compact recommendation tiles do not need a variant picker; they link to PDP.

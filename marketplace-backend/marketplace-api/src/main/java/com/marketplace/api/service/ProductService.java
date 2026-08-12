package com.marketplace.api.service;

import com.marketplace.api.dto.ProductDtos.ProductRequest;
import com.marketplace.api.dto.ProductDtos;
import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.discovery.ProductPopularity;
import com.marketplace.api.discovery.ProductPopularityRepository;
import com.marketplace.api.discovery.ProductViewRecorder;
import com.marketplace.api.discovery.SimilarityRanker;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductVariant;
import com.marketplace.api.entity.Category;
import com.marketplace.api.entity.User;
import com.marketplace.api.exception.ProductExceptions.DuplicateSkuException;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.ProductVariantRepository;
import com.marketplace.api.repository.SearchSynonymRepository;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.storage.ObjectStorageService;
import org.springframework.lang.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Product CRUD with the marketplace's core authorization rule: vendors manage
 * ONLY their own products.
 *
 * Two-layer defense:
 *   - Controller: @PreAuthorize("hasAnyRole('VENDOR','ADMIN')") — coarse gate
 *   - Service (here): assertOwnerOrAdmin — fine ownership check
 *
 * Throwing AccessDeniedException means the GlobalExceptionHandler's 403 mapping
 * covers both @PreAuthorize failures and these checks with one handler.
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductViewRecorder viewRecorder;
    private final ProductPopularityRepository popularityRepository;
    private final ObjectStorageService storage;
    private final CategoryService categoryService;
    private final ProductVariantRepository variantRepository;
    private final SearchSynonymRepository synonymRepository;
    private final com.marketplace.api.discovery.ProductEmbeddingRepository embeddingRepository;
    private final SimilarityRanker ranker;

    /**
     * Cosine floor for a pair to count as related. See semanticCandidates.
     *
     * Now configurable rather than a compile-time constant: it was written as
     * a guess, it turned out to need checking against real scores, and a value
     * that gets tuned belongs where it can be tuned without a deploy.
     */
    private final double minSimilarity;

    public ProductService(ProductRepository productRepository,
                          UserRepository userRepository,
                          ProductViewRecorder viewRecorder,
                          ProductPopularityRepository popularityRepository,
                          ObjectStorageService storage,
                          CategoryService categoryService,
                          ProductVariantRepository variantRepository,
                          SearchSynonymRepository synonymRepository,
                          com.marketplace.api.discovery.ProductEmbeddingRepository embeddingRepository,
                          SimilarityRanker ranker,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${app.discovery.similar.min-similarity:0.55}") double minSimilarity) {
        this.ranker = ranker;
        this.minSimilarity = minSimilarity;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.viewRecorder = viewRecorder;
        this.popularityRepository = popularityRepository;
        this.storage = storage;
        this.categoryService = categoryService;
        this.variantRepository = variantRepository;
        this.synonymRepository = synonymRepository;
        this.embeddingRepository = embeddingRepository;
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(Pageable pageable) {
        return toResponses(productRepository.findAllByDeletedAtIsNull(pageable));
    }

    /**
     * The vendor dashboard's listing: ONLY the caller's products, and unlike
     * every catalog query it INCLUDES soft-deleted rows — the dashboard's
     * Archived tab is exactly those. Scoping by the token's user id is the
     * fix for the dashboard showing the whole marketplace to every vendor.
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> listMine(Long vendorId, Pageable pageable) {
        return toResponses(productRepository.findByVendorId(vendorId, pageable));
    }

    /**
     * ?category= and ?handmade= catalogue filters. Both null means "all".
     *
     * A top-level slug matches the root AND its children (CategoryService
     * .resolveToIds): browsing Fashion has to show the jewellery, not an
     * empty page because everything is filed one level down. That single
     * behaviour is why this takes a slug and expands it here rather than
     * matching one id.
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> list(@Nullable String categorySlug,
                                      @Nullable Boolean handmade,
                                      Pageable pageable) {
        return list(categorySlug, handmade, null, null, pageable);
        }

        @Transactional(readOnly = true)
        public Page<ProductResponse> list(@Nullable String categorySlug,
                          @Nullable Boolean handmade,
                          @Nullable String name,
                          Pageable pageable) {
        return list(categorySlug, handmade, name, null, pageable);
        }

    /**
     * vendorId is the public storefront filter: LIVE products only, so it
     * cannot be used to enumerate a vendor's archived listings.
     */
        @Transactional(readOnly = true)
        public Page<ProductResponse> list(@Nullable String categorySlug,
                          @Nullable Boolean handmade,
                          @Nullable String name,
                          @Nullable Long vendorId,
                          Pageable pageable) {
        List<Long> categoryIds = categorySlug == null || categorySlug.isBlank()
                ? null
                : categoryService.resolveToIds(categorySlug);
        boolean searchDisabled = name == null || name.isBlank();
        String searchText = searchDisabled
            ? ""
            : name.strip();

        if (categoryIds == null && handmade == null && searchDisabled && vendorId == null) return list(pageable);

        // A real search goes through full-text (V21); everything else stays on
        // the browse query. Splitting on searchDisabled rather than adding a
        // branch inside one query keeps category browsing — the path every
        // nav click uses — untouched by search changes.
        if (!searchDisabled) {
            String tsQuery = buildTsQuery(searchText);
            if (!tsQuery.isEmpty()) {
                // The Pageable's sort MUST be dropped. Spring Data appends a
                // native query's Sort verbatim, which would both fight the
                // ORDER BY already in the query and emit the JPA property
                // name ("createdAt") where the column name is required —
                // a 42601 syntax error, not a wrong order. Relevance is the
                // only sensible ordering for a search anyway.
                Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
                return toResponses(productRepository.searchRanked(
                        categoryIds == null,
                        categoryIds == null ? List.of(-1L) : categoryIds,
                        handmade,
                        vendorId,
                        tsQuery,
                        "%" + searchText + "%",
                        unsorted));
            }
            // Nothing survived sanitising (punctuation only, or pure stop
            // words). Fall through to the substring path rather than running
            // an empty tsquery that matches nothing.
        }

        return toResponses(
            productRepository.findFiltered(categoryIds, handmade, searchDisabled, searchText, vendorId, pageable));
    }

    /**
     * Related products for the "You might also like" shelf.
     *
     * The query text is the source product's own name plus its tags — the two
     * fields a vendor chooses deliberately. Description is left out on
     * purpose: it is prose, and feeding a paragraph in produces a tsquery of
     * mostly incidental words that matches everything weakly.
     *
     * Returns an empty list rather than filler when nothing shares a signal.
     * An empty shelf is the honest answer on a catalogue this size, and the
     * frontend renders nothing at all in that case.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> similar(Long productId, int limit) {
        Product source = productRepository.findByIdAndDeletedAtIsNull(productId).orElse(null);
        if (source == null) return List.of();

        // BOTH signals are gathered, then ranked together by SimilarityRanker.
        // Previously the semantic path SHORT-CIRCUITED this method: whenever
        // embeddings returned anything at all, the text score, the category
        // bonus, and every popularity signal PopularityJob computes hourly
        // were discarded, and cosine alone decided the whole shelf.
        //
        // Semantic still outranks lexical, because their scores are on
        // different scales and only the tier comparison between them is
        // meaningful. What changed is that lexical results now FILL a shelf
        // the embeddings left short instead of being thrown away.
        Map<Long, Double> semantic = semanticCandidates(productId);

        String text = source.getName() + " " + String.join(" ", source.getTags());
        String tsQuery = buildSimilarityQuery(text);
        // No usable lexemes (a name of pure punctuation, or only stop words):
        // a sentinel that matches nothing leaves the category bonus as the
        // only signal, rather than throwing on an empty tsquery.
        if (tsQuery.isEmpty()) tsQuery = "zzzz_no_match_zzzz";

        Long categoryId = source.getCategory() == null ? null : source.getCategory().getId();

        // Over-fetch on purpose. Ranking and the vendor cap both reorder, so a
        // SQL LIMIT of exactly `limit` would let the database pre-decide a
        // shelf that Java is about to rearrange.
        List<ProductRepository.ScoredCandidate> lexical =
                productRepository.findSimilarScored(productId, categoryId, tsQuery, limit * 4);

        if (semantic.isEmpty() && lexical.isEmpty()) return List.of();

        // The lexical gate admits same-category rows that share no words. Those
        // are a LAST RESORT, not filler: appending "some other thing from this
        // category" behind three genuine matches pads a shelf that was honest
        // before, and a padded shelf looks like a recommendation without being
        // one. So they are allowed only when there is nothing better anywhere,
        // which is exactly the old behaviour when embeddings were absent.
        boolean categoryOnlyAllowed = semantic.isEmpty();

        Map<Long, Double> lexicalScore = new java.util.LinkedHashMap<>();
        java.util.Set<Long> keywordMatched = new java.util.HashSet<>();
        for (ProductRepository.ScoredCandidate row : lexical) {
            if (!row.getKeywordMatch() && !categoryOnlyAllowed) continue;
            lexicalScore.put(row.getProductId(), row.getRelevance());
            if (row.getKeywordMatch()) keywordMatched.add(row.getProductId());
        }

        java.util.Set<Long> ids = new java.util.LinkedHashSet<>(semantic.keySet());
        ids.addAll(lexicalScore.keySet());

        Map<Long, Product> byId = productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, ProductPopularity> popularity = popularityRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ProductPopularity::getProductId, Function.identity()));

        List<SimilarityRanker.Candidate> candidates = new java.util.ArrayList<>(ids.size());
        for (Long id : ids) {
            Product candidate = byId.get(id);
            if (candidate == null) continue;   // raced with a delete between queries
            boolean isSemantic = semantic.containsKey(id);
            // Null popularity is NORMAL — a product created since the last
            // hourly rebuild has no row yet. Zeros are the truthful quality
            // for it, and it still competes on relevance.
            ProductPopularity pop = popularity.get(id);
            candidates.add(new SimilarityRanker.Candidate(
                    id,
                    candidate.getVendor() == null ? null : candidate.getVendor().getId(),
                    isSemantic ? semantic.get(id) : lexicalScore.get(id),
                    isSemantic,
                    keywordMatched.contains(id),
                    pop == null || pop.getWeightedRating() == null ? 0
                            : pop.getWeightedRating().doubleValue(),
                    pop == null ? 0L : pop.getSalesCount(),
                    pop == null ? 0L : pop.getViews30d()));
        }

        // Build the product list and the reason list in one pass so their
        // indices align by construction — toResponses preserves input order,
        // and pairing them afterwards by position would be a silent
        // mis-labelling bug the moment a lookup missed.
        List<SimilarityRanker.Ranked> ranked = ranker.rank(candidates, limit);
        List<Product> ordered = new java.util.ArrayList<>(ranked.size());
        List<String> reasons = new java.util.ArrayList<>(ranked.size());
        for (SimilarityRanker.Ranked r : ranked) {
            Product p = byId.get(r.productId());
            if (p == null) continue;
            ordered.add(p);
            reasons.add(r.reason());
        }

        List<ProductResponse> responses = toResponses(ordered);
        List<ProductResponse> labelled = new java.util.ArrayList<>(responses.size());
        for (int i = 0; i < responses.size(); i++) {
            labelled.add(responses.get(i).withSimilarityReason(reasons.get(i)));
        }
        return labelled;
    }

    /**
     * Candidate ids to cosine similarity, for everything above the floor.
     *
     * Returns raw scores rather than responses because ranking now happens in
     * SimilarityRanker, which needs the number. No limit is applied here for
     * the same reason: trimming before the blend would discard candidates the
     * ranker might promote.
     *
     * Voyage returns unit-length vectors, so cosine reduces to a dot product
     * and no normalisation is needed here. Comparing in Java rather than SQL
     * is deliberate at this catalogue size (see ProductEmbeddingRepository);
     * it is also what lets this stay independent of pgvector.
     *
     * minSimilarity is the whole relevance gate. Over a small catalogue the
     * nearest neighbour of anything is still *something*, so without a floor
     * every product would show a full shelf of its least-unrelated peers —
     * exactly the "looks like a recommendation but isn't" failure the lexical
     * version was careful to avoid. Note the ranker CANNOT rescue a product
     * rejected here, which is the intended division of labour: this decides
     * what is related, the ranker only decides what order.
     *
     * The 0.55 default was a guess when written and has since been measured
     * against the live catalogue: genuine matches ran 0.610-0.747 and the
     * first unrelated product sat at 0.507, so the floor falls inside a real
     * gap. It stays configurable because that gap will move as the catalogue
     * grows denser.
     */
    private Map<Long, Double> semanticCandidates(Long productId) {
        double[] source = embeddingRepository.embeddingOf(productId);
        if (source == null) return Map.of();

        Map<Long, double[]> candidates = embeddingRepository.liveEmbeddings();
        Map<Long, Double> scored = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, double[]> entry : candidates.entrySet()) {
            if (entry.getKey().equals(productId)) continue;
            double score = dot(source, entry.getValue());
            if (score >= minSimilarity) scored.put(entry.getKey(), score);
        }
        return scored;
    }

    /** Cosine for unit vectors. Length mismatch means a model changed
     *  underneath us, which must not silently score as "unrelated". */
    private static double dot(double[] a, double[] b) {
        if (a.length != b.length) return -1;
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    /** Terms are lexemes for to_tsquery, so anything that is not a letter or
     *  digit has to go: &, |, !, ':' and parentheses are tsquery OPERATORS,
     *  and a stray one turns a search into a syntax error rather than a
     *  no-match. Splitting on non-alphanumerics does the sanitising and the
     *  tokenising in one pass, so there is no path where a raw fragment of
     *  user input reaches the query string. */
    private static final java.util.regex.Pattern TERM_SPLIT =
            java.util.regex.Pattern.compile("[^\\p{Alnum}]+");

    /** Hard ceiling on terms. Without it a pasted paragraph becomes a
     *  hundred-clause tsquery, and each term is another synonym expansion. */
    private static final int MAX_TERMS = 8;

    /**
     * Turns "rose gold watches" into a tsquery, widening each term with its
     * curated synonyms:
     *
     *   (rose:*) & (gold:*) & (watch:* | timepiece:* | wristwatch:*)
     *
     * AND between the shopper's own words, OR within a word's synonym group.
     * That is the combination that behaves the way people expect: adding a
     * word narrows the result, and a synonym never narrows anything.
     *
     * The :* prefix match is deliberate on a catalogue this small. Stemming
     * already handles watch/watches; prefixes additionally catch the partial
     * word ("neckl") and compound-ish typing, and over-matching across 12
     * products is a far better failure than an empty page.
     *
     * Returns "" when nothing usable survives, which the caller treats as
     * "do not run a full-text search".
     */
    /**
     * Words that describe packaging or quantity rather than the thing itself.
     * They are common across unrelated listings, so on the SIMILARITY path
     * they manufacture matches: "Villa fragrance and body care gift set" and
     * "Rose Gold Watch set" were being called related purely because both end
     * in "set". That is the characteristic failure of text similarity, and it
     * is what a shopper notices first.
     *
     * Dropped ONLY for similarity, never for search. A shopper who types
     * "gift set" means it and must still find one; a product that merely
     * happens to be sold as a set is not thereby related to every other set.
     *
     * Kept in code rather than beside the synonyms in the database because
     * this is a ranking parameter, not editorial vocabulary: changing it
     * changes what counts as related, which wants re-testing rather than a
     * live edit. Move it to a table if it ever grows past a screenful.
     */
    private static final java.util.Set<String> COMMODITY_TERMS = java.util.Set.of(
            "set", "sets", "pack", "packs", "packet", "pcs", "pc", "piece", "pieces",
            "bundle", "combo", "kit", "box", "bag", "size", "item", "items", "product",
            "ml", "kg", "cm", "mm", "g", "l");

    /**
     * A bare quantity, with or without a unit: 350g, 500ml, 250, 67. Two
     * products sharing a pack size have nothing in common, and on this
     * catalogue "350g" alone would have tied two unrelated pantry items.
     */
    private static final java.util.regex.Pattern MEASUREMENT =
            java.util.regex.Pattern.compile("^\\d+[a-z]{0,2}$");

    private static boolean isCommodityTerm(String term) {
        return COMMODITY_TERMS.contains(term) || MEASUREMENT.matcher(term).matches();
    }

    String buildTsQuery(String rawSearchText) {
        return buildTsQuery(rawSearchText, " & ", false);
    }

    /** Query built from a product's own words, to find products like it. */
    String buildSimilarityQuery(String rawText) {
        return buildTsQuery(rawText, " | ", true);
    }

    /**
     * joiner picks the semantics between the shopper's own words:
     *   " & " for SEARCH — every word must appear, so adding a word narrows.
     *   " | " for SIMILARITY — any overlap counts, because two products are
     *         related when they share some words, never all of them. An AND
     *         here would return nothing for almost every pair.
     * Synonym expansion inside each group is identical either way.
     */
    /**
     * similarityMode switches three things together, because they are one
     * decision ("find things LIKE this" vs "find what the shopper typed"):
     * terms are OR-joined, commodity words are dropped, and prefix matching
     * is off.
     *
     * Prefix matching earns its place in SEARCH — a shopper types a fragment
     * ("neckl") and a zero-result page is the worst outcome on a small
     * catalogue. In similarity the source terms are already complete words
     * taken from a product's own name, so a prefix only reaches words that
     * merely start the same: measured here, "body care" matched "bodice"
     * because both stem to a common prefix. That is noise, not a relation.
     */
    String buildTsQuery(String rawSearchText, String joiner, boolean similarityMode) {
        String[] rawTerms = TERM_SPLIT.split(rawSearchText.toLowerCase().strip());

        List<String> terms = new java.util.ArrayList<>();
        for (String term : rawTerms) {
            if (term.isBlank() || terms.contains(term)) continue;
            if (similarityMode && isCommodityTerm(term)) continue;
            terms.add(term);
            if (terms.size() == MAX_TERMS) break;
        }
        if (terms.isEmpty()) return "";

        Map<String, List<String>> synonyms = synonymRepository.findForTerms(terms);

        List<String> groups = new java.util.ArrayList<>();
        for (String term : terms) {
            // LinkedHashSet: a term whose synonym is also a typed term must
            // not appear twice inside its own OR group.
            java.util.Set<String> group = new java.util.LinkedHashSet<>();
            group.add(term);
            group.addAll(synonyms.getOrDefault(term, List.of()));

            String suffix = similarityMode ? "" : ":*";
            List<String> lexemes = group.stream().map(s -> s + suffix).toList();
            groups.add("(" + String.join(" | ", lexemes) + ")");
        }
        return String.join(joiner, groups);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id, @Nullable Long viewerUserId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        // Record happens AFTER orElseThrow: a 404 records nothing, structurally.
        // The call is async (ProductViewRecorder) — it never blocks or fails this request.
        viewRecorder.record(id, viewerUserId);
        return toResponse(product);
    }

    @Transactional
    public ProductResponse create(ProductRequest request, UserPrincipal me) {
        assertSkuAvailable(request.sku());
        Product product = new Product();
        applyRequest(product, request);
        product.setVendor(userRepository.getReferenceById(me.getId()));
        try {
            // saveAndFlush: force the INSERT here so a SKU race surfaces inside
            // the try (house pattern from ReviewService), not at commit.
            return toResponse(productRepository.saveAndFlush(product));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateSkuException(request.sku());
        }
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request, UserPrincipal me) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        assertOwnerOrAdmin(product, me);
        // Only check when the SKU actually changes — the product's own live row
        // would otherwise fail the exists check against itself.
        if (!request.sku().equals(product.getSku())) {
            assertSkuAvailable(request.sku());
        }
        applyRequest(product, request);
        try {
            return toResponse(productRepository.saveAndFlush(product));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateSkuException(request.sku());
        }
    }

    @Transactional
    public void delete(Long id, UserPrincipal me) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        assertOwnerOrAdmin(product, me);
        product.setDeletedAt(java.time.LocalDateTime.now());
    }

    /** Pre-check for the clean 409; the saveAndFlush catch is the race backstop. */
    private void assertSkuAvailable(String sku) {
        if (productRepository.existsBySkuAndDeletedAtIsNull(sku)) {
            throw new DuplicateSkuException(sku);
        }
    }

    private void assertOwnerOrAdmin(Product product, UserPrincipal me) {
        boolean isAdmin = "ADMIN".equals(me.getRole());
        boolean isOwner = product.getVendor() != null
                && product.getVendor().getId().equals(me.getId());
        if (!isAdmin && !isOwner) {
            throw new AccessDeniedException(
                    "Vendor " + me.getId() + " does not own product " + product.getId());
        }
    }

    private void applyRequest(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setSku(request.sku());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setCategory(categoryService.requireBySlug(request.categorySlug()));
        product.setHandmade(request.handmadeOrFalse());
        product.setTags(normaliseTags(request.tagsOrEmpty()));
    }

    /**
     * Lowercased, trimmed, deduplicated, blanks dropped, order preserved.
     *
     * Without this "Vegan", "vegan ", and "vegan" are three different tags
     * and the filter silently splits a vendor's own catalogue across them.
     * Normalising on write rather than on read means the GIN index matches
     * exactly what a filter chip sends.
     */
    private static List<String> normaliseTags(List<String> raw) {
        return raw.stream()
                .filter(Objects::nonNull)
                .map(t -> t.trim().toLowerCase())
                .filter(t -> !t.isEmpty())
                .distinct()
                .toList();
    }

    // ---- mapping: ONE enriched mapper, three shapes over it -------------
    // Batch is the required shape for any list (one popularity query per
    // page); the single-product variant does one lookup and exists for
    // get/create/update. Never loop the single variant over a list.

    /** Single product — one popularity lookup. */
    @Transactional(readOnly = true)
    public ProductResponse toResponse(Product p) {
        return toResponse(p, popularityRepository.findById(p.getId()).orElse(null),
                variantRepository.findByProductIdOrderByPositionAscIdAsc(p.getId()));
    }

    /** Batch — one findAllById covers the whole list. Preserves input order. */
    @Transactional(readOnly = true)
    public List<ProductResponse> toResponses(List<Product> products) {
        Map<Long, ProductPopularity> pop = popularityMap(products);
        Map<Long, List<ProductVariant>> variants = variantMap(products);
        return products.stream()
                .map(p -> toResponse(p, pop.get(p.getId()),
                        variants.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    /** Batch over a page — same single query, pagination metadata preserved. */
    @Transactional(readOnly = true)
    public Page<ProductResponse> toResponses(Page<Product> page) {
        Map<Long, ProductPopularity> pop = popularityMap(page.getContent());
        Map<Long, List<ProductVariant>> variants = variantMap(page.getContent());
        return page.map(p -> toResponse(p, pop.get(p.getId()),
                variants.getOrDefault(p.getId(), List.of())));
    }

    /** One query for a whole page of products, so a grid is not N+1. */
    private Map<Long, List<ProductVariant>> variantMap(List<Product> products) {
        List<Long> ids = products.stream().map(Product::getId).toList();
        if (ids.isEmpty()) return Map.of();
        return variantRepository.findByProductIdInOrderByPositionAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(v -> v.getProduct().getId()));
    }

    private Map<Long, ProductPopularity> popularityMap(List<Product> products) {
        List<Long> ids = products.stream().map(Product::getId).toList();
        return popularityRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ProductPopularity::getProductId, Function.identity()));
    }

    /**
     * Null popularity is NORMAL, not exceptional — a product created since
     * the last hourly rebuild has no row yet. Zeros are the truthful answer.
     */
    private ProductResponse toResponse(Product p, @Nullable ProductPopularity pop,
                                       List<ProductVariant> variants) {
        User vendor = p.getVendor();
        Category category = p.getCategory();

        // A product with variants delegates BOTH stock and price to them
        // (V20). Stock is the sum, so "in stock" means "some option is
        // buyable"; price is the minimum, so a card reads as "from R120"
        // rather than quoting an option the shopper might not pick. Neither
        // is stored — a maintained total would be a dual write, and dual
        // writes drift.
        boolean hasVariants = !variants.isEmpty();
        int effectiveStock = hasVariants
                ? variants.stream().mapToInt(ProductVariant::getStockQuantity).sum()
                : p.getStock();
        BigDecimal effectivePrice = hasVariants
                ? variants.stream().map(ProductVariant::getPrice)
                        .min(BigDecimal::compareTo).orElse(p.getPrice())
                : p.getPrice();

        List<ProductDtos.VariantResponse> variantResponses = variants.stream()
                .map(v -> new ProductDtos.VariantResponse(
                        v.getId(), v.getLabel(), v.getSku(), v.getPrice(),
                        v.getStockQuantity(),
                        v.getImageKey() != null ? storage.publicUrl(v.getImageKey()) : null))
                .toList();

        return new ProductResponse(
                p.getId(), p.getName(), p.getDescription(), p.getSku(),
                effectivePrice, effectiveStock,
                vendor != null ? vendor.getId() : null,
                // Storefront name, NOT the person's name: a listing is
                // attributed to the business that sells it (V19).
                vendor != null ? vendor.getStorefrontName() : null,
                pop != null ? pop.getAvgRating() : BigDecimal.ZERO,
                pop != null ? pop.getReviewCount() : 0L,
                pop != null ? pop.getSalesCount() : 0L,
                p.getCreatedAt(),
                category.getSlug(),
                category.getName(),
                category.isTopLevel() ? null : category.getParent().getSlug(),
                Boolean.TRUE.equals(p.getHandmade()),
                List.copyOf(p.getTags()),
                p.getImageKey() != null ? storage.publicUrl(p.getImageKey()) : null,
                p.getDeletedAt(),
                variantResponses,
                // Set only on the related-items path, via withSimilarityReason.
                null);
    }
}

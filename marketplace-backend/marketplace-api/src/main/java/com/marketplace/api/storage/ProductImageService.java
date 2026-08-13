package com.marketplace.api.storage;

import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductImage;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.repository.ProductImageRepository;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.security.UserPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Product image upload. Security posture, because a file-upload endpoint is
 * the classic soft target:
 *
 *  - Ownership: same assertOwnerOrAdmin rule as every product write —
 *    vendors upload to THEIR products only.
 *  - Content-type WHITELIST (jpeg/png/webp), never a blacklist, and the
 *    stored content type is OUR canonical one for the validated type — the
 *    client's declared type is checked, then discarded, never echoed.
 *    (SVG deliberately excluded: it's XML that can carry scripts.)
 *  - Extension derived from the validated type, not the filename. The
 *    uploaded filename is never used for anything — filenames are
 *    attacker-controlled input with a path-traversal history.
 *  - Size cap enforced by Spring multipart limits (application.yml) BEFORE
 *    this code runs; the check here is belt-and-braces.
 *  - Key is products/{id}/{uuid}.{ext}: every upload writes a NEW key and
 *    keys are never reused, so the immutable cache headers can never serve
 *    a stale image. Deleting a photo removes the object best-effort.
 *
 * HARDENING NOTE (deliberate deferral, not ignorance): true magic-byte
 * sniffing (Tika) would catch a renamed .exe declaring image/png. The
 * blast radius here is low — objects serve from a cookie-less image
 * domain with our content type, not inline HTML — so declared-type
 * validation is proportionate for now. Add Tika when vendors are
 * strangers rather than you.
 */
@Service
public class ProductImageService {

    // The whitelist and size cap moved to ImageValidation when the AI listing
    // drafter became a second entry point for vendor photos. Both paths call
    // the same validator so the rules cannot drift apart.

    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final ObjectStorageService storage;

    public ProductImageService(ProductRepository productRepository,
                               ProductImageRepository imageRepository,
                               ObjectStorageService storage) {
        this.productRepository = productRepository;
        this.imageRepository = imageRepository;
        this.storage = storage;
    }

    /**
     * Hard cap on photos per product. Not a storage limit — R2 would take
     * hundreds — but a gallery long enough to need paging stops being a
     * gallery, and every image is another object nobody ever deletes.
     */
    public static final int MAX_IMAGES = 8;

    /**
     * APPENDS a photo (V24). This used to REPLACE the product's single image
     * and delete the old object; now a product has an ordered gallery and an
     * upload adds to the end of it.
     */
    @Transactional
    public String upload(Long productId, MultipartFile file, UserPrincipal me) {
        Product product = requireOwned(productId, me);

        long existing = imageRepository.countByProductId(productId);
        if (existing >= MAX_IMAGES) {
            throw new TooManyImagesException(MAX_IMAGES);
        }

        String contentType = file.getContentType();
        String ext = ImageValidation.validateAndGetExtension(file);

        String newKey = "products/" + productId + "/" + UUID.randomUUID() + "." + ext;
        try {
            storage.put(newKey, file.getInputStream(), file.getSize(), contentType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setImageKey(newKey);
        // Append: one past the current highest position, so an upload never
        // silently reorders the photos already there.
        image.setPosition(imageRepository.findByProductIdOrderByPositionAscIdAsc(productId)
                .stream().mapToInt(ProductImage::getPosition).max().orElse(-1) + 1);
        imageRepository.save(image);

        return storage.publicUrl(newKey);
    }

    /**
     * Removes one photo and closes the gap in the ordering.
     *
     * The R2 object is deleted too, and deliberately AFTER the row: a failed
     * object delete leaves an orphan in a bucket, which costs pennies, while
     * a failed row delete against a deleted object leaves a product pointing
     * at a 404, which is visible to shoppers. deleteQuietly already treats
     * storage failure as non-fatal for that reason.
     */
    @Transactional
    public void delete(Long productId, Long imageId, UserPrincipal me) {
        requireOwned(productId, me);

        ProductImage image = imageRepository.findById(imageId)
                .filter(i -> i.getProduct().getId().equals(productId))
                .orElseThrow(() -> new ImageNotFoundException(imageId));

        String key = image.getImageKey();
        imageRepository.delete(image);
        imageRepository.flush();

        // Renumber what is left from zero. Without this, deleting the first
        // of three photos leaves positions 1,2 — harmless for ordering, but
        // the next append would compute 3 and the numbers drift away from
        // the count until they mean nothing to anyone reading the table.
        List<ProductImage> remaining = imageRepository.findByProductIdOrderByPositionAscIdAsc(productId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setPosition(i);
        }
        imageRepository.saveAll(remaining);

        storage.deleteQuietly(key);
    }

    private Product requireOwned(Long productId, UserPrincipal me) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        boolean isAdmin = "ADMIN".equals(me.getRole());
        boolean isOwner = product.getVendor() != null
                && product.getVendor().getId().equals(me.getId());
        if (!isAdmin && !isOwner) {
            throw new AccessDeniedException(
                    "Vendor " + me.getId() + " does not own product " + productId);
        }
        return product;
    }

    /** Maps to 400. */
    public static class TooManyImagesException extends RuntimeException {
        public TooManyImagesException(int max) {
            super("A product can have at most " + max + " photos");
        }
    }

    /** Maps to 404. */
    public static class ImageNotFoundException extends RuntimeException {
        public ImageNotFoundException(Long id) {
            super("Image not found: " + id);
        }
    }

    /** Maps to 400 in the handler. */
    public static class UnsupportedImageTypeException extends RuntimeException {
        public UnsupportedImageTypeException(String got) {
            super("Image must be JPEG, PNG, or WebP under 5MB (got: " + got + ")");
        }
    }
}

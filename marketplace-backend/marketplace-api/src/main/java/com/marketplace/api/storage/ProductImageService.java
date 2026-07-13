package com.marketplace.api.storage;

import com.marketplace.api.entity.Product;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.security.UserPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 *  - Key is products/{id}/{uuid}.{ext}: replacing an image writes a NEW
 *    key and deletes the old (best-effort), so the immutable cache
 *    headers never serve a stale image under a reused key.
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

    private static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");
    private static final long MAX_BYTES = 5 * 1024 * 1024;

    private final ProductRepository productRepository;
    private final ObjectStorageService storage;

    public ProductImageService(ProductRepository productRepository,
                               ObjectStorageService storage) {
        this.productRepository = productRepository;
        this.storage = storage;
    }

    @Transactional
    public String upload(Long productId, MultipartFile file, UserPrincipal me) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        boolean isAdmin = "ADMIN".equals(me.getRole());
        boolean isOwner = product.getVendor() != null
                && product.getVendor().getId().equals(me.getId());
        if (!isAdmin && !isOwner) {
            throw new AccessDeniedException(
                    "Vendor " + me.getId() + " does not own product " + productId);
        }

        String contentType = file.getContentType();
        String ext = contentType != null ? ALLOWED.get(contentType) : null;
        if (ext == null) {
            throw new UnsupportedImageTypeException(contentType);
        }
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new UnsupportedImageTypeException("empty or over 5MB");
        }

        String newKey = "products/" + productId + "/" + UUID.randomUUID() + "." + ext;
        try {
            storage.put(newKey, file.getInputStream(), file.getSize(), contentType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String oldKey = product.getImageKey();
        product.setImageKey(newKey);
        if (oldKey != null) {
            storage.deleteQuietly(oldKey);
        }
        return storage.publicUrl(newKey);
    }

    /** Maps to 400 in the handler. */
    public static class UnsupportedImageTypeException extends RuntimeException {
        public UnsupportedImageTypeException(String got) {
            super("Image must be JPEG, PNG, or WebP under 5MB (got: " + got + ")");
        }
    }
}

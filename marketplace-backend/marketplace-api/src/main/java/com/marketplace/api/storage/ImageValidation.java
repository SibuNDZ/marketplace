package com.marketplace.api.storage;

import com.marketplace.api.storage.ProductImageService.UnsupportedImageTypeException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * The single definition of "is this an acceptable product image".
 *
 * Extracted from ProductImageService when the AI listing drafter became a
 * second entry point for vendor-supplied photos. Two copies of a 5MB cap and
 * a MIME whitelist drift the moment one of them is tuned, and the drift is
 * silent — the upload path would keep rejecting what the draft path accepts,
 * or worse, the reverse. One rule set, two callers.
 *
 * Throws UnsupportedImageTypeException (mapped to 400) rather than returning
 * a boolean so neither caller can forget to check the result.
 */
public final class ImageValidation {

    /** Content type -> file extension. Membership here IS the whitelist. */
    static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    static final long MAX_BYTES = 5 * 1024 * 1024;

    private ImageValidation() {}

    /**
     * Validates type and size, returning the canonical file extension for the
     * accepted content type.
     *
     * The drafter discards the return value — it needs the validation, not the
     * extension — but returning it keeps the upload path from re-deriving what
     * this method already computed.
     */
    public static String validateAndGetExtension(MultipartFile file) {
        String contentType = file.getContentType();
        String ext = contentType != null ? ALLOWED.get(contentType) : null;
        if (ext == null) {
            throw new UnsupportedImageTypeException(contentType);
        }
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new UnsupportedImageTypeException("empty or over 5MB");
        }
        return ext;
    }

    /** Whether a content type is one the model can be sent. */
    public static boolean isAllowedContentType(String contentType) {
        return contentType != null && ALLOWED.containsKey(contentType);
    }
}

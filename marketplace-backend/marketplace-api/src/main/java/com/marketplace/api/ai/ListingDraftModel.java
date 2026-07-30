package com.marketplace.api.ai;

/**
 * The seam between "we asked a model for a listing draft" and "which model,
 * over which wire". Everything above this interface — prompt assembly, JSON
 * parsing, slug validation, length clamping — is ours and is unit-testable
 * without a network call or an API key.
 *
 * This exists so the drafter's tests can exercise the parsing and fallback
 * paths (malformed JSON, invented category slugs, over-long descriptions)
 * deterministically. Those are the branches most likely to break, and they
 * are impossible to trigger on demand against a live model.
 */
public interface ListingDraftModel {

    /**
     * @param imageBytes  raw bytes of an already-validated product photo
     * @param mediaType   the validated content type (image/jpeg|png|webp)
     * @param prompt      the fully-assembled instruction, including the live
     *                    category slug list
     * @return the model's raw text response, exactly as returned — parsing,
     *         fence-stripping and validation belong to the caller
     * @throws DraftExceptions.DraftProviderException when the provider itself
     *         fails (transport, auth, rate limit on their side)
     */
    String draft(byte[] imageBytes, String mediaType, String prompt);
}

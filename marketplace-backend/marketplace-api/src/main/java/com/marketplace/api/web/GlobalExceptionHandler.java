package com.marketplace.api.web;

import com.marketplace.api.auth.AuthService.EmailAlreadyRegisteredException;
import com.marketplace.api.auth.AuthService.EmailNotVerifiedException;
import com.marketplace.api.auth.AuthService.UsernameTakenException;
import com.marketplace.api.ai.DraftExceptions.DraftProviderException;
import com.marketplace.api.ai.DraftExceptions.DraftRateLimitExceededException;
import com.marketplace.api.auth.UserTokenService;
import com.marketplace.api.exception.CategoryExceptions.CategoryNotFoundException;
import com.marketplace.api.exception.OrderExceptions.*;
import com.marketplace.api.exception.ProductExceptions.CompareAtPricingPausedException;
import com.marketplace.api.exception.ProductExceptions.DuplicateSkuException;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.exception.ReviewExceptions.*;
import com.marketplace.api.payment.PaymentExceptions.PaymentProviderException;
import com.marketplace.api.payout.PayoutExceptions;
import com.marketplace.api.service.ProductStockService.InsufficientAdjustmentException;
import com.marketplace.api.service.VariantSelection.VariantNotApplicableException;
import com.marketplace.api.service.VariantSelection.VariantRequiredException;
import com.marketplace.api.storage.ProductImageService.ImageNotFoundException;
import com.marketplace.api.storage.ProductImageService.TooManyImagesException;
import com.marketplace.api.storage.ProductImageService.UnsupportedImageTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single place where business exceptions become HTTP responses (RFC 7807
 * application/problem+json). Controllers and services never build error
 * responses — they throw, this translates.
 *
 * Status choices:
 * - InsufficientStock → 409: request was well-formed; conflicts with resource state.
 * - InvalidOrderState → 409 for the same reason.
 * - Ownership failures → 403 via AccessDeniedException.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({OrderNotFoundException.class, CartNotFoundException.class,
            ProductNotFoundException.class, ReviewNotFoundException.class,
            CategoryNotFoundException.class})
    public ProblemDetail notFound(RuntimeException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage());
    }

    @ExceptionHandler(NotVerifiedPurchaserException.class)
    public ProblemDetail notVerifiedPurchaser(NotVerifiedPurchaserException ex) {
        return problem(HttpStatus.FORBIDDEN, "Purchase required", ex.getMessage());
    }

    @ExceptionHandler(DuplicateReviewException.class)
    public ProblemDetail duplicateReview(DuplicateReviewException ex) {
        return problem(HttpStatus.CONFLICT, "Duplicate review", ex.getMessage());
    }

    // Deliberately NOT a blanket DataIntegrityViolationException → 409 mapping:
    // that would dress every future FK/constraint bug as a polite conflict.
    // Services translate the specific constraint they own (house pattern).
    @ExceptionHandler(DuplicateSkuException.class)
    public ProblemDetail duplicateSku(DuplicateSkuException ex) {
        return problem(HttpStatus.CONFLICT, "Duplicate SKU", ex.getMessage());
    }

    @ExceptionHandler(CompareAtPricingPausedException.class)
    public ProblemDetail compareAtPricingPaused(CompareAtPricingPausedException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Compare-at pricing paused", ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail insufficientStock(InsufficientStockException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Insufficient stock", ex.getMessage());
        pd.setProperty("shortages", ex.getShortages().stream()
                .map(s -> Map.of(
                        "productId", s.productId(),
                        "productName", s.productName(),
                        "requested", s.requested(),
                        "available", s.available()))
                .toList());
        return pd;
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ProblemDetail invalidOrderState(InvalidOrderStateException ex) {
        return problem(HttpStatus.CONFLICT, "Invalid order state", ex.getMessage());
    }

    @ExceptionHandler(PayoutExceptions.PayoutBatchNotFoundException.class)
    public ProblemDetail payoutBatchNotFound(PayoutExceptions.PayoutBatchNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Payout batch not found", ex.getMessage());
    }

    // Both 409, same reasoning as InvalidOrderState: the request was
    // well-formed but conflicts with the resource's current state (already
    // paid, not exported, non-positive vendor sum, incomplete banking).
    @ExceptionHandler({PayoutExceptions.InvalidPayoutStateException.class,
            PayoutExceptions.VendorNotPayableException.class})
    public ProblemDetail payoutConflict(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "Payout not possible", ex.getMessage());
    }

    // The selling gate, shopper-side: 409 with the blocked items enumerated,
    // the InsufficientStock shape — checkout renders which lines to remove.
    @ExceptionHandler(PayoutExceptions.VendorNotSellableException.class)
    public ProblemDetail vendorNotSellable(PayoutExceptions.VendorNotSellableException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Items temporarily unavailable", ex.getMessage());
        pd.setProperty("blockedItems", ex.getBlocked().stream()
                .map(b -> Map.of(
                        "vendorName", b.vendorName(),
                        "productName", b.productName()))
                .toList());
        return pd;
    }

    @ExceptionHandler(PayoutExceptions.StaleTermsVersionException.class)
    public ProblemDetail staleTermsVersion(PayoutExceptions.StaleTermsVersionException ex) {
        return problem(HttpStatus.CONFLICT, "Terms have changed", ex.getMessage());
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ProblemDetail emailTaken(EmailAlreadyRegisteredException ex) {
        return problem(HttpStatus.CONFLICT, "Email already registered", ex.getMessage());
    }

    @ExceptionHandler(com.marketplace.api.auth.AuthService.VendorDetailsRequiredException.class)
    public ProblemDetail vendorDetailsRequired(
            com.marketplace.api.auth.AuthService.VendorDetailsRequiredException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "Seller accounts need a few more details");
        pd.setProperty("errors", ex.getFieldErrors());
        return pd;
    }

    @ExceptionHandler(com.marketplace.api.service.ProductVariantService.DuplicateVariantLabelException.class)
    public ProblemDetail duplicateVariantLabel(
            com.marketplace.api.service.ProductVariantService.DuplicateVariantLabelException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Duplicate option", ex.getMessage());
        // Field-keyed so the variant editor marks the label input, matching
        // how the duplicate-SKU and username-taken conflicts already render.
        pd.setProperty("errors", Map.of("label", List.of(ex.getMessage())));
        return pd;
    }

    @ExceptionHandler(com.marketplace.api.service.ProductVariantService.VariantNotFoundException.class)
    public ProblemDetail variantNotFound(
            com.marketplace.api.service.ProductVariantService.VariantNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Option not found", ex.getMessage());
    }

    @ExceptionHandler(UsernameTakenException.class)
    public ProblemDetail usernameTaken(UsernameTakenException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Username taken",
                "That username is already in use.");
        // Field-keyed so the register form can mark the username input
        // rather than showing a detached banner, matching how 400 validation
        // errors already render.
        pd.setProperty("errors", Map.of("username", List.of("That username is already in use.")));
        return pd;
    }

    /**
     * 403 with a machine-readable code, NOT 401. A 401 would send the
     * frontend's api.ts into its refresh-then-retry path, which cannot
     * possibly help — there is no session to refresh — and would end with a
     * generic "invalid credentials" for someone whose password was correct.
     * The code is what lets the login page offer a resend button.
     */
    @ExceptionHandler(EmailNotVerifiedException.class)
    public ProblemDetail emailNotVerified(EmailNotVerifiedException ex) {
        ProblemDetail pd = problem(HttpStatus.FORBIDDEN, "Email not verified", ex.getMessage());
        pd.setProperty("code", "EMAIL_NOT_VERIFIED");
        return pd;
    }

    /** Expired, already-used, and unknown tokens are one case on purpose. */
    @ExceptionHandler(UserTokenService.InvalidTokenException.class)
    public ProblemDetail invalidToken(UserTokenService.InvalidTokenException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid or expired link", ex.getMessage());
    }

    @ExceptionHandler(InsufficientAdjustmentException.class)
    public ProblemDetail insufficientAdjustment(InsufficientAdjustmentException ex) {
        return problem(HttpStatus.CONFLICT, "Insufficient stock for adjustment", ex.getMessage());
    }

    @ExceptionHandler(EmptyCartException.class)
    public ProblemDetail emptyCart(EmptyCartException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Empty cart", ex.getMessage());
    }

    @ExceptionHandler(UnsupportedImageTypeException.class)
    public ProblemDetail unsupportedImageType(UnsupportedImageTypeException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Unsupported image", ex.getMessage());
    }

    // Both are the shopper picking (or failing to pick) an option, so both
    // are a 400 the buy box can render inline rather than a server fault.
    @ExceptionHandler(VariantRequiredException.class)
    public ProblemDetail variantRequired(VariantRequiredException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Option required", ex.getMessage());
    }

    @ExceptionHandler(VariantNotApplicableException.class)
    public ProblemDetail variantNotApplicable(VariantNotApplicableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid option", ex.getMessage());
    }

    @ExceptionHandler(TooManyImagesException.class)
    public ProblemDetail tooManyImages(TooManyImagesException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Too many images", ex.getMessage());
    }

    @ExceptionHandler(ImageNotFoundException.class)
    public ProblemDetail imageNotFound(ImageNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Image not found", ex.getMessage());
    }

    // Spring throws this BEFORE the controller when multipart limits trip
    // (application.yml's max-file-size) — without this mapping an oversized
    // upload is an ugly 500 instead of a clean 400.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail uploadTooLarge(MaxUploadSizeExceededException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Image too large", "Image too large: 5MB max");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid");
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.computeIfAbsent(fe.getField(), k -> new java.util.ArrayList<>())
                    .add(fe.getDefaultMessage());
        }
        pd.setProperty("errors", errors);
        return pd;
    }

    /** ?category=NOTREAL and similar unconvertible query/path params -> 400, not 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail typeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid parameter",
                "'%s' is not a valid value for '%s'".formatted(ex.getValue(), ex.getName()));
    }

    /** Malformed JSON body (bad enum value, wrong type, syntax error) -> 400, not 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail malformedBody(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "Request body could not be parsed");
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail badCredentials(BadCredentialsException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid email or password");
    }

    /**
     * 502, mirroring PaymentProviderException: the vendor's photo was fine,
     * an upstream we depend on returned something unusable. The raw model
     * output is logged (truncated) in ListingDraftService and never returned
     * to the browser — it is unvalidated third-party text.
     */
    @ExceptionHandler(DraftProviderException.class)
    public ProblemDetail draftProviderError(DraftProviderException ex) {
        log.error("Listing draft provider error", ex);
        return problem(HttpStatus.BAD_GATEWAY, "Drafting unavailable",
                "Drafting service returned an unusable response. Try again");
    }

    /**
     * ResponseEntity rather than a bare ProblemDetail so Retry-After can ride
     * along. The window is the real time to the next token, not a constant.
     */
    @ExceptionHandler(DraftRateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> draftRateLimited(DraftRateLimitExceededException ex) {
        ProblemDetail pd = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                "You have used this hour's drafting allowance. Retry after "
                        + ex.getRetryAfterSeconds() + " seconds, or fill the form manually.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(pd);
    }

    @ExceptionHandler(com.marketplace.api.feedback.FeedbackExceptions.FeedbackRateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> feedbackRateLimited(
            com.marketplace.api.feedback.FeedbackExceptions.FeedbackRateLimitExceededException ex) {
        ProblemDetail pd = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                "You have sent several pieces of feedback in a short time. "
                        + "Please wait " + ex.getRetryAfterSeconds() + " seconds and try again.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(pd);
    }

    @ExceptionHandler(com.marketplace.api.feedback.FeedbackService.FeedbackNotFoundException.class)
    public ProblemDetail feedbackNotFound(
            com.marketplace.api.feedback.FeedbackService.FeedbackNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Feedback not found", ex.getMessage());
    }

    @ExceptionHandler(PaymentProviderException.class)
    public ProblemDetail paymentProviderError(PaymentProviderException ex) {
        log.error("Payment provider error", ex);
        return problem(HttpStatus.BAD_GATEWAY, "Payment provider unavailable",
                "Payment provider unavailable");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail accessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden",
                "You do not have permission to perform this action");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "An unexpected error occurred");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setProperty("requestId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return pd;
    }
}

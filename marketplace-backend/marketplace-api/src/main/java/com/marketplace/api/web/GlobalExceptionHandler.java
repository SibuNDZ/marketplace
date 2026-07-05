package com.marketplace.api.web;

import com.marketplace.api.auth.AuthService.EmailAlreadyRegisteredException;
import com.marketplace.api.exception.OrderExceptions.*;
import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.exception.ReviewExceptions.*;
import com.marketplace.api.payment.PaymentExceptions.PaymentProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
            ProductNotFoundException.class, ReviewNotFoundException.class})
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

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ProblemDetail emailTaken(EmailAlreadyRegisteredException ex) {
        return problem(HttpStatus.CONFLICT, "Email already registered", ex.getMessage());
    }

    @ExceptionHandler(EmptyCartException.class)
    public ProblemDetail emptyCart(EmptyCartException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Empty cart", ex.getMessage());
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

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail badCredentials(BadCredentialsException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid email or password");
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
        return pd;
    }
}

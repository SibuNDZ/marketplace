package com.marketplace.api.controller;

import com.marketplace.api.dto.CartDtos.AddItemRequest;
import com.marketplace.api.dto.CartDtos.CartResponse;
import com.marketplace.api.dto.CartDtos.UpdateQuantityRequest;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.CartService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Routes are /api/v1/cart — singular, no user id in the path. There is no
 * "GET /users/{id}/cart" because that URL shape invites IDOR. Identity comes
 * exclusively from the token; cross-user access is unrepresentable.
 */
@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse getCart(@AuthenticationPrincipal UserPrincipal me) {
        return cartService.getCart(me.getId());
    }

    @PostMapping("/items")
    public CartResponse addItem(
            @Valid @RequestBody AddItemRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return cartService.addItem(me.getId(), request);
    }

    /**
     * variantId identifies WHICH line, since a product can now sit in the
     * cart more than once under different options. Optional, and absent
     * means the line with no option — the only kind that existed before V25,
     * so every pre-existing client call keeps addressing the same row.
     */
    @PutMapping("/items/{productId}")
    public CartResponse updateQuantity(
            @PathVariable Long productId,
            @RequestParam(required = false) Long variantId,
            @Valid @RequestBody UpdateQuantityRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return cartService.updateQuantity(me.getId(), productId, variantId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(
            @PathVariable Long productId,
            @RequestParam(required = false) Long variantId,
            @AuthenticationPrincipal UserPrincipal me) {
        return cartService.removeItem(me.getId(), productId, variantId);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@AuthenticationPrincipal UserPrincipal me) {
        cartService.clear(me.getId());
        return ResponseEntity.noContent().build();
    }
}

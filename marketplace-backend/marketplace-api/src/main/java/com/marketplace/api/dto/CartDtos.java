package com.marketplace.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public class CartDtos {

    public record AddItemRequest(
            @NotNull Long productId,
            @NotNull @Min(1) @Max(999) Integer quantity
    ) {}

    public record UpdateQuantityRequest(
            @NotNull @Min(1) @Max(999) Integer quantity
    ) {}

    /**
     * Cart lines show LIVE product price (unlike OrderItems, which snapshot).
     * That's correct: the cart is a quote, the order is a contract. subtotal
     * is computed server-side so the client never does money math.
     */
    public record CartResponse(
            List<CartLine> items,
            BigDecimal subtotal
    ) {
        public record CartLine(
                Long productId,
                String productName,
                BigDecimal unitPrice,
                int quantity,
                BigDecimal lineTotal,
                int availableStock // lets the UI warn "only 2 left" pre-checkout
        ) {}
    }
}

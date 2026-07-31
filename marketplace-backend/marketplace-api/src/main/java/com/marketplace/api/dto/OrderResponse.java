package com.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * API-facing order representation. name and unitPrice come from the OrderItem
 * snapshot — historical orders show what the customer actually paid, immune to
 * later price/name edits.
 *
 * shippingAddress is null until submitted at pay-time (orders 1-7 predate
 * this field and simply have no address), and is masked for admin viewers
 * on orders that aren't PAID-or-later — see OrderService.shippingFor, the
 * one place that rule is allowed to live.
 */
public record OrderResponse(
        Long id,
        String status,
        BigDecimal total,     // items + delivery fees — the amount actually charged
        LocalDateTime createdAt,
        List<OrderItemResponse> items,
        List<DeliveryFeeResponse> deliveryFees, // one per vendor charging delivery; empty = free
        ShippingDtos.ShippingAddressResponse shippingAddress
) {
    public record OrderItemResponse(
            Long productId,
            String productName,   // snapshot at purchase time
            BigDecimal unitPrice, // snapshot at purchase time
            int quantity,
            BigDecimal lineTotal
    ) {}

    /** Fee snapshot at placement time — vendor fee edits never reprice this. */
    public record DeliveryFeeResponse(
            String vendorName,
            BigDecimal fee
    ) {}
}

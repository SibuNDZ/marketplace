package com.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * API-facing order representation. name and unitPrice come from the OrderItem
 * snapshot — historical orders show what the customer actually paid, immune to
 * later price/name edits.
 */
public record OrderResponse(
        Long id,
        String status,
        BigDecimal total,
        LocalDateTime createdAt,
        List<OrderItemResponse> items
) {
    public record OrderItemResponse(
            Long productId,
            String productName,   // snapshot at purchase time
            BigDecimal unitPrice, // snapshot at purchase time
            int quantity,
            BigDecimal lineTotal
    ) {}
}

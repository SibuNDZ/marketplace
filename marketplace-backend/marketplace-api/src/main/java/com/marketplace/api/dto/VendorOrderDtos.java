package com.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The vendor's window onto an order, which is deliberately NARROWER than
 * OrderResponse: only their own line items and the dispatch address. No buyer
 * identity beyond the recipient details, no other vendors' items, no order
 * grand total — a vendor learns what they must ship and where, nothing else.
 */
public class VendorOrderDtos {

    public record VendorOrderResponse(
            Long orderId,
            String orderNumber,
            String status,
            LocalDateTime createdAt,
            List<VendorLineItem> items,      // ONLY this vendor's items
            BigDecimal itemsTotal,           // sum over their items, not the order total
            /** This vendor's delivery fee as snapshotted on the order; null when they charged none. */
            BigDecimal deliveryFee,
            /**
             * True only when the order is PAID and every item in it belongs to
             * this vendor. Mixed-vendor orders ship via admin for now, and the
             * UI should say so instead of offering a button that 409s.
             */
            boolean canShip,
            ShippingDtos.ShippingAddressResponse shipTo
    ) {}

    public record VendorLineItem(
            String productName,   // snapshot at purchase time
            int quantity,
            BigDecimal unitPrice, // snapshot at purchase time
            BigDecimal lineTotal
    ) {}
}

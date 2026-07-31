package com.marketplace.api.service;

import com.marketplace.api.dto.ShippingDtos;
import com.marketplace.api.dto.VendorOrderDtos.VendorLineItem;
import com.marketplace.api.dto.VendorOrderDtos.VendorOrderResponse;
import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderItem;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.exception.OrderExceptions.InvalidOrderStateException;
import com.marketplace.api.exception.OrderExceptions.OrderNotFoundException;
import com.marketplace.api.repository.OrderItemRepository;
import com.marketplace.api.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The vendor's read-only order window plus their one write: mark-as-shipped.
 *
 * Visibility rule: vendors see orders only from PAID onward. A PENDING order
 * may still expire unpaid, and CANCELLED means the purchase never happened —
 * showing either would leak buyer addresses for sales that do not exist
 * (the same reasoning as OrderService.shippingFor's admin masking, enforced
 * here structurally by the status filter rather than by masking).
 *
 * Ownership failures are 404, not 403: "this order is not yours" and "this
 * order does not exist" must be indistinguishable, or the endpoint becomes
 * an order-id oracle.
 */
@Service
public class VendorOrderService {

    private static final Logger log = LoggerFactory.getLogger(VendorOrderService.class);

    /** PAID and later. EnumSet, so a new status fails loudly in review, not silently here. */
    static final Set<OrderStatus> VENDOR_VISIBLE = EnumSet.of(
            OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.REFUNDED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusRecorder recorder;

    public VendorOrderService(OrderRepository orderRepository,
                              OrderItemRepository orderItemRepository,
                              OrderStatusRecorder recorder) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.recorder = recorder;
    }

    @Transactional(readOnly = true)
    public Page<VendorOrderResponse> list(Long vendorId, Pageable pageable) {
        Page<Order> orders = orderRepository.findVendorOrders(vendorId, VENDOR_VISIBLE, pageable);
        List<Long> ids = orders.map(Order::getId).getContent();
        if (ids.isEmpty()) {
            return orders.map(o -> null); // empty page, correct metadata
        }

        // Two set-based queries for the whole page, instead of touching each
        // order's items collection (one lazy load per row).
        Map<Long, List<OrderItem>> mineByOrder = orderItemRepository
                .findByOrderIdInAndProductVendorId(ids, vendorId).stream()
                .collect(Collectors.groupingBy(i -> i.getOrder().getId()));
        Set<Long> mixed = Set.copyOf(orderRepository.idsWithForeignItems(ids, vendorId));

        return orders.map(o -> toResponse(o,
                mineByOrder.getOrDefault(o.getId(), List.of()),
                !mixed.contains(o.getId())));
    }

    @Transactional(readOnly = true)
    public VendorOrderResponse get(Long vendorId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .filter(o -> VENDOR_VISIBLE.contains(o.getStatus()))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        List<OrderItem> mine = orderItemRepository
                .findByOrderIdInAndProductVendorId(List.of(orderId), vendorId);
        if (mine.isEmpty()) {
            throw new OrderNotFoundException(orderId);
        }
        boolean singleVendor = orderRepository.idsWithForeignItems(List.of(orderId), vendorId).isEmpty();
        return toResponse(order, mine, singleVendor);
    }

    /**
     * PAID -> SHIPPED, restricted to orders consisting ENTIRELY of this
     * vendor's items. A mixed order has no single party who can truthfully
     * say "the parcel is on its way", so those stay with admin until
     * per-vendor shipment splitting exists.
     *
     * Same lock as the webhook/expiry pair: two clicks of the button, or a
     * vendor racing an admin, serialize on the row instead of double-recording.
     */
    @Transactional
    public VendorOrderResponse markShipped(Long vendorId, Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .filter(o -> VENDOR_VISIBLE.contains(o.getStatus()))
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        List<OrderItem> mine = orderItemRepository
                .findByOrderIdInAndProductVendorId(List.of(orderId), vendorId);
        if (mine.isEmpty()) {
            throw new OrderNotFoundException(orderId);
        }
        if (!orderRepository.idsWithForeignItems(List.of(orderId), vendorId).isEmpty()) {
            throw new InvalidOrderStateException(
                    "Order " + orderId + " contains other vendors' items — "
                    + "mixed-vendor orders are shipped by an administrator");
        }
        OrderStatus current = order.getStatus();
        if (!OrderTransitions.isAllowed(current, OrderStatus.SHIPPED)) {
            throw new InvalidOrderStateException(
                    "Order " + orderId + " is " + current + " and cannot be marked shipped");
        }

        order.setStatus(OrderStatus.SHIPPED);
        recorder.record(order, current, OrderStatus.SHIPPED, vendorId, "Shipped by vendor");
        log.info("Order {} PAID -> SHIPPED by vendor {}", orderId, vendorId);
        return toResponse(order, mine, true);
    }

    private VendorOrderResponse toResponse(Order order, List<OrderItem> myItems, boolean singleVendor) {
        List<VendorLineItem> lines = myItems.stream()
                .map(i -> new VendorLineItem(
                        i.getProductNameAtPurchase(),
                        i.getQuantity(),
                        i.getPriceAtPurchase(),
                        i.getPriceAtPurchase().multiply(BigDecimal.valueOf(i.getQuantity()))))
                .toList();
        BigDecimal itemsTotal = lines.stream()
                .map(VendorLineItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new VendorOrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus().name(),
                order.getCreatedAt(),
                lines,
                itemsTotal,
                singleVendor && order.getStatus() == OrderStatus.PAID,
                shipTo(order));
    }

    /**
     * Every order here is PAID-or-later (the status filter above), so no
     * masking decision remains — only the "address never submitted" legacy
     * case, which maps to null exactly as OrderResponse does.
     */
    private ShippingDtos.ShippingAddressResponse shipTo(Order order) {
        if (order.getAddressLine1() == null) return null;
        return new ShippingDtos.ShippingAddressResponse(
                order.getRecipientName(),
                order.getPhone(),
                order.getAddressLine1(),
                order.getAddressLine2(),
                order.getCity(),
                order.getProvince(),
                order.getPostalCode());
    }
}

package com.marketplace.api.service;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.dto.ShippingDtos;
import com.marketplace.api.entity.*;
import com.marketplace.api.exception.OrderExceptions.*;
import com.marketplace.api.repository.CartRepository;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Order placement and lifecycle. Invariants held under ANY concurrency:
 *   1. Stock never goes negative (no overselling).
 *   2. Order + stock decrements commit atomically, or neither does.
 *   3. OrderItems snapshot price and name at purchase time.
 *   4. Cancelling a PENDING order restores exactly the stock it consumed.
 *
 * Concurrency: pessimistic row locks (SELECT ... FOR UPDATE) acquired in
 * ascending product-id order to prevent deadlocks. All stock reads that
 * follow a lock acquisition go through {@link #lockAndRefresh}, which forces
 * an entity refresh after the lock query. This is necessary because Hibernate's
 * first-level cache may hold an entity loaded earlier in the same session (e.g.
 * via the cart EntityGraph), causing the lock query to return stale state even
 * though the database row was updated and committed by a concurrent transaction
 * between that earlier load and the lock acquisition. Without the refresh,
 * the stock check and decrement silently operate on the pre-lock snapshot,
 * defeating the pessimistic-lock strategy entirely.
 */
@Service
public class OrderService {

    @PersistenceContext
    private EntityManager entityManager;

    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OrderStatusRecorder recorder;

    public OrderService(CartRepository cartRepository,
                        OrderRepository orderRepository,
                        ProductRepository productRepository,
                        OrderStatusRecorder recorder) {
        this.cartRepository = cartRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.recorder = recorder;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse placeOrder(Long userId) {
        // Acquire an exclusive row lock on the cart by running a native
        // SELECT ... FOR UPDATE via Session.doReturningWork, which executes
        // on Hibernate's own JDBC connection for the current transaction.
        // This is the only form that reliably holds a transaction-scoped lock
        // in Hibernate 6.6 — neither @Query(nativeQuery=true) nor
        // entityManager.find(PESSIMISTIC_WRITE) issues the lock through the
        // same connection in all Hibernate 6.6 execution paths.
        // Thread B blocks here while Thread A holds the lock. When Thread A
        // commits (cart items deleted), Thread B unblocks and findById reads
        // the empty state → EmptyCartException.
        Long cartId = entityManager.unwrap(Session.class).doReturningWork(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT id FROM carts WHERE user_id = ? FOR UPDATE")) {
                ps.setLong(1, userId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }
        });
        if (cartId == null) throw new CartNotFoundException(userId);
        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new CartNotFoundException(userId));

        if (cart.getItems().isEmpty()) {
            throw new EmptyCartException();
        }

        Map<Long, Integer> demandByProduct = cart.getItems().stream()
                .collect(Collectors.toMap(
                        ci -> ci.getProduct().getId(),
                        CartItem::getQuantity,
                        Integer::sum));

        List<Long> productIds = demandByProduct.keySet().stream().sorted().toList();
        Map<Long, Product> productsById = lockAndRefresh(productIds);

        // Validate ALL lines before decrementing ANY stock.
        List<InsufficientStockException.StockShortage> shortages = new ArrayList<>();
        for (Long productId : productIds) {
            Product product = productsById.get(productId);
            int requested = demandByProduct.get(productId);
            if (product == null) {
                shortages.add(new InsufficientStockException.StockShortage(
                        productId, "(product no longer exists)", requested, 0));
            } else if (product.getDeletedAt() != null) {
                shortages.add(new InsufficientStockException.StockShortage(
                        productId, product.getName() + " (no longer available)", requested, 0));
            } else if (product.getStock() < requested) {
                shortages.add(new InsufficientStockException.StockShortage(
                        productId, product.getName(), requested, product.getStock()));
            }
        }
        if (!shortages.isEmpty()) {
            throw new InsufficientStockException(shortages);
        }

        Order order = new Order();
        order.setOrderNumber("ORD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 16));
        order.setUser(cart.getUser());
        order.setStatus(OrderStatus.PENDING);

        BigDecimal total = BigDecimal.ZERO;
        List<CartItem> sortedItems = cart.getItems().stream()
                .sorted(Comparator.comparing(ci -> ci.getProduct().getId()))
                .toList();

        for (CartItem cartItem : sortedItems) {
            Product product = productsById.get(cartItem.getProduct().getId());
            product.setStock(product.getStock() - cartItem.getQuantity());

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProduct(product);
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setPriceAtPurchase(product.getPrice());
            orderItem.setProductNameAtPurchase(product.getName());

            order.getOrderItems().add(orderItem);
            total = total.add(product.getPrice()
                    .multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        }

        // One flat delivery fee per unique vendor in the cart, snapshotted
        // like item prices (Task 2.3). Vendors at R0 contribute no line —
        // free delivery is silence, not a zero row. Iteration follows the
        // sorted product order so fee rows are deterministic across runs.
        Map<Long, User> feeVendorsById = new LinkedHashMap<>();
        for (Long productId : productIds) {
            User vendor = productsById.get(productId).getVendor();
            if (vendor != null && vendor.getDeliveryFee().signum() > 0) {
                feeVendorsById.putIfAbsent(vendor.getId(), vendor);
            }
        }
        for (User vendor : feeVendorsById.values()) {
            OrderDeliveryFee fee = new OrderDeliveryFee();
            fee.setOrder(order);
            fee.setVendor(vendor);
            fee.setVendorNameAtPurchase(vendor.getFullName());
            fee.setFeeAtPurchase(vendor.getDeliveryFee());
            order.getDeliveryFees().add(fee);
            total = total.add(vendor.getDeliveryFee());
        }
        order.setTotalAmount(total);

        Order saved = orderRepository.save(order);
        recorder.record(saved, null, OrderStatus.PENDING, userId, "Order placed");
        cart.getItems().clear(); // orphanRemoval deletes cart_items on flush

        return toResponse(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse cancelOrder(Long orderId, Long userId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return cancelInternal(order, userId, "Cancelled by customer");
    }

    /**
     * Entry point for the expiry job. Uses findByIdForUpdate so that this
     * call and PaymentEventService.handleCheckoutCompleted serialize on the
     * same row lock — whoever wins writes their status, the loser backs off.
     * If the order is no longer PENDING (paid in the window), this is a
     * clean no-op; no exception is thrown so the job can continue sweeping.
     */
    @Transactional
    public void cancelExpired(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.PENDING) return; // paid meanwhile — job loses cleanly
        cancelInternal(order, order.getUser().getId(),
                "Expired — payment not completed within window");
    }

    /**
     * Stock-restoring cancellation body shared by cancelOrder (customer) and
     * cancelExpired (job). Callers are responsible for ownership / timing
     * checks before invoking. Guards PENDING status itself as a safety net.
     */
    private OrderResponse cancelInternal(Order order, Long changedBy, String note) {
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException(
                    "Only PENDING orders can be cancelled; order " + order.getId()
                    + " is " + order.getStatus());
        }

        Map<Long, Integer> restoreByProduct = order.getOrderItems().stream()
                .filter(oi -> oi.getProduct() != null)
                .collect(Collectors.toMap(
                        oi -> oi.getProduct().getId(),
                        OrderItem::getQuantity,
                        Integer::sum));

        List<Long> productIds = restoreByProduct.keySet().stream().sorted().toList();
        Map<Long, Product> productsById = lockAndRefresh(productIds);
        for (Product product : productsById.values()) {
            product.setStock(product.getStock() + restoreByProduct.get(product.getId()));
        }

        order.setStatus(OrderStatus.CANCELLED);
        recorder.record(order, OrderStatus.PENDING, OrderStatus.CANCELLED, changedBy, note);
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId, Long userId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return toResponse(order);
    }

    /**
     * Admin detail view — the one place an admin reads a single order's
     * items and (masked) address before shipping it. findWithItemsById is
     * the same EntityGraph the customer-facing getOrder relies on via
     * toResponse's item mapping.
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrderForAdmin(Long orderId) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return toResponse(order, true);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getMyOrders(Long userId, Pageable pageable) {
        return orderRepository.findByUserId(userId, pageable).map(this::toResponse);
    }

    /**
     * Acquires pessimistic write locks on the given product rows (in ascending ID order
     * to prevent deadlocks), then force-refreshes each entity from the database.
     *
     * The refresh step is non-optional: if these products were loaded earlier in the
     * same Hibernate session (e.g. via the cart EntityGraph in placeOrder, or via lazy
     * proxy initialisation in cancelOrder), the lock query returns the cached instance
     * rather than re-reading the row. A concurrent transaction may have modified and
     * committed that row between the earlier load and the lock acquisition, so without
     * the refresh the caller would validate and mutate a stale snapshot.
     *
     * @param productIds product IDs to lock, in any order; locking is always ascending
     * @return map of product ID to the freshly-read, locked, managed Product entity
     */
    private Map<Long, Product> lockAndRefresh(List<Long> productIds) {
        List<Product> locked = productRepository.findAllByIdForUpdate(productIds);
        locked.forEach(entityManager::refresh);
        return locked.stream().collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    /** Owner viewing their own order — always sees their own submitted address. */
    private OrderResponse toResponse(Order order) {
        return toResponse(order, false);
    }

    private OrderResponse toResponse(Order order, boolean viewerIsPrivileged) {
        List<OrderResponse.OrderItemResponse> items = order.getOrderItems().stream()
                .map(oi -> new OrderResponse.OrderItemResponse(
                        oi.getProduct() != null ? oi.getProduct().getId() : null,
                        oi.getProductNameAtPurchase(),
                        oi.getPriceAtPurchase(),
                        oi.getQuantity(),
                        oi.getPriceAtPurchase()
                                .multiply(BigDecimal.valueOf(oi.getQuantity()))))
                .toList();
        // Sorted by vendor name: deliveryFees is a Set (see Order), and an
        // API list whose order changes between reads looks like a bug.
        List<OrderResponse.DeliveryFeeResponse> fees = order.getDeliveryFees().stream()
                .sorted(Comparator.comparing(OrderDeliveryFee::getVendorNameAtPurchase))
                .map(f -> new OrderResponse.DeliveryFeeResponse(
                        f.getVendorNameAtPurchase(), f.getFeeAtPurchase()))
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                items,
                fees,
                shippingFor(order, viewerIsPrivileged));
    }

    /**
     * The entire masking rule, in one place, called from both viewer paths
     * so it can never quietly diverge between them.
     *
     * CUSTOMER (viewerIsPrivileged=false): always sees their own submitted
     * address, regardless of order status — it's their own data.
     *
     * ADMIN (viewerIsPrivileged=true): sees the address only once the order
     * is committed (PAID or later). Not for PENDING (might still expire
     * unpaid — same reasoning as why views expire after 90 days) and not
     * for CANCELLED (the purchase didn't happen).
     */
    private ShippingDtos.ShippingAddressResponse shippingFor(Order order, boolean viewerIsPrivileged) {
        if (order.getAddressLine1() == null) return null; // not submitted
        boolean payerCommitted = order.getStatus() != OrderStatus.PENDING
                && order.getStatus() != OrderStatus.CANCELLED;
        if (viewerIsPrivileged && !payerCommitted) return null; // masked for admin
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

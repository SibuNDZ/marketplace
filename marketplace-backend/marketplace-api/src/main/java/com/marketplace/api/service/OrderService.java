package com.marketplace.api.service;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.*;
import com.marketplace.api.exception.OrderExceptions.*;
import com.marketplace.api.repository.CartRepository;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
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
        // Lock the cart row FIRST (before product locks) so that concurrent
        // same-user checkout attempts block here. The second call to arrive
        // will see the cleared cart after the first commits and throw
        // EmptyCartException — making duplicate orders impossible.
        Cart cart = cartRepository.findByUserIdForUpdate(userId)
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
        order.setTotalAmount(total);

        Order saved = orderRepository.save(order);
        recorder.record(saved, null, OrderStatus.PENDING, userId, "Order placed");
        cart.getItems().clear();

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

    private OrderResponse toResponse(Order order) {
        List<OrderResponse.OrderItemResponse> items = order.getOrderItems().stream()
                .map(oi -> new OrderResponse.OrderItemResponse(
                        oi.getProduct() != null ? oi.getProduct().getId() : null,
                        oi.getProductNameAtPurchase(),
                        oi.getPriceAtPurchase(),
                        oi.getQuantity(),
                        oi.getPriceAtPurchase()
                                .multiply(BigDecimal.valueOf(oi.getQuantity()))))
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                items);
    }
}

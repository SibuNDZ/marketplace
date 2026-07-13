package com.marketplace.api.payment;

import com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest;
import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderItem;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.exception.OrderExceptions.InvalidOrderStateException;
import com.marketplace.api.exception.OrderExceptions.OrderNotFoundException;
import com.marketplace.api.repository.OrderRepository;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Creates Stripe Checkout Sessions for PENDING orders.
 *
 * Money handling: Stripe wants minor units (cents). totalAmount and
 * priceAtPurchase are BigDecimal rand — multiply by 100 and use
 * longValueExact(), which THROWS on sub-cent precision rather than
 * silently rounding someone's money. If that ever throws, the bug is
 * upstream in price data, and loud is correct.
 *
 * Line items come from the OrderItem SNAPSHOTS (priceAtPurchase,
 * productNameAtPurchase) — the customer pays what checkout showed them,
 * even if the vendor repriced between order placement and payment.
 *
 * Session expiry is set to PAYMENT_WINDOW_MINUTES so Stripe stops
 * accepting payment at roughly the same moment the expiry job becomes
 * eligible to cancel the order (job cutoff is deliberately a few minutes
 * LATER — see OrderExpiryJob — so the common case is "session expired,
 * then order cancelled", not a race).
 */
@Service
public class StripeCheckoutService {

    public static final int PAYMENT_WINDOW_MINUTES = 30;

    private final OrderRepository orderRepository;
    private final String secretKey;
    private final String successUrl;
    private final String cancelUrl;

    public StripeCheckoutService(OrderRepository orderRepository,
                                 @Value("${app.stripe.secret-key}") String secretKey,
                                 @Value("${app.stripe.success-url}") String successUrl,
                                 @Value("${app.stripe.cancel-url}") String cancelUrl) {
        this.orderRepository = orderRepository;
        this.secretKey = secretKey;
        this.successUrl = successUrl;
        this.cancelUrl = cancelUrl;
    }

    @PostConstruct
    void init() {
        Stripe.apiKey = secretKey;
    }

    /**
     * Ownership enforced via findByIdAndUserId — you pay for your own orders.
     * Calling this twice on a still-PENDING order creates a fresh session
     * (fine: old sessions just expire; Stripe sessions are not reservations,
     * so the address is simply overwritten with whatever was submitted most
     * recently — consistent with there being no separate "edit address"
     * flow).
     *
     * Not readOnly: the shipping address is written onto the order in THIS
     * transaction, before the Stripe session is created. One transaction —
     * address saved and session created together, or neither is. No window
     * where a checkout session exists for an order with no address on file.
     */
    @Transactional
    public String createCheckoutSession(Long orderId, Long userId, ShippingAddressRequest shipping) {
        Order order = attachShipping(orderId, userId, shipping);

        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl + "?order=" + order.getId())
                .setCancelUrl(cancelUrl + "?order=" + order.getId())
                .setClientReferenceId(String.valueOf(order.getId()))
                .putMetadata("order_id", String.valueOf(order.getId()))
                .setExpiresAt(Instant.now()
                        .plusSeconds(PAYMENT_WINDOW_MINUTES * 60L).getEpochSecond());

        for (OrderItem oi : order.getOrderItems()) {
            params.addLineItem(
                    SessionCreateParams.LineItem.builder()
                            .setQuantity((long) oi.getQuantity())
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency("zar")
                                    .setUnitAmount(toCents(oi.getPriceAtPurchase()))
                                    .setProductData(SessionCreateParams.LineItem.PriceData
                                            .ProductData.builder()
                                            .setName(oi.getProductNameAtPurchase())
                                            .build())
                                    .build())
                            .build());
        }

        try {
            return Session.create(params.build()).getUrl();
        } catch (StripeException e) {
            throw new PaymentExceptions.PaymentProviderException(
                    "Failed to create checkout session for order " + orderId, e);
        }
    }

    static long toCents(BigDecimal rand) {
        return rand.multiply(BigDecimal.valueOf(100)).longValueExact();
    }

    /**
     * Loads, validates ownership + PENDING status, and writes the shipping
     * address onto the order — the exact piece createCheckoutSession needs
     * committed before it calls out to Stripe. Package-private so this is
     * unit-testable on its own: Session.create() is a real network call to
     * Stripe with no sandbox in this test suite (test/resources/
     * application.yml deliberately stubs app.stripe.secret-key with a
     * placeholder, same reasoning as the R2 storage stubs), so tests can't
     * drive the full createCheckoutSession path — this is the piece of it
     * that matters for the "address saved before session exists" guarantee,
     * and it's fully exercisable without Stripe.
     */
    @Transactional
    Order attachShipping(Long orderId, Long userId, ShippingAddressRequest shipping) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException(
                    "Order " + orderId + " is " + order.getStatus() + " — only PENDING orders can be paid");
        }

        order.setRecipientName(shipping.recipientName());
        order.setPhone(shipping.phone());
        order.setAddressLine1(shipping.addressLine1());
        order.setAddressLine2(shipping.addressLine2());
        order.setCity(shipping.city());
        order.setProvince(shipping.province());
        order.setPostalCode(shipping.postalCode());
        return order;
    }
}

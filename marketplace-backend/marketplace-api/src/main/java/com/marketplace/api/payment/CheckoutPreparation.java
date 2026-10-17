package com.marketplace.api.payment;

import com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest;
import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.exception.OrderExceptions.InvalidOrderStateException;
import com.marketplace.api.exception.OrderExceptions.OrderNotFoundException;
import com.marketplace.api.repository.OrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The provider-independent half of starting a payment: load the order,
 * enforce ownership (404, not 403: order ids must not be an oracle) and
 * PENDING status, and write the shipping address in the SAME transaction
 * that will create the provider checkout. Extracted from
 * StripeCheckoutService when PayFast arrived; both providers share these
 * semantics and must never drift apart.
 */
@Component
public class CheckoutPreparation {

    private final OrderRepository orderRepository;
    private final CheckoutPolicy checkoutPolicy;

    public CheckoutPreparation(OrderRepository orderRepository, CheckoutPolicy checkoutPolicy) {
        this.orderRepository = orderRepository;
        this.checkoutPolicy = checkoutPolicy;
    }

    @Transactional
    public Order attachShipping(Long orderId, Long userId, ShippingAddressRequest shipping) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException(
                    "Order " + orderId + " is " + order.getStatus()
                    + "; only PENDING orders can be paid");
        }

        // A test order stays a test order for life (no payout, no vendor
        // email, "no money moved" to the buyer). Once live keys are in and
        // checkout is open, paying one would take real money for an order the
        // rest of the system treats as a rehearsal, so it has to be re-placed.
        if (order.isTestOrder() && !checkoutPolicy.isGuarded()) {
            throw new InvalidOrderStateException(
                    "Order " + orderId + " was placed while payments ran in test mode, so it "
                    + "cannot be paid with live payments. Please place the order again.");
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

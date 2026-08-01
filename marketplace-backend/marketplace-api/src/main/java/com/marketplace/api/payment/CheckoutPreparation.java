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

    public CheckoutPreparation(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
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

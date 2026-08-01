package com.marketplace.api.payment;

import com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest;
import com.marketplace.api.entity.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.LinkedHashMap;

/**
 * Builds the signed field set for PayFast's hosted payment page. Unlike
 * Stripe there is no server-side session: the "checkout" IS this signed
 * form, which the frontend auto-submits to the process URL.
 *
 * PayFast takes ONE amount and ONE item_name — no line items. The charge is
 * order.totalAmount (items + delivery fees, snapshotted at placement) and
 * the item_name is the order number; itemization lives on our pages and
 * emails. m_payment_id carries the order id natively, which is the entire
 * metadata story (compare the Stripe webhook's raw-JSON metadata saga).
 *
 * Field INSERTION ORDER below is load-bearing: the signature must be built
 * over the documented attribute order, which is why this returns a
 * LinkedHashMap and the frontend renders fields in iteration order.
 */
@Service
public class PayfastCheckoutService {

    private final CheckoutPreparation checkoutPreparation;
    private final String merchantId;
    private final String merchantKey;
    private final String passphrase;
    private final String processUrl;
    private final String returnUrl;
    private final String cancelUrl;
    private final String notifyUrl;

    public PayfastCheckoutService(CheckoutPreparation checkoutPreparation,
                                  @Value("${app.payfast.merchant-id}") String merchantId,
                                  @Value("${app.payfast.merchant-key}") String merchantKey,
                                  @Value("${app.payfast.passphrase:}") String passphrase,
                                  @Value("${app.payfast.process-url}") String processUrl,
                                  @Value("${app.payfast.return-url}") String returnUrl,
                                  @Value("${app.payfast.cancel-url}") String cancelUrl,
                                  @Value("${app.payfast.notify-url}") String notifyUrl) {
        this.checkoutPreparation = checkoutPreparation;
        this.merchantId = merchantId;
        this.merchantKey = merchantKey;
        this.passphrase = passphrase;
        this.processUrl = processUrl;
        this.returnUrl = returnUrl;
        this.cancelUrl = cancelUrl;
        this.notifyUrl = notifyUrl;
    }

    public record PayfastCheckout(String processUrl, LinkedHashMap<String, String> fields) {}

    /**
     * Same transaction boundary as the Stripe path: the address is written
     * before any redirect exists, so a checkout never points at an order
     * with no address on file.
     */
    @Transactional
    public PayfastCheckout createCheckout(Long orderId, Long userId, ShippingAddressRequest shipping) {
        Order order = checkoutPreparation.attachShipping(orderId, userId, shipping);

        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        // Merchant block (documented order)
        fields.put("merchant_id", merchantId);
        fields.put("merchant_key", merchantKey);
        fields.put("return_url", returnUrl + "?order=" + order.getId());
        fields.put("cancel_url", cancelUrl + "?order=" + order.getId());
        fields.put("notify_url", notifyUrl);
        // Customer block
        fields.put("name_first", order.getUser().getFirstName());
        fields.put("email_address", order.getUser().getEmail());
        // Transaction block
        fields.put("m_payment_id", String.valueOf(order.getId()));
        fields.put("amount", order.getTotalAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        fields.put("item_name", "eRestyu order " + order.getOrderNumber());

        fields.put("signature", PayfastSignature.sign(fields, passphrase));
        return new PayfastCheckout(processUrl, fields);
    }
}

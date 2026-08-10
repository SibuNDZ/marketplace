package com.marketplace.api.payment;

import com.marketplace.api.entity.Order;
import com.marketplace.api.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ITN gauntlet: PayFast's four prescribed security checks, in an order
 * chosen so the cheapest and most decisive checks run first, then the
 * existing idempotent state machine does the actual transition. A failed
 * check DROPS the notification with a loud log; the HTTP layer still
 * returns 200 (retrying a forged or mismatched payload can never make it
 * valid, and non-200 just makes PayFast hammer the endpoint — same
 * reasoning as the Stripe webhook's business-anomaly handling).
 *
 * The amount check is load-bearing here, not belt-and-braces: PayFast's
 * redirect form fields are client-visible, so "buyer paid what the order
 * says" must be re-established server-side from the ITN's amount_gross
 * against the snapshotted totalAmount.
 */
@Service
public class PayfastItnService {

    private static final Logger log = LoggerFactory.getLogger(PayfastItnService.class);
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");

    private final PaymentEventService eventService;
    private final OrderRepository orderRepository;
    private final PayfastValidator validator;
    private final String passphrase;
    private final String merchantId;

    public PayfastItnService(PaymentEventService eventService,
                             OrderRepository orderRepository,
                             PayfastValidator validator,
                             @Value("${app.payfast.passphrase:}") String passphrase,
                             @Value("${app.payfast.merchant-id}") String merchantId) {
        this.eventService = eventService;
        this.orderRepository = orderRepository;
        this.validator = validator;
        this.passphrase = passphrase;
        this.merchantId = merchantId;
    }

    public void handle(String rawBody) {
        List<Map.Entry<String, String>> ordered = PayfastSignature.parseOrdered(rawBody);
        Map<String, String> params = new LinkedHashMap<>();
        ordered.forEach(e -> params.putIfAbsent(e.getKey(), e.getValue()));

        String mPaymentId = params.get("m_payment_id");
        String pfPaymentId = params.get("pf_payment_id");

        // Check 1: signature, over the fields in the order received.
        if (!PayfastSignature.verifyItn(ordered, params.get("signature"), passphrase)) {
            log.error("PayFast ITN REJECTED (bad signature) for m_payment_id {} pf_payment_id {}",
                    mPaymentId, pfPaymentId);
            return;
        }

        // Belongs-to-us check: an ITN for another merchant's account is
        // misdelivery or mischief either way.
        if (!merchantId.equals(params.get("merchant_id"))) {
            log.error("PayFast ITN REJECTED (merchant_id {} is not ours) for m_payment_id {}",
                    params.get("merchant_id"), mPaymentId);
            return;
        }

        Long orderId = parseOrderId(mPaymentId);
        if (orderId == null) {
            log.error("PayFast ITN REJECTED (unparseable m_payment_id '{}') pf_payment_id {}",
                    mPaymentId, pfPaymentId);
            return;
        }

        String status = params.get("payment_status");
        if (!"COMPLETE".equals(status)) {
            // CANCELLED (subscriptions) or anything unrecognised: not a
            // payment, nothing to transition. Logged for the audit trail.
            log.info("PayFast ITN for order {} with payment_status {} — no transition", orderId, status);
            return;
        }

        // Check 2: the amount actually paid matches the snapshotted total.
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.error("PayFast ITN REJECTED (unknown order {}) pf_payment_id {}", orderId, pfPaymentId);
            return;
        }
        BigDecimal gross = parseAmount(params.get("amount_gross"));
        if (gross == null
                || order.getTotalAmount().subtract(gross).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
            // The alert string. If this fires, money and order disagree and
            // a human must look before anything ships.
            log.error("PAYFAST AMOUNT MISMATCH for order {}: expected {} but ITN says '{}' "
                            + "(pf_payment_id {}) — MANUAL REVIEW REQUIRED, order NOT transitioned",
                    orderId, order.getTotalAmount(), params.get("amount_gross"), pfPaymentId);
            return;
        }

        // Check 3: PayFast's server confirms it actually sent this.
        if (!validator.confirms(paramStringWithoutSignature(ordered))) {
            log.error("PayFast ITN REJECTED (validate endpoint refused) for order {} pf_payment_id {}",
                    orderId, pfPaymentId);
            return;
        }

        // (Check 4, source host, is enforced at the controller/network layer
        // where the connection details live; see PayfastController.)

        // The state machine takes it from here, idempotently: duplicate
        // COMPLETE ITNs land in "already PAID, ignoring", exactly like
        // duplicate Stripe webhooks.
        eventService.handleCheckoutCompleted(orderId, "PayFast");
    }

    static String paramStringWithoutSignature(List<Map.Entry<String, String>> ordered) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : ordered) {
            if ("signature".equals(e.getKey())) break;
            if (!sb.isEmpty()) sb.append('&');
            sb.append(e.getKey()).append('=')
              .append(PayfastSignature.encode(e.getValue() == null ? "" : e.getValue()));
        }
        return sb.toString();
    }

    private static Long parseOrderId(String mPaymentId) {
        try {
            return mPaymentId == null ? null : Long.valueOf(mPaymentId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseAmount(String raw) {
        try {
            return raw == null ? null : new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

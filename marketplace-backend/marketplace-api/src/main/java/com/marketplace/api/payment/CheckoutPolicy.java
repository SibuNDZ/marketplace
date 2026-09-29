package com.marketplace.api.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Who may check out, given the payment mode.
 *
 * While the payment provider runs TEST credentials, a checkout moves no money
 * but otherwise looks exactly like a real sale: the order goes PAID, the
 * vendor is emailed "a customer paid", a payout is recorded, and the product
 * gains a public "sold". That happened for real on 2026-09-29, from the
 * owner's own test. So while payments are in test mode, checkout is GUARDED:
 *
 *  - only admins can place an order or start a payment (a shopper browsing
 *    the live site cannot produce a fake sale)
 *  - every order placed meanwhile is flagged test_order, and a test order has
 *    no effect as a sale anywhere (see Order.testOrder)
 *
 * The guard switches itself off: it applies only while
 * {@link PaymentHealth#isTestMode()} is true, so the deploy that puts live keys
 * in opens checkout to everyone with nothing else to remember. "unknown" mode
 * is not test mode, and a live key never guards.
 *
 * app.payments.test-mode-checkout chooses the behaviour in test mode:
 *   admins-only (the default, and production's setting)
 *   open        (the test classpath, and a local dev box that wants to try
 *                checkout as a customer; orders placed then are NOT flagged,
 *                so they behave as real sales, which is what those tests assert)
 * Anything unrecognised is treated as admins-only: fail closed.
 */
@Component
public class CheckoutPolicy {

    private static final Logger log = LoggerFactory.getLogger(CheckoutPolicy.class);

    private final boolean guarded;

    public CheckoutPolicy(PaymentHealth paymentHealth,
                          @Value("${app.payments.test-mode-checkout:admins-only}") String testModeCheckout) {
        String value = testModeCheckout == null ? "" : testModeCheckout.strip().toLowerCase();
        boolean open = value.equals("open");
        if (!open && !value.equals("admins-only")) {
            log.warn("app.payments.test-mode-checkout='{}' is not admins-only or open; "
                    + "treating it as admins-only", testModeCheckout);
        }
        this.guarded = paymentHealth.isTestMode() && !open;
        if (guarded) {
            log.info("Payments are in TEST mode: checkout is limited to admins, and their orders "
                    + "are flagged as test orders. This lifts automatically with live keys.");
        }
    }

    /** True while payments are in test mode and checkout is limited to admins. */
    public boolean isGuarded() {
        return guarded;
    }

    /** "admins" while guarded, else "everyone". Public, for the storefront. */
    public String openTo() {
        return guarded ? "admins" : "everyone";
    }

    /**
     * Throws unless this role may check out right now. Accepts "ADMIN" and
     * "ROLE_ADMIN", since the role reaches here from both the entity and the
     * security principal.
     */
    public void requireCanCheckOut(String role) {
        if (guarded && !isAdmin(role)) {
            throw new CheckoutNotOpenException();
        }
    }

    /** Whether an order placed right now must be flagged test_order. */
    public boolean flagsNewOrdersAsTest() {
        return guarded;
    }

    private static boolean isAdmin(String role) {
        return "ADMIN".equals(role) || "ROLE_ADMIN".equals(role);
    }

    /** 409: checkout exists, it is just not open to this user yet. */
    public static class CheckoutNotOpenException extends RuntimeException {
        public CheckoutNotOpenException() {
            super("Checkout opens once live payments are switched on. Your cart is saved, "
                    + "so you can come back and check out then.");
        }
    }
}

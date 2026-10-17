package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest;
import com.marketplace.api.security.UserPrincipal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain unit test for PaymentController.extractOrderId — no Spring context,
 * no Stripe signature needed. Guards the version-drift fix: this must read
 * order_id straight from the raw payload regardless of what stripe-java's
 * typed EventDataObjectDeserializer would have made of the same bytes (see
 * class Javadoc — evt_1TsQPhDQkBKfcjoqCDgBhi3q in production).
 */
class PaymentControllerTest {

    private final PaymentController controller =
            new PaymentController(null, null, null, null, null, new ObjectMapper(), "whsec_test", "stripe", null);

    // Real-shaped: trimmed to the fields extractOrderId actually reads, but
    // same nesting Stripe sends for checkout.session.completed.
    private static final String REAL_SHAPED_PAYLOAD = """
            {
              "id": "evt_1TsQPhDQkBKfcjoqCDgBhi3q",
              "object": "event",
              "type": "checkout.session.completed",
              "data": {
                "object": {
                  "id": "cs_test_a1NPRuI3ENsLNbOxVAUNasvtBdT4CUGxYj6iSvaZYWZlzbZWEpGLJXXEsZ",
                  "object": "checkout.session",
                  "payment_status": "paid",
                  "metadata": {
                    "order_id": "2"
                  }
                }
              }
            }
            """;

    @Test
    void extractOrderId_readsFromRawPayload() {
        assertThat(controller.extractOrderId(REAL_SHAPED_PAYLOAD)).isEqualTo("2");
    }

    @Test
    void extractOrderId_missingMetadata_returnsNull() {
        String payload = """
                {"type": "checkout.session.completed", "data": {"object": {"id": "cs_test_x"}}}
                """;
        assertThat(controller.extractOrderId(payload)).isNull();
    }

    @Test
    void extractOrderId_malformedJson_returnsNullNotException() {
        assertThat(controller.extractOrderId("not json")).isNull();
    }

    // --- provider dispatch ---------------------------------------------------

    private static final ShippingAddressRequest ADDRESS = new ShippingAddressRequest(
            "Thandi Mokoena", "+27 82 000 0000", "12 Milkwood Lane",
            null, "Gqeberha", "Eastern Cape", "6001");
    private static final UserPrincipal BUYER =
            new UserPrincipal(3L, "buyer@example.com", "x", "CUSTOMER", true);

    /** Live keys: checkout open to everyone, so dispatch is what is under test. */
    private static CheckoutPolicy openCheckout() {
        return new CheckoutPolicy(
                new PaymentHealth("paystack", "", "", "sk_live_x", "", "", ""), "admins-only");
    }

    @Test
    void paddedMixedCaseProvider_dispatchesToPaystack_notStripe() {
        // The validator and health endpoint trim and lowercase; pay() must
        // agree, or "paystack " boots healthy and 502s every checkout.
        StripeCheckoutService stripe = mock(StripeCheckoutService.class);
        PaystackCheckoutService paystack = mock(PaystackCheckoutService.class);
        when(paystack.createCheckout(7L, 3L, ADDRESS)).thenReturn("https://checkout.paystack.com/x");
        PaymentController c = new PaymentController(
                stripe, null, null, paystack, null, new ObjectMapper(), "", " Paystack ", openCheckout());

        assertThat(c.pay(7L, ADDRESS, BUYER))
                .isEqualTo(Map.of("checkoutUrl", "https://checkout.paystack.com/x"));
        verifyNoInteractions(stripe);
    }

    @Test
    void unknownProvider_neverFallsThroughToStripe() {
        StripeCheckoutService stripe = mock(StripeCheckoutService.class);
        PaymentController c = new PaymentController(
                stripe, null, null, null, null, new ObjectMapper(), "", "paystak", openCheckout());

        assertThatThrownBy(() -> c.pay(7L, ADDRESS, BUYER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paystak");
        verifyNoInteractions(stripe);
    }
}

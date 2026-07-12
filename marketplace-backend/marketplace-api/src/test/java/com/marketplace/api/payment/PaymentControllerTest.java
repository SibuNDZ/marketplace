package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit test for PaymentController.extractOrderId — no Spring context,
 * no Stripe signature needed. Guards the version-drift fix: this must read
 * order_id straight from the raw payload regardless of what stripe-java's
 * typed EventDataObjectDeserializer would have made of the same bytes (see
 * class Javadoc — evt_1TsQPhDQkBKfcjoqCDgBhi3q in production).
 */
class PaymentControllerTest {

    private final PaymentController controller =
            new PaymentController(null, null, new ObjectMapper(), "whsec_test");

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
}

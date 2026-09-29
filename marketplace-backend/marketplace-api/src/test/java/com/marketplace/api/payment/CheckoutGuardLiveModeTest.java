package com.marketplace.api.payment;

import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.VendorPayoutEntryRepository;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The guard's off switch is the payment mode, not a flag someone has to
 * remember: with LIVE keys and production's own admins-only setting,
 * checkout is open to everyone and orders are real sales.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CheckoutGuardLiveModeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        // Production's setting, with a live-shaped key. Only the prefix is read.
        registry.add("app.payments.test-mode-checkout", () -> "admins-only");
        registry.add("app.stripe.secret-key", () -> "sk_live_placeholder_for_tests");
    }

    @Autowired OrderService                orderService;
    @Autowired PaymentEventService         paymentEventService;
    @Autowired VendorPayoutEntryRepository entryRepository;
    @Autowired TestFixtures                fixtures;
    @Autowired MockMvc                     mockMvc;

    @Test
    void liveKeysOpenCheckoutAndOrdersAreRealSales() throws Exception {
        mockMvc.perform(get("/api/v1/payments/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("live"))
                .andExpect(jsonPath("$.checkoutOpenTo").value("everyone"));

        String tag = UUID.randomUUID().toString().substring(0, 8);
        User vendor = fixtures.vendor("live-guard-vendor-" + tag);
        Product p = fixtures.productForVendor("Live Guard Item " + tag, "SKU-LG-" + tag,
                new BigDecimal("100.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf("live-guard-buyer-" + tag, p);

        OrderResponse order = orderService.placeOrder(buyer.getId());
        assertThat(order.testOrder()).isFalse();

        paymentEventService.handleCheckoutCompleted(order.id(), "Stripe");
        // A real sale owes the vendor: the payout entry is written.
        assertThat(entryRepository.findByOrderId(order.id())).hasSize(1);
    }
}

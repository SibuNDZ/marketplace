package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.api.dto.OrderResponse;
import com.marketplace.api.dto.ShippingDtos.ShippingAddressRequest;
import com.marketplace.api.entity.Order;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorPayoutEntry;
import com.marketplace.api.repository.OrderRepository;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.repository.VendorPayoutEntryRepository;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Split switch ON: the initialize request carries a flat split equal to
 * the ledger's per-vendor net, only for vendors with a subaccount code,
 * and what was sent is persisted on the order. Rate pinned to 12.5% like
 * CommissionLedgerTest so the literal numbers below hold.
 */
@Testcontainers
@SpringBootTest
class PaystackSplitCheckoutTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final HttpServer STUB;
    static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/transaction/initialize", exchange -> {
                LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] out = ("{\"status\":true,\"data\":{\"authorization_url\":\"https://checkout.paystack.com/x\","
                        + "\"access_code\":\"x\",\"reference\":\"x\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            STUB.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        registry.add("app.paystack.initialize-url",
                () -> "http://127.0.0.1:" + STUB.getAddress().getPort() + "/transaction/initialize");
        registry.add("app.paystack.split-enabled", () -> "true");
        registry.add("app.payouts.commission-rate", () -> "0.125");
    }

    @Autowired PaystackCheckoutService     checkout;
    @Autowired PaymentEventService         paymentEventService;
    @Autowired OrderService                orderService;
    @Autowired OrderRepository             orderRepository;
    @Autowired UserRepository              userRepository;
    @Autowired VendorPayoutEntryRepository entryRepository;
    @Autowired TestFixtures                fixtures;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final ShippingAddressRequest ADDRESS = new ShippingAddressRequest(
            "Thandi Mokoena", "+27 82 000 0000", "12 Milkwood Lane",
            null, "Gqeberha", "Eastern Cape", "6001");

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void reset() {
        LAST_BODY.set(null);
    }

    private User vendorWith(String tag, String code, BigDecimal override, BigDecimal deliveryFee) {
        User v = fixtures.vendor(uniq(tag));
        v.setPaystackSubaccountCode(code);
        v.setCommissionRate(override);
        v.setDeliveryFee(deliveryFee);
        return userRepository.save(v);
    }

    @Test
    @DisplayName("two vendors, one override, one without a subaccount: shares equal the ledger net, the unsubaccounted vendor is absent")
    void splitMatchesLedger() throws Exception {
        // Vendor A: default 12.5%, R100 item, R20 delivery, subaccount -> net 100 - 12.50 + 20 = 107.50
        User a = vendorWith("split-a", "ACCT_a", null, new BigDecimal("20.00"));
        // Vendor B: 20% override, R50 item, free delivery, subaccount -> net 50 - 10 = 40.00
        User b = vendorWith("split-b", "ACCT_b", new BigDecimal("0.2000"), BigDecimal.ZERO);
        // Vendor C: no subaccount yet -> stays with the main account, EFT ledger entry
        User c = vendorWith("split-c", null, null, BigDecimal.ZERO);

        Product pa = fixtures.productForVendor(uniq("A Item"), uniq("SKU-SA"), new BigDecimal("100.00"), 5, a);
        Product pb = fixtures.productForVendor(uniq("B Item"), uniq("SKU-SB"), new BigDecimal("50.00"), 5, b);
        Product pc = fixtures.productForVendor(uniq("C Item"), uniq("SKU-SC"), new BigDecimal("30.00"), 5, c);
        User buyer = fixtures.customerWithCartOf(uniq("split-buyer"), pa, pb, pc);
        OrderResponse placed = orderService.placeOrder(buyer.getId());

        checkout.createCheckout(placed.id(), buyer.getId(), ADDRESS);

        JsonNode sent = mapper.readTree(LAST_BODY.get());
        long amount = Long.parseLong(sent.get("amount").asText());
        assertThat(amount).isEqualTo(20_000); // 100 + 20 + 50 + 30
        JsonNode split = sent.get("split");
        assertThat(split).isNotNull();
        assertThat(split.get("type").asText()).isEqualTo("flat");
        assertThat(split.get("bearer_type").asText()).isEqualTo("account");
        assertThat(split.get("reference").asText()).isEqualTo("ERY-" + placed.id() + "-SPLIT");
        JsonNode subs = split.get("subaccounts");
        assertThat(subs).hasSize(2);
        assertThat(subs.get(0).get("subaccount").asText()).isEqualTo("ACCT_a");
        assertThat(subs.get(0).get("share").asLong()).isEqualTo(10_750);
        assertThat(subs.get(1).get("subaccount").asText()).isEqualTo("ACCT_b");
        assertThat(subs.get(1).get("share").asLong()).isEqualTo(4_000);
        assertThat(LAST_BODY.get()).doesNotContain("ACCT_c").doesNotContain("1122336789");

        // Persisted as sent.
        Order order = orderRepository.findById(placed.id()).orElseThrow();
        assertThat(order.getSplitRequest()).isNotNull();
        assertThat(mapper.readTree(order.getSplitRequest()).get("subaccounts")).hasSize(2);

        // The ledger's net per vendor equals the share sent, by construction.
        paymentEventService.handleCheckoutCompleted(placed.id(), "Paystack");
        List<VendorPayoutEntry> entries = entryRepository.findByOrderId(placed.id());
        assertThat(entries).hasSize(3);
        assertThat(entries.stream().filter(e -> e.getVendor().getId().equals(a.getId())).findFirst().orElseThrow()
                .getNetPayable()).isEqualByComparingTo("107.50");
        assertThat(entries.stream().filter(e -> e.getVendor().getId().equals(b.getId())).findFirst().orElseThrow()
                .getNetPayable()).isEqualByComparingTo("40.00");
        assertThat(entries.stream().filter(e -> e.getVendor().getId().equals(c.getId())).findFirst().orElseThrow()
                .getNetPayable()).isEqualByComparingTo("26.25");
    }

    @Test
    @DisplayName("no vendor with a subaccount: the request carries no split and nothing is persisted")
    void noSubaccountsNoSplit() throws Exception {
        User c = vendorWith("split-none", null, null, BigDecimal.ZERO);
        Product pc = fixtures.productForVendor(uniq("Plain Item"), uniq("SKU-SP"), new BigDecimal("30.00"), 5, c);
        User buyer = fixtures.customerWithCartOf(uniq("plain-buyer"), pc);
        OrderResponse placed = orderService.placeOrder(buyer.getId());

        checkout.createCheckout(placed.id(), buyer.getId(), ADDRESS);

        assertThat(mapper.readTree(LAST_BODY.get()).has("split")).isFalse();
        assertThat(orderRepository.findById(placed.id()).orElseThrow().getSplitRequest()).isNull();
    }
}

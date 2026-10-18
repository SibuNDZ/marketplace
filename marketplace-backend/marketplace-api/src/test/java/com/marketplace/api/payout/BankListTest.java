package com.marketplace.api.payout;

import com.marketplace.api.entity.User;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.TestFixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /vendor/settings/payouts/banks: served from the 24h cache after one
 * upstream call; 503 with a readable problem when Paystack is down and
 * nothing is cached; never a hardcoded list.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class BankListTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final HttpServer STUB;
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicInteger CALLS = new AtomicInteger();
    static final String BANKS = "{\"status\":true,\"data\":[{\"name\":\"Nedbank\",\"code\":\"198765\",\"slug\":\"nedbank\"},"
            + "{\"name\":\"Capitec Bank\",\"code\":\"470010\",\"slug\":\"capitec\"},"
            + "{\"name\":\"Absa Bank\",\"code\":\"632005\",\"slug\":\"absa\"}]}";

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/bank", exchange -> {
                CALLS.incrementAndGet();
                String body = STATUS.get() == 200 ? BANKS : "{\"status\":false,\"message\":\"down\"}";
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(STATUS.get(), out.length);
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
        registry.add("app.paystack.api-base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @Autowired BankListService bankListService;
    @Autowired TestFixtures    fixtures;
    @Autowired MockMvc         mockMvc;
    @Autowired JwtService      jwtService;

    private String vendorToken() {
        User vendor = fixtures.vendor("banks-vendor-" + UUID.randomUUID().toString().substring(0, 8));
        return "Bearer " + jwtService.generateToken(vendor.getId(), vendor.getRole().name());
    }

    @BeforeEach
    void reset() {
        bankListService.evict();
        STATUS.set(200);
        CALLS.set(0);
    }

    @Test
    @DisplayName("the list is fetched once and served from cache, sorted by name")
    void servedFromCache() throws Exception {
        String token = vendorToken();
        mockMvc.perform(get("/api/v1/vendor/settings/payouts/banks").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Absa Bank"))
                .andExpect(jsonPath("$[0].code").value("632005"))
                .andExpect(jsonPath("$[2].name").value("Nedbank"));
        mockMvc.perform(get("/api/v1/vendor/settings/payouts/banks").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Paystack down with nothing cached is 503 with the message the card shows")
    void unavailableIs503() throws Exception {
        STATUS.set(503);
        mockMvc.perform(get("/api/v1/vendor/settings/payouts/banks").header("Authorization", vendorToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Bank list unavailable"))
                .andExpect(jsonPath("$.detail").value("Bank list unavailable, try again shortly"));
    }

    @Test
    @DisplayName("Paystack down with a warm cache keeps serving the cached list")
    void staleBeatsNothing() throws Exception {
        String token = vendorToken();
        mockMvc.perform(get("/api/v1/vendor/settings/payouts/banks").header("Authorization", token))
                .andExpect(status().isOk());
        STATUS.set(503);
        bankListService.evictTtlOnly();
        mockMvc.perform(get("/api/v1/vendor/settings/payouts/banks").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        assertThat(CALLS.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a customer cannot read the list")
    void vendorOnly() throws Exception {
        User customer = fixtures.customer("banks-customer-" + UUID.randomUUID().toString().substring(0, 8));
        String token = "Bearer " + jwtService.generateToken(customer.getId(), customer.getRole().name());
        mockMvc.perform(get("/api/v1/vendor/settings/payouts/banks").header("Authorization", token))
                .andExpect(status().isForbidden());
    }
}

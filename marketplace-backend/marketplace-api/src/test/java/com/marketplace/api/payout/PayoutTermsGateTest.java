package com.marketplace.api.payout;

import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.payout.PayoutExceptions.StaleTermsVersionException;
import com.marketplace.api.payout.PayoutExceptions.VendorNotSellableException;
import com.marketplace.api.payout.VendorPayoutSettingsService.PayoutSettingsStatus;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.OrderService;
import com.marketplace.api.service.TestFixtures;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The selling gate WITH THE FLAG ON — this context flips
 * app.payouts.selling-gate-enabled, unlike every other suite.
 *
 * The flag-off behaviour needs no test of its own: every other suite's
 * fixture vendors are unbanked and unaccepted, and their orders place fine —
 * the whole test tree passing IS the flag-off proof.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PayoutTermsGateTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
        registry.add("app.payouts.selling-gate-enabled", () -> "true");
    }

    @Autowired OrderService                orderService;
    @Autowired VendorPayoutSettingsService settingsService;
    @Autowired UserRepository              userRepository;
    @Autowired TestFixtures                fixtures;
    @Autowired MockMvc                     mockMvc;
    @Autowired JwtService                  jwtService;

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Completes the vendor's onboarding: banking + current terms. */
    private void onboard(User vendor) {
        settingsService.updateBanking(vendor.getId(),
                "Holder " + vendor.getUsername(), "Nedbank", "1122336789", "198765",
                BankAccountType.CHEQUE);
        settingsService.acceptTerms(vendor.getId(), 1);
    }

    // ── the gate at checkout ─────────────────────────────────────────────

    @Test
    @DisplayName("an un-onboarded vendor's item cannot be checked out, and the 409 names it")
    void ungatedVendorBlocksCheckout() throws Exception {
        User vendor = fixtures.vendor(uniq("gate-vendor")); // no banking, no terms
        Product p = fixtures.productForVendor(uniq("Gated Item"), uniq("SKU-GT"),
                new BigDecimal("100.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("gate-buyer"), p);
        String buyerToken = jwtService.generateToken(buyer.getId(), buyer.getRole().name());

        // Service-level: the typed exception with the item enumerated.
        assertThatThrownBy(() -> orderService.placeOrder(buyer.getId()))
                .isInstanceOf(VendorNotSellableException.class)
                .hasMessageContaining("Gated Item");

        // HTTP-level: RFC 7807, 409, with the blocked items the UI renders.
        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Items temporarily unavailable"))
                .andExpect(jsonPath("$.blockedItems[0].productName").value(
                        org.hamcrest.Matchers.containsString("Gated Item")));
    }

    @Test
    @DisplayName("terms alone are not enough, and banking alone is not enough")
    void bothHalvesRequired() {
        User termsOnly = fixtures.vendor(uniq("terms-only"));
        settingsService.acceptTerms(termsOnly.getId(), 1);
        Product pt = fixtures.productForVendor(uniq("Terms Only Item"), uniq("SKU-GA"),
                new BigDecimal("50.00"), 5, termsOnly);
        User buyerA = fixtures.customerWithCartOf(uniq("gate-buyer-a"), pt);
        assertThatThrownBy(() -> orderService.placeOrder(buyerA.getId()))
                .isInstanceOf(VendorNotSellableException.class);

        User bankOnly = fixtures.vendor(uniq("bank-only"));
        settingsService.updateBanking(bankOnly.getId(),
                "Holder", "Nedbank", "9988776655", "112233", BankAccountType.SAVINGS);
        Product pb = fixtures.productForVendor(uniq("Bank Only Item"), uniq("SKU-GB"),
                new BigDecimal("50.00"), 5, bankOnly);
        User buyerB = fixtures.customerWithCartOf(uniq("gate-buyer-b"), pb);
        assertThatThrownBy(() -> orderService.placeOrder(buyerB.getId()))
                .isInstanceOf(VendorNotSellableException.class);
    }

    @Test
    @DisplayName("a fully onboarded vendor sells normally")
    void onboardedVendorSells() {
        User vendor = fixtures.vendor(uniq("onboarded-vendor"));
        onboard(vendor);
        Product p = fixtures.productForVendor(uniq("Open Item"), uniq("SKU-GO"),
                new BigDecimal("100.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("open-buyer"), p);

        assertThat(orderService.placeOrder(buyer.getId()).id()).isNotNull();
    }

    @Test
    @DisplayName("a mixed cart is blocked as a whole, naming only the gated vendor's items")
    void mixedCartNamesTheGatedHalf() {
        User open = fixtures.vendor(uniq("mixed-open"));
        onboard(open);
        User gated = fixtures.vendor(uniq("mixed-gated"));
        Product po = fixtures.productForVendor(uniq("Fine Item"), uniq("SKU-GM1"),
                new BigDecimal("50.00"), 5, open);
        Product pg = fixtures.productForVendor(uniq("Blocked Item"), uniq("SKU-GM2"),
                new BigDecimal("50.00"), 5, gated);
        User buyer = fixtures.customerWithCartOf(uniq("mixed-buyer"), po, pg);

        assertThatThrownBy(() -> orderService.placeOrder(buyer.getId()))
                .isInstanceOf(VendorNotSellableException.class)
                .hasMessageContaining("Blocked Item")
                .satisfies(e -> assertThat(((VendorNotSellableException) e).getBlocked())
                        .allSatisfy(b -> assertThat(b.productName()).doesNotContain("Fine Item")));
    }

    // ── acceptance mechanics ─────────────────────────────────────────────

    @Test
    @DisplayName("acceptance records the version and timestamp; the status reflects it")
    void acceptanceRecorded() {
        User vendor = fixtures.vendor(uniq("accept-vendor"));

        PayoutSettingsStatus before = settingsService.status(vendor.getId());
        assertThat(before.termsCurrent()).isFalse();
        assertThat(before.termsText()).contains("12.5% commission").contains("7 days");

        PayoutSettingsStatus after = settingsService.acceptTerms(vendor.getId(), 1);
        assertThat(after.termsCurrent()).isTrue();
        assertThat(after.acceptedVersion()).isEqualTo(1);
        assertThat(after.acceptedAt()).isNotNull();

        User persisted = userRepository.findById(vendor.getId()).orElseThrow();
        assertThat(persisted.getPayoutTermsVersion()).isEqualTo(1);
        assertThat(persisted.getPayoutTermsAcceptedAt()).isNotNull();
    }

    @Test
    @DisplayName("an old acceptance does not satisfy a bumped version — re-acceptance is required")
    void versionBumpForcesReacceptance() {
        User vendor = fixtures.vendor(uniq("bump-vendor"));
        onboard(vendor); // banking complete + accepted CURRENT version (1)

        // Simulate a vendor who accepted an EARLIER version before the bump
        // to the current one: the stored acceptance stops counting.
        vendor = userRepository.findById(vendor.getId()).orElseThrow();
        vendor.setPayoutTermsVersion(0);
        userRepository.save(vendor);

        Product p = fixtures.productForVendor(uniq("Stale Terms Item"), uniq("SKU-GS"),
                new BigDecimal("50.00"), 5, vendor);
        User buyer = fixtures.customerWithCartOf(uniq("bump-buyer"), p);
        assertThatThrownBy(() -> orderService.placeOrder(buyer.getId()))
                .isInstanceOf(VendorNotSellableException.class);

        // Re-accepting the current version restores selling.
        settingsService.acceptTerms(vendor.getId(), 1);
        assertThat(orderService.placeOrder(buyer.getId()).id()).isNotNull();
    }

    @Test
    @DisplayName("accepting a stale version number is refused, never recorded")
    void staleVersionRefused() {
        User vendor = fixtures.vendor(uniq("stale-vendor"));

        assertThatThrownBy(() -> settingsService.acceptTerms(vendor.getId(), 0))
                .isInstanceOf(StaleTermsVersionException.class);
        assertThat(userRepository.findById(vendor.getId()).orElseThrow()
                .getPayoutTermsVersion()).isNull();
    }

    // ── the settings surface stays masked ────────────────────────────────

    @Test
    @DisplayName("the vendor's own status response masks the account number")
    void ownStatusIsMasked() {
        User vendor = fixtures.vendor(uniq("mask-own-vendor"));
        PayoutSettingsStatus status = settingsService.updateBanking(vendor.getId(),
                "Holder", "Nedbank", "1122336789", "198765", BankAccountType.CHEQUE);

        assertThat(status.banking().accountNumberLast4()).isEqualTo("···6789");
        assertThat(status.banking().complete()).isTrue();
        assertThat(status.toString()).doesNotContain("1122336789");
    }
}

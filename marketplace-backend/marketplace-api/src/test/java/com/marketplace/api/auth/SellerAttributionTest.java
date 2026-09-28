package com.marketplace.api.auth;

import com.marketplace.api.auth.AuthDtos.RegisterRequest;
import com.marketplace.api.entity.ReferralSource;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seller attribution (V33): the "how did you hear about us" answer.
 *
 * The rules under test exist because this column is the platform's only
 * attribution signal, so what it contains decides whether a recruitment
 * campaign can be evaluated at all:
 *
 *   - it is asked of sellers, and a buyer's answer is ignored, or the seller
 *     numbers stop meaning sellers
 *   - an unknown value never fails a registration, because losing one
 *     attribution is cheap and losing a signup is not
 *   - the first answer wins, because both doors into a seller account ask,
 *     and one person can pass through both
 *   - unanswered reads as unknown and is reported separately, never folded
 *     into a channel
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SellerAttributionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
    }

    @Autowired MockMvc        mockMvc;
    @Autowired AuthService    authService;
    @Autowired JwtService     jwtService;
    @Autowired UserRepository userRepository;
    @Autowired TestFixtures   fixtures;

    private String tokenFor(User user) {
        return jwtService.generateToken(user.getId(), user.getRole().name());
    }

    private User registered(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }

    // --- registration ------------------------------------------------------

    @Test
    void vendorRegistration_recordsTheAnswer() {
        authService.register(new RegisterRequest(
                "tiktok@sa-test.local", "password123", "Thembi", "Ngcobo",
                "sa_tiktok", "VENDOR", "Thembi Watches", "TIKTOK", null));

        User vendor = registered("tiktok@sa-test.local");
        assertThat(vendor.getReferralSource()).isEqualTo(ReferralSource.TIKTOK);
        assertThat(vendor.getReferralSourceDetail()).isNull();
    }

    @Test
    void lowercaseAnswer_isAccepted() {
        // The wire value comes from a client we do not control forever. Case
        // is not a reason to throw away the one signal we have.
        authService.register(new RegisterRequest(
                "case@sa-test.local", "password123", "Nomvula", "Dlamini",
                "sa_case", "VENDOR", "Case Crafts", "facebook", null));

        assertThat(registered("case@sa-test.local").getReferralSource())
                .isEqualTo(ReferralSource.FACEBOOK);
    }

    @Test
    void unknownAnswer_isDiscarded_andTheRegistrationStillSucceeds() {
        // The whole point: a stale or wrong client value costs the attribution,
        // never the seller. A 400 here would turn a marketing field into an
        // outage on the signup path.
        authService.register(new RegisterRequest(
                "unknown@sa-test.local", "password123", "Lerato", "Mahlangu",
                "sa_unknown", "VENDOR", "Unknown Origins", "PIGEON_POST", "irrelevant"));

        User vendor = registered("unknown@sa-test.local");
        assertThat(vendor.getReferralSource()).isNull();
        assertThat(vendor.getReferralSourceDetail()).isNull();
    }

    @Test
    void buyerAnswer_isIgnored() {
        authService.register(new RegisterRequest(
                "buyer@sa-test.local", "password123", "Sibongile", null,
                "sa_buyer", "CUSTOMER", null, "FACEBOOK", null));

        assertThat(registered("buyer@sa-test.local").getReferralSource()).isNull();
    }

    @Test
    void freeTextLabel_isKeptForOther_andDroppedForEverythingElse() {
        authService.register(new RegisterRequest(
                "other@sa-test.local", "password123", "Zanele", "Khumalo",
                "sa_other", "VENDOR", "Other Goods", "OTHER", "  Church group  "));
        authService.register(new RegisterRequest(
                "labelled@sa-test.local", "password123", "Ayanda", "Zulu",
                "sa_labelled", "VENDOR", "Labelled Co", "WHATSAPP", "a group chat"));

        User other = registered("other@sa-test.local");
        assertThat(other.getReferralSource()).isEqualTo(ReferralSource.OTHER);
        assertThat(other.getReferralSourceDetail()).isEqualTo("Church group");

        // A label attached to a known channel is noise in the admin table, and
        // accepting it would let a client relabel an answer it did not give.
        assertThat(registered("labelled@sa-test.local").getReferralSourceDetail()).isNull();
    }

    // --- the upgrade door -------------------------------------------------

    @Test
    void becomeVendor_recordsTheAnswer() throws Exception {
        User buyer = fixtures.customer("sa-upgrade");

        mockMvc.perform(post("/api/v1/account/become-vendor")
                        .header("Authorization", "Bearer " + tokenFor(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"Upgrade Crafts","lastName":"Mokoena",
                                 "referralSource":"EVENT"}"""))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(buyer.getId()).orElseThrow().getReferralSource())
                .isEqualTo(ReferralSource.EVENT);
    }

    @Test
    void becomeVendor_cannotOverwriteAnAnswerAlreadyGiven() throws Exception {
        User buyer = fixtures.customer("sa-overwrite");
        String token = tokenFor(buyer);

        mockMvc.perform(post("/api/v1/account/become-vendor")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"First Answer","lastName":"Mokoena",
                                 "referralSource":"FRIEND"}"""))
                .andExpect(status().isOk());

        // This endpoint is idempotent for vendors, so a seller reopening the
        // form to rename their stall must not be able to rewrite how they were
        // attributed. First answer is the one given closest to arriving.
        mockMvc.perform(post("/api/v1/account/become-vendor")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"Renamed Stall","lastName":"Mokoena",
                                 "referralSource":"SEARCH"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Renamed Stall"));

        assertThat(userRepository.findById(buyer.getId()).orElseThrow().getReferralSource())
                .isEqualTo(ReferralSource.FRIEND);
    }

    @Test
    void becomeVendor_withoutAnAnswer_stillWorks() throws Exception {
        // The field is required by the form, not by the API. A seller must
        // never be locked out of their own upgrade by a marketing question.
        User buyer = fixtures.customer("sa-no-answer");

        mockMvc.perform(post("/api/v1/account/become-vendor")
                        .header("Authorization", "Bearer " + tokenFor(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"Silent Co\",\"lastName\":\"Nkosi\"}"))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(buyer.getId()).orElseThrow().getReferralSource())
                .isNull();
    }

    // --- the admin read ---------------------------------------------------

    @Test
    void adminSources_reportsUnknownSeparately_andTheTotalsReconcile() throws Exception {
        // A vendor with no answer: every seller who registered before this
        // shipped looks like this, and early on they are the majority.
        fixtures.vendor("sa-silent-vendor");
        authService.register(new RegisterRequest(
                "counted@sa-test.local", "password123", "Palesa", "Sithole",
                "sa_counted", "VENDOR", "Counted Crafts", "TIKTOK", null));

        String body = mockMvc.perform(get("/api/v1/admin/sellers/sources")
                        .header("Authorization", "Bearer " + tokenFor(fixtures.admin("sa-admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unknown").value(org.hamcrest.Matchers.greaterThan(0)))
                .andReturn().getResponse().getContentAsString();

        // Asserted as an invariant rather than as fixed numbers: other tests
        // in this class share the container and create vendors of their own.
        com.fasterxml.jackson.databind.JsonNode json =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertThat(json.get("answered").asLong() + json.get("unknown").asLong())
                .isEqualTo(json.get("totalVendors").asLong());
        assertThat(json.get("sources").findValuesAsText("source"))
                .contains("TIKTOK")
                .doesNotContain("null");
    }

    @Test
    void adminSources_isAdminOnly() throws Exception {
        // Who recruited whom is not vendor-visible, and the endpoint sits
        // behind the same /api/v1/admin/** rule as the payout run.
        mockMvc.perform(get("/api/v1/admin/sellers/sources")
                        .header("Authorization", "Bearer " + tokenFor(fixtures.vendor("sa-nosy"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/sellers/sources"))
                .andExpect(status().isUnauthorized());
    }
}

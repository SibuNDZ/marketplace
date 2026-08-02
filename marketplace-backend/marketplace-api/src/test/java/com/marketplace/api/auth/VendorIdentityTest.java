package com.marketplace.api.auth;

import com.marketplace.api.auth.AuthDtos.RegisterRequest;
import com.marketplace.api.auth.AuthService.VendorDetailsRequiredException;
import com.marketplace.api.dto.ProductDtos.ProductResponse;
import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.UserRole;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.ProductService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vendor identity (V19) and the buyer -> seller upgrade.
 *
 * The rule under test is that seller obligations are role-scoped: vendors
 * must supply a surname and a public business name, buyers must not be asked
 * for either, and listings are attributed to the business, never the person.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class VendorIdentityTest {

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
    @Autowired ProductService productService;
    @Autowired TestFixtures   fixtures;

    private String tokenFor(User user) {
        return jwtService.generateToken(user.getId(), user.getRole().name());
    }

    // --- registration: role-scoped requirements ---------------------------

    @Test
    void vendorRegistration_requiresBusinessNameAndSurname_withFieldKeyedErrors() {
        assertThatThrownBy(() -> authService.register(new RegisterRequest(
                "no-details@vi-test.local", "password123",
                "Thandi", null, "vi_nodetails", "VENDOR", null)))
                .isInstanceOf(VendorDetailsRequiredException.class)
                .satisfies(e -> assertThat(((VendorDetailsRequiredException) e).getFieldErrors())
                        .containsOnlyKeys("lastName", "businessName"));
    }

    @Test
    void vendorRegistration_withBothDetails_storesBusinessName() {
        authService.register(new RegisterRequest(
                "full@vi-test.local", "password123",
                "Thandi", "Mokoena", "vi_full", "VENDOR", "Morning Star Essentials"));

        User vendor = userRepository.findByEmail("full@vi-test.local").orElseThrow();
        assertThat(vendor.getRole()).isEqualTo(UserRole.VENDOR);
        assertThat(vendor.getBusinessName()).isEqualTo("Morning Star Essentials");
        assertThat(vendor.getStorefrontName()).isEqualTo("Morning Star Essentials");
    }

    @Test
    void buyerRegistration_stillNeedsNeitherSurnameNorBusinessName() {
        // The mononym rule (AuthVerificationTest) must survive role-scoping:
        // tightening this for everyone was explicitly rejected as signup
        // friction at the worst possible moment.
        authService.register(new RegisterRequest(
                "buyer@vi-test.local", "password123",
                "Sibongile", null, "vi_buyer", "CUSTOMER", null));

        User buyer = userRepository.findByEmail("buyer@vi-test.local").orElseThrow();
        assertThat(buyer.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(buyer.getBusinessName()).isNull();
    }

    // --- listings are attributed to the business --------------------------

    @Test
    void productCard_showsBusinessName_notThePersonsName() {
        User vendor = fixtures.vendor("vi-storefront");
        userRepository.findById(vendor.getId()).ifPresent(v -> {
            v.setFirstName("Thandi");
            v.setLastName("Mokoena");
            v.setBusinessName("Karoo Veldt Provisions");
            userRepository.save(v);
        });
        Product p = fixtures.productForVendor("VI-Honey", "SKU-VI-1",
                new BigDecimal("89.00"), 5, vendor);

        ProductResponse response = productService.listMine(vendor.getId(), PageRequest.of(0, 50))
                .getContent().stream()
                .filter(r -> r.id().equals(p.getId()))
                .findFirst().orElseThrow();

        assertThat(response.vendorName()).isEqualTo("Karoo Veldt Provisions");
        assertThat(response.vendorName()).doesNotContain("Thandi").doesNotContain("Mokoena");
    }

    // --- buyer -> seller upgrade ------------------------------------------

    @Test
    void buyerBecomesVendor_selfServe_andRoleIsLiveImmediately() throws Exception {
        User buyer = fixtures.customer("vi-upgrader");
        String token = tokenFor(buyer);

        // The token still claims CUSTOMER after this call; the filter reloads
        // the user each request, so the new role must apply with no re-login.
        mockMvc.perform(post("/api/v1/account/become-vendor")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"Cavioure Designers\",\"lastName\":\"Ndzukuma\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VENDOR"))
                .andExpect(jsonPath("$.businessName").value("Cavioure Designers"));

        assertThat(userRepository.findById(buyer.getId()).orElseThrow().getRole())
                .isEqualTo(UserRole.VENDOR);

        // Vendor-only route, reached with the pre-upgrade token.
        mockMvc.perform(get("/api/v1/products/mine")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void becomeVendor_withoutBusinessName_400() throws Exception {
        User buyer = fixtures.customer("vi-nodetails-upgrade");
        mockMvc.perform(post("/api/v1/account/become-vendor")
                        .header("Authorization", "Bearer " + tokenFor(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"  \",\"lastName\":\"Dlamini\"}"))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findById(buyer.getId()).orElseThrow().getRole())
                .isEqualTo(UserRole.CUSTOMER);
    }

    @Test
    void becomeVendor_asAdmin_isRefused_notASilentDowngrade() throws Exception {
        User admin = fixtures.admin("vi-admin");
        mockMvc.perform(post("/api/v1/account/become-vendor")
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"Sneaky Co\",\"lastName\":\"Admin\"}"))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(admin.getId()).orElseThrow().getRole())
                .isEqualTo(UserRole.ADMIN);
    }

    @Test
    void becomeVendor_twice_isIdempotent() throws Exception {
        User buyer = fixtures.customer("vi-twice");
        String body = "{\"businessName\":\"Twice Traders\",\"lastName\":\"Mokoena\"}";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/account/become-vendor")
                            .header("Authorization", "Bearer " + tokenFor(buyer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("VENDOR"));
        }
    }

    // --- editing the storefront name --------------------------------------

    @Test
    void vendorCannotBlankTheirBusinessName_butBuyerNeedNotSupplyOne() throws Exception {
        User vendor = fixtures.vendor("vi-rename");
        mockMvc.perform(put("/api/v1/account")
                        .header("Authorization", "Bearer " + tokenFor(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Thandi\",\"lastName\":\"M\",\"phoneNumber\":\"\",\"businessName\":\"\"}"))
                .andExpect(status().isBadRequest());

        User buyer = fixtures.customer("vi-buyer-edit");
        mockMvc.perform(put("/api/v1/account")
                        .header("Authorization", "Bearer " + tokenFor(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Sibongile\",\"lastName\":\"\",\"phoneNumber\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").doesNotExist());
    }
}

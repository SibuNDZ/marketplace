package com.marketplace.api.vendor;

import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductImage;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.ProductImageRepository;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.TestFixtures;
import com.marketplace.api.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Store social links over HTTP (V36): the owner's decisions D1-D3 in
 * seller-social-links.md. Parsing rules live in SocialLinkNormalizerTest;
 * this covers who may set what, all-or-nothing saves, and what the public
 * sees.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SocialLinksTest {

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

    @Autowired MockMvc                    mockMvc;
    @Autowired JwtService                 jwtService;
    @Autowired UserRepository             userRepository;
    @Autowired ProductImageRepository     imageRepository;
    @Autowired VendorSocialLinkRepository linkRepository;
    @Autowired TestFixtures               fixtures;
    @MockitoBean ObjectStorageService     storage;

    @BeforeEach
    void storageUrls() {
        when(storage.publicUrl(anyString())).thenAnswer(inv -> "https://img.test/" + inv.getArgument(0));
    }

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String bearer(User u) {
        return "Bearer " + jwtService.generateToken(u.getId(), u.getRole().name());
    }

    private User store(String name) {
        User v = fixtures.vendor(uniq("sl"));
        v.setBusinessName(name);
        return userRepository.save(v);
    }

    private ResultActions putLinks(User u, String json) throws Exception {
        return mockMvc.perform(put("/api/v1/account/profile/social-links")
                .header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // ── the vendor's own links ─────────────────────────────────────────────

    @Test
    @DisplayName("pasted links and handles are saved as canonical profile links")
    void setLinks() throws Exception {
        User v = store("Karoo Honey Co");
        putLinks(v, """
                {"instagram":"https://www.instagram.com/Karoo.Honey/?igsh=abc",
                 "tiktok":"@KarooHoney",
                 "facebook":"https://www.facebook.com/profile.php?id=61591684209144",
                 "x":"twitter.com/karoo_honey"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.socialLinks.instagram").value("https://www.instagram.com/karoo.honey"))
                .andExpect(jsonPath("$.socialLinks.tiktok").value("https://www.tiktok.com/@karoohoney"))
                .andExpect(jsonPath("$.socialLinks.facebook")
                        .value("https://www.facebook.com/profile.php?id=61591684209144"))
                .andExpect(jsonPath("$.socialLinks.x").value("https://x.com/karoo_honey"));

        // Only handles are stored, never what was pasted.
        assertThat(linkRepository.findByVendorId(v.getId()))
                .extracting(VendorSocialLink::getHandle)
                .containsExactlyInAnyOrder("karoo.honey", "karoohoney", "61591684209144", "karoo_honey");
    }

    @Test
    @DisplayName("one bad field refuses the whole save, with every mistake keyed to its field")
    void allOrNothing() throws Exception {
        User v = store("Mixed Links");
        putLinks(v, """
                {"instagram":"https://www.instagram.com/p/C1abc/",
                 "tiktok":"@goodhandle",
                 "x":"https://bit.ly/abc"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.instagram[0]").value(org.hamcrest.Matchers.containsString("post")))
                .andExpect(jsonPath("$.errors.x[0]").exists())
                .andExpect(jsonPath("$.errors.tiktok").doesNotExist());

        // The valid TikTok link was NOT half-saved.
        assertThat(linkRepository.findByVendorId(v.getId())).isEmpty();
    }

    @Test
    @DisplayName("absent leaves a link alone, blank clears it, a new value replaces it")
    void partialUpdates() throws Exception {
        User v = store("Changing Links");
        putLinks(v, "{\"instagram\":\"@first\",\"tiktok\":\"@tok\"}").andExpect(status().isOk());

        putLinks(v, "{\"instagram\":\"@second\"}")                 // tiktok absent
                .andExpect(jsonPath("$.socialLinks.instagram").value("https://www.instagram.com/second"))
                .andExpect(jsonPath("$.socialLinks.tiktok").value("https://www.tiktok.com/@tok"));

        putLinks(v, "{\"tiktok\":\"\"}")                            // blank clears
                .andExpect(jsonPath("$.socialLinks.tiktok").doesNotExist())
                .andExpect(jsonPath("$.socialLinks.instagram").value("https://www.instagram.com/second"));

        // Replaced in place, never a second row for the same platform.
        assertThat(linkRepository.findByVendorId(v.getId())).hasSize(1);
    }

    @Test
    @DisplayName("a customer has no store to link")
    void customerForbidden() throws Exception {
        User c = fixtures.customer(uniq("slc"));
        putLinks(c, "{\"instagram\":\"@someone\"}").andExpect(status().isForbidden());
    }

    // ── what the public sees ────────────────────────────────────────────────

    @Test
    @DisplayName("the shop page lists set links in platform order; the spotlight lists none")
    void publicView() throws Exception {
        User v = store(uniq("Public Links"));
        Product p = fixtures.productForVendor(uniq("Item"), uniq("SKU-SL"), new BigDecimal("50.00"), 2, v);
        ProductImage img = new ProductImage();
        img.setProduct(p);
        img.setImageKey("products/" + p.getId() + "/x.jpg");
        img.setPosition(0);
        imageRepository.save(img);
        putLinks(v, "{\"x\":\"@karoo_honey\",\"instagram\":\"@karoo.honey\"}").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/vendors/" + v.getId()))       // signed out
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.socialLinks", hasSize(2)))
                .andExpect(jsonPath("$.socialLinks[0].platform").value("instagram"))
                .andExpect(jsonPath("$.socialLinks[0].url").value("https://www.instagram.com/karoo.honey"))
                .andExpect(jsonPath("$.socialLinks[1].platform").value("x"));

        // The spotlight advertises the store ON eRestyu: no links out.
        String spotlight = mockMvc.perform(get("/api/v1/vendors/spotlight"))
                .andReturn().getResponse().getContentAsString();
        assertThat(spotlight).contains("\"id\":" + v.getId()).doesNotContain("instagram.com");
    }

    // ── admin removal ───────────────────────────────────────────────────────

    @Test
    @DisplayName("an admin can clear a store's links, with a reason")
    void adminClear() throws Exception {
        User v = store("Impersonator");
        putLinks(v, "{\"instagram\":\"@somebodyelse\",\"tiktok\":\"@somebodyelse\"}").andExpect(status().isOk());
        User admin = fixtures.admin(uniq("sla"));

        mockMvc.perform(post("/api/v1/admin/vendors/" + v.getId() + "/social-links/clear")
                        .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
        assertThat(linkRepository.findByVendorId(v.getId())).hasSize(2);

        mockMvc.perform(post("/api/v1/admin/vendors/" + v.getId() + "/social-links/clear")
                        .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Linked an account that is not theirs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(2));
        assertThat(linkRepository.findByVendorId(v.getId())).isEmpty();
    }

    @Test
    @DisplayName("only admins can clear links, and only a store's")
    void adminClearRules() throws Exception {
        User v = store("Protected");
        mockMvc.perform(post("/api/v1/admin/vendors/" + v.getId() + "/social-links/clear")
                        .header("Authorization", bearer(v)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"trying my luck\"}"))
                .andExpect(status().isForbidden());

        User customer = fixtures.customer(uniq("slnc"));
        mockMvc.perform(post("/api/v1/admin/vendors/" + customer.getId() + "/social-links/clear")
                        .header("Authorization", bearer(fixtures.admin(uniq("sla2"))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}

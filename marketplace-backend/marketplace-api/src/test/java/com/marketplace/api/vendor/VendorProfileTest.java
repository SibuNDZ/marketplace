package com.marketplace.api.vendor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vendor store profiles (V35) and the spotlight that shows them.
 *
 * Storage is mocked: these tests are about who may change what and what the
 * public sees, not about R2 (ProductImageServiceTest covers the bucket).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class VendorProfileTest {

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

    @Autowired MockMvc                mockMvc;
    @Autowired JwtService             jwtService;
    @Autowired UserRepository         userRepository;
    @Autowired ProductImageRepository imageRepository;
    @Autowired TestFixtures           fixtures;
    @MockitoBean ObjectStorageService storage;

    private final ObjectMapper json = new ObjectMapper();

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

    private User vendorNamed(String businessName) {
        User v = fixtures.vendor(uniq("pv"));
        v.setBusinessName(businessName);
        return userRepository.save(v);
    }

    /** A live listing with one photo, which is what makes a store spotlight-eligible. */
    private Product listingWithPhoto(User vendor) {
        Product p = fixtures.productForVendor(uniq("Item"), uniq("SKU-PV"), new BigDecimal("100.00"), 3, vendor);
        ProductImage img = new ProductImage();
        img.setProduct(p);
        img.setImageKey("products/" + p.getId() + "/" + UUID.randomUUID() + ".jpg");
        img.setPosition(0);
        imageRepository.save(img);
        return p;
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("file", "me.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
    }

    // ── the vendor's own profile ────────────────────────────────────────────

    @Test
    @DisplayName("a vendor sets their bio, trimmed, and a blank bio clears it")
    void bio() throws Exception {
        User v = vendorNamed("Karoo Honey Co");

        mockMvc.perform(put("/api/v1/account/profile").header("Authorization", bearer(v))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":\"  Raw honey from the Karoo, jarred by hand.  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Karoo Honey Co"))
                .andExpect(jsonPath("$.bio").value("Raw honey from the Karoo, jarred by hand."));

        mockMvc.perform(put("/api/v1/account/profile").header("Authorization", bearer(v))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bio\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").doesNotExist());
    }

    @Test
    @DisplayName("a bio over 500 characters is refused, and nothing is saved")
    void bioTooLong() throws Exception {
        User v = vendorNamed("Long Winded");
        String longBio = "x".repeat(501);

        mockMvc.perform(put("/api/v1/account/profile").header("Authorization", bearer(v))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":\"" + longBio + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findById(v.getId()).orElseThrow().getBio()).isNull();
    }

    @Test
    @DisplayName("a customer has no store profile to edit")
    void customerForbidden() throws Exception {
        User c = fixtures.customer(uniq("pc"));
        mockMvc.perform(get("/api/v1/account/profile").header("Authorization", bearer(c)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/v1/account/profile/avatar").file(png())
                        .header("Authorization", bearer(c)))
                .andExpect(status().isForbidden());
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("a new picture is stored under a fresh key, and the old one is removed")
    void avatarReplace() throws Exception {
        User v = vendorNamed("Picture Co");

        String first = json.readTree(mockMvc.perform(multipart("/api/v1/account/profile/avatar")
                        .file(png()).header("Authorization", bearer(v)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("avatarUrl").asText();
        String firstKey = userRepository.findById(v.getId()).orElseThrow().getAvatarKey();
        assertThat(firstKey).startsWith("vendors/" + v.getId() + "/avatar-").endsWith(".png");
        assertThat(first).isEqualTo("https://img.test/" + firstKey);

        mockMvc.perform(multipart("/api/v1/account/profile/avatar")
                        .file(png()).header("Authorization", bearer(v)))
                .andExpect(status().isOk());
        String secondKey = userRepository.findById(v.getId()).orElseThrow().getAvatarKey();

        // Never reuse a key: the immutable cache headers would serve the old picture.
        assertThat(secondKey).isNotEqualTo(firstKey);
        verify(storage).deleteQuietly(firstKey);
    }

    @Test
    @DisplayName("anything but jpeg, png or webp is refused (SVG can carry scripts)")
    void avatarTypeRefused() throws Exception {
        User v = vendorNamed("Svg Co");
        MockMultipartFile svg = new MockMultipartFile("file", "x.svg", "image/svg+xml", "<svg/>".getBytes());

        mockMvc.perform(multipart("/api/v1/account/profile/avatar").file(svg)
                        .header("Authorization", bearer(v)))
                .andExpect(status().isBadRequest());
        verify(storage, never()).put(startsWith("vendors/"), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("removing the picture clears it and deletes the object")
    void avatarRemove() throws Exception {
        User v = vendorNamed("Remove Co");
        mockMvc.perform(multipart("/api/v1/account/profile/avatar").file(png())
                .header("Authorization", bearer(v))).andExpect(status().isOk());
        String key = userRepository.findById(v.getId()).orElseThrow().getAvatarKey();

        mockMvc.perform(delete("/api/v1/account/profile/avatar").header("Authorization", bearer(v)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());

        assertThat(userRepository.findById(v.getId()).orElseThrow().getAvatarKey()).isNull();
        verify(storage).deleteQuietly(eq(key));
    }

    // ── what the public sees ────────────────────────────────────────────────

    @Test
    @DisplayName("a store's profile is public, with live figures and no private fields")
    void publicProfile() throws Exception {
        User v = vendorNamed("Open Stall");
        v.setBio("We make candles.");
        userRepository.save(v);
        listingWithPhoto(v);
        listingWithPhoto(v);

        String body = mockMvc.perform(get("/api/v1/vendors/" + v.getId()))   // no token
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Open Stall"))
                .andExpect(jsonPath("$.bio").value("We make candles."))
                .andExpect(jsonPath("$.pieces").value(2))
                .andExpect(jsonPath("$.sold").value(0))
                .andExpect(jsonPath("$.rating").doesNotExist())
                .andExpect(jsonPath("$.sampleImageUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // A shop front, not an account: nothing that identifies the person.
        assertThat(body).doesNotContain(v.getEmail()).doesNotContain("firstName")
                .doesNotContain("accountNumber").doesNotContain("phone");
    }

    @Test
    @DisplayName("a customer's id is not a store")
    void customerIsNotAStore() throws Exception {
        User c = fixtures.customer(uniq("notstore"));
        mockMvc.perform(get("/api/v1/vendors/" + c.getId())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the spotlight rotates through real stores with photos, completed profiles first")
    void spotlight() throws Exception {
        User complete = vendorNamed(uniq("Complete Store"));
        listingWithPhoto(complete);
        mockMvc.perform(put("/api/v1/account/profile").header("Authorization", bearer(complete))
                .contentType(MediaType.APPLICATION_JSON).content("{\"bio\":\"All about us.\"}"));
        mockMvc.perform(multipart("/api/v1/account/profile/avatar").file(png())
                .header("Authorization", bearer(complete)));

        User plain = vendorNamed(uniq("Plain Store"));        // newer, but no profile
        listingWithPhoto(plain);

        User noPhotos = vendorNamed(uniq("No Photos"));
        fixtures.productForVendor(uniq("Bare"), uniq("SKU-NP"), new BigDecimal("10.00"), 1, noPhotos);
        User noListings = vendorNamed(uniq("Empty Stall"));
        User fixture = vendorNamed("Fixture Vendor");
        listingWithPhoto(fixture);

        JsonNode list = json.readTree(mockMvc.perform(get("/api/v1/vendors/spotlight"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        list.forEach(n -> ids.add(n.get("id").asLong()));

        assertThat(ids).contains(complete.getId(), plain.getId())
                .doesNotContain(noPhotos.getId(), noListings.getId(), fixture.getId());
        // Completed profiles lead, even though "plain" registered later.
        assertThat(ids.indexOf(complete.getId())).isLessThan(ids.indexOf(plain.getId()));
    }
}

package com.marketplace.api.storage;

import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.security.UserPrincipal;
import com.marketplace.api.service.TestFixtures;
import com.marketplace.api.storage.ProductImageService.UnsupportedImageTypeException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real S3-protocol round-trip against a MinIO testcontainer — R2Config's
 * endpoint-override property exists solely for this. Two containers this
 * time (Postgres, house pattern, plus MinIO); each gets its own
 * @DynamicPropertySource block for clarity.
 */
@Testcontainers
@SpringBootTest
class ProductImageServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MINIO_ACCESS_KEY = "test-access-key";
    private static final String MINIO_SECRET_KEY = "test-secret-key";
    private static final String TEST_BUCKET = "erestyu-images-test";

    @Container
    static GenericContainer<?> minio = new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
            .withCommand("server", "/data")
            .waitingFor(Wait.forLogMessage(".*API:.*\\n", 1));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");

        // R2Config's endpoint-override — the whole reason that property exists.
        registry.add("app.storage.r2.account-id", () -> "unused-when-endpoint-overridden");
        registry.add("app.storage.r2.access-key-id", () -> MINIO_ACCESS_KEY);
        registry.add("app.storage.r2.secret-access-key", () -> MINIO_SECRET_KEY);
        registry.add("app.storage.r2.bucket", () -> TEST_BUCKET);
        registry.add("app.storage.r2.public-base-url", () -> "https://images.erestyu.com");
        registry.add("app.storage.r2.endpoint-override",
                () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
    }

    @Autowired ProductImageService productImageService;
    @Autowired ObjectStorageService storage;
    @Autowired S3Client s3;
    @Autowired TestFixtures fixtures;

    /** MinIO doesn't auto-create buckets — do it once before any upload test runs. */
    @BeforeAll
    static void ensureBucket(@Autowired S3Client s3) {
        try {
            s3.headBucket(b -> b.bucket(TEST_BUCKET));
        } catch (Exception notFound) {
            s3.createBucket(CreateBucketRequest.builder().bucket(TEST_BUCKET).build());
        }
    }

    private MockMultipartFile pngFile(String content) {
        return new MockMultipartFile("file", "photo.png", "image/png", content.getBytes());
    }

    @Test
    void upload_roundTrip() {
        Product product = fixtures.product("Image Test A", "SKU-IMG-A1", new BigDecimal("10"), 5);
        User vendor = fixtures.vendor("img-vendor1");
        // fixtures.product always assigns the shared test vendor — reassign
        // ownership so THIS vendor can legitimately upload.
        reassignVendor(product.getId(), vendor.getId());

        String url = productImageService.upload(product.getId(), pngFile("fake-png-bytes"),
                UserPrincipal.from(vendor));

        assertThat(url).startsWith("https://images.erestyu.com/products/" + product.getId() + "/");
        assertThat(url).endsWith(".png");

        // Object genuinely exists in MinIO, not just a URL string.
        String key = url.substring("https://images.erestyu.com/".length());
        assertThat(s3.headObject(b -> b.bucket(TEST_BUCKET).key(key)).contentType()).isEqualTo("image/png");
    }

    @Test
    void upload_replacesOldKey() {
        Product product = fixtures.product("Image Test B", "SKU-IMG-B1", new BigDecimal("10"), 5);
        User vendor = fixtures.vendor("img-vendor2");
        reassignVendor(product.getId(), vendor.getId());

        String firstUrl = productImageService.upload(product.getId(), pngFile("first"), UserPrincipal.from(vendor));
        String firstKey = firstUrl.substring("https://images.erestyu.com/".length());

        String secondUrl = productImageService.upload(product.getId(), pngFile("second"), UserPrincipal.from(vendor));
        String secondKey = secondUrl.substring("https://images.erestyu.com/".length());

        assertThat(secondKey).isNotEqualTo(firstKey);
        // Old object deleted (best-effort deleteQuietly) — headObject on a
        // gone key throws; NoSuchKeyException confirms it's actually gone,
        // not just that the call didn't throw for some other reason.
        assertThatThrownBy(() -> s3.headObject(b -> b.bucket(TEST_BUCKET).key(firstKey)))
                .isInstanceOfAny(NoSuchKeyException.class, software.amazon.awssdk.services.s3.model.S3Exception.class);
    }

    @Test
    void upload_strangerVendor_403() {
        Product product = fixtures.product("Image Test C", "SKU-IMG-C1", new BigDecimal("10"), 5);
        User owner = fixtures.vendor("img-vendor3-owner");
        User stranger = fixtures.vendor("img-vendor3-stranger");
        reassignVendor(product.getId(), owner.getId());

        assertThatThrownBy(() ->
                productImageService.upload(product.getId(), pngFile("x"), UserPrincipal.from(stranger)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void upload_unsupportedType_throws() {
        Product product = fixtures.product("Image Test D", "SKU-IMG-D1", new BigDecimal("10"), 5);
        User vendor = fixtures.vendor("img-vendor4");
        reassignVendor(product.getId(), vendor.getId());

        MockMultipartFile gif = new MockMultipartFile("file", "photo.gif", "image/gif", "x".getBytes());

        assertThatThrownBy(() ->
                productImageService.upload(product.getId(), gif, UserPrincipal.from(vendor)))
                .isInstanceOf(UnsupportedImageTypeException.class);
    }

    @Test
    void upload_oversize_throws() {
        Product product = fixtures.product("Image Test E", "SKU-IMG-E1", new BigDecimal("10"), 5);
        User vendor = fixtures.vendor("img-vendor5");
        reassignVendor(product.getId(), vendor.getId());

        byte[] tooBig = new byte[6 * 1024 * 1024]; // over the 5MB cap
        MockMultipartFile big = new MockMultipartFile("file", "photo.png", "image/png", tooBig);

        assertThatThrownBy(() ->
                productImageService.upload(product.getId(), big, UserPrincipal.from(vendor)))
                .isInstanceOf(UnsupportedImageTypeException.class);
    }

    // fixtures.product() always attaches the one shared test vendor
    // (test-vendor@test.local) — these tests need a SPECIFIC vendor to
    // exercise ownership, so reassign directly via the autowired repositories.
    // Plain method, no @Transactional: self-invocation from a @Test method
    // bypasses Spring's AOP proxy, so the annotation would be silently inert
    // anyway — saveAndFlush opens its own transaction regardless.
    @Autowired com.marketplace.api.repository.ProductRepository productRepository;
    @Autowired com.marketplace.api.repository.UserRepository userRepository;

    void reassignVendor(Long productId, Long vendorId) {
        Product p = productRepository.findById(productId).orElseThrow();
        p.setVendor(userRepository.getReferenceById(vendorId));
        productRepository.saveAndFlush(p);
    }
}

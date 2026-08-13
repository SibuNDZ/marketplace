package com.marketplace.api.storage;

import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.ProductImage;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.ProductImageRepository;
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
import java.util.List;

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
    @Autowired ProductImageRepository imageRepository;
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

    /**
     * Was upload_replacesOldKey. A second upload used to REPLACE the product's
     * one photo and delete the old object; since V24 a product has a gallery
     * and an upload appends to it. The old assertion — that the first object
     * is gone — would now be asserting data loss.
     */
    @Test
    void upload_appendsToGallery() {
        Product product = fixtures.product("Image Test B", "SKU-IMG-B1", new BigDecimal("10"), 5);
        User vendor = fixtures.vendor("img-vendor2");
        reassignVendor(product.getId(), vendor.getId());

        String firstUrl = productImageService.upload(product.getId(), pngFile("first"), UserPrincipal.from(vendor));
        String firstKey = firstUrl.substring("https://images.erestyu.com/".length());

        String secondUrl = productImageService.upload(product.getId(), pngFile("second"), UserPrincipal.from(vendor));
        String secondKey = secondUrl.substring("https://images.erestyu.com/".length());

        assertThat(secondKey).isNotEqualTo(firstKey);

        // BOTH objects survive now. The first one still being there is the
        // whole point of the change.
        assertThat(s3.headObject(b -> b.bucket(TEST_BUCKET).key(firstKey)).contentType())
                .isEqualTo("image/png");
        assertThat(s3.headObject(b -> b.bucket(TEST_BUCKET).key(secondKey)).contentType())
                .isEqualTo("image/png");

        // Ordered, and appended rather than prepended: the first upload stays
        // the cover, so adding a photo never silently changes what a card shows.
        List<ProductImage> gallery = imageRepository.findByProductIdOrderByPositionAscIdAsc(product.getId());
        assertThat(gallery).hasSize(2);
        assertThat(gallery.get(0).getImageKey()).isEqualTo(firstKey);
        assertThat(gallery.get(0).getPosition()).isZero();
        assertThat(gallery.get(1).getPosition()).isEqualTo(1);
    }

    @Test
    void delete_removesObjectAndClosesTheGap() {
        Product product = fixtures.product("Image Test D", "SKU-IMG-D-" + java.util.UUID.randomUUID(), new BigDecimal("10"), 5);
        User vendor = fixtures.vendor("img-vendor-del-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        reassignVendor(product.getId(), vendor.getId());

        productImageService.upload(product.getId(), pngFile("one"), UserPrincipal.from(vendor));
        productImageService.upload(product.getId(), pngFile("two"), UserPrincipal.from(vendor));
        productImageService.upload(product.getId(), pngFile("three"), UserPrincipal.from(vendor));

        List<ProductImage> before = imageRepository.findByProductIdOrderByPositionAscIdAsc(product.getId());
        String removedKey = before.get(0).getImageKey();

        productImageService.delete(product.getId(), before.get(0).getId(), UserPrincipal.from(vendor));

        // Renumbered from zero, so positions never drift away from the count.
        List<ProductImage> after = imageRepository.findByProductIdOrderByPositionAscIdAsc(product.getId());
        assertThat(after).hasSize(2);
        assertThat(after).extracting(ProductImage::getPosition).containsExactly(0, 1);
        assertThat(after.get(0).getImageKey()).isEqualTo(before.get(1).getImageKey());

        assertThatThrownBy(() -> s3.headObject(b -> b.bucket(TEST_BUCKET).key(removedKey)))
                .isInstanceOfAny(NoSuchKeyException.class, software.amazon.awssdk.services.s3.model.S3Exception.class);
    }

    @Test
    void delete_strangerVendor_403() {
        Product product = fixtures.product("Image Test E", "SKU-IMG-E-" + java.util.UUID.randomUUID(), new BigDecimal("10"), 5);
        User owner = fixtures.vendor("img-vendor-e-owner-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        User stranger = fixtures.vendor("img-vendor-e-stranger-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        reassignVendor(product.getId(), owner.getId());
        productImageService.upload(product.getId(), pngFile("x"), UserPrincipal.from(owner));
        Long imageId = imageRepository.findByProductIdOrderByPositionAscIdAsc(product.getId()).get(0).getId();

        // Deleting someone else's photo is the same ownership question as
        // uploading to their product, and must answer it the same way.
        assertThatThrownBy(() ->
                productImageService.delete(product.getId(), imageId, UserPrincipal.from(stranger)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void upload_pastTheCap_rejected() {
        Product product = fixtures.product("Image Test F", "SKU-IMG-F-" + java.util.UUID.randomUUID(), new BigDecimal("10"), 5);
        User vendor = fixtures.vendor("img-vendor-cap-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        reassignVendor(product.getId(), vendor.getId());

        for (int i = 0; i < ProductImageService.MAX_IMAGES; i++) {
            productImageService.upload(product.getId(), pngFile("f" + i), UserPrincipal.from(vendor));
        }

        assertThatThrownBy(() ->
                productImageService.upload(product.getId(), pngFile("overflow"), UserPrincipal.from(vendor)))
                .isInstanceOf(ProductImageService.TooManyImagesException.class);
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

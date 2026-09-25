package com.marketplace.api.web;

import com.marketplace.api.entity.Product;
import com.marketplace.api.entity.User;
import com.marketplace.api.security.JwtService;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.FormContentFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;

import static com.marketplace.api.web.RequestBodyLimitFilter.DEFAULT_MAX_BYTES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app-wide body cap through a real Tomcat, which MockMvc would bypass
 * (it always reports a Content-Length and never runs container parsing).
 * Payment callbacks have their own, tighter rule, pinned in
 * WebhookBodyLimitTest.
 *
 * The oversized JSON and form cases use 1 MB, far past any cap this filter
 * might be tuned to, so they pin "bounded" rather than one particular number.
 * The exact boundaries live in RequestBodyLimitFilterTest. The limits come
 * from test/resources/application.yml, which BodyLimitConfigTest keeps in
 * step with production.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestBodyLimitTest {

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

    private static final int ONE_MB = 1024 * 1024;

    @LocalServerPort int                port;
    @Autowired       TestFixtures       fixtures;
    @Autowired       JwtService         jwtService;
    @Autowired       ApplicationContext context;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // --- the holes ---------------------------------------------------------

    @Test
    void formBodyOnAPut_isRejectedBeforeAuthentication() throws Exception {
        // Before this change FormContentFilter read this body whole, ahead of
        // Spring Security, and only then did the 401 go out. What this pins
        // is the cap answering first on a PUT to an authenticated path;
        // FormContentFilter being gone is pinned separately below and in
        // BodyLimitConfigTest.
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/cart/items/1"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(publisher("q=" + "x".repeat(ONE_MB), false))
                .build();

        assertThat(send(request).statusCode()).isEqualTo(413);
    }

    @Test
    void oversizedLoginBody_413() throws Exception {
        String body = "{\"email\":\"someone@example.com\",\"password\":\"" + "x".repeat(ONE_MB) + "\"}";

        assertThat(send(json("/api/v1/auth/login", body, null, false)).statusCode()).isEqualTo(413);
    }

    @Test
    void oversizedChunkedJson_fromASignedInUser_413() throws Exception {
        // Any account holder can sign in, so authentication is no size limit.
        // Before the cap this padded body was accepted and the item added.
        User buyer = fixtures.customer("rbl-big-buyer");
        Product product = fixtures.product("RBL-BIG", "SKU-RBL-BIG", new BigDecimal("20.00"), 5);
        String body = addItem(product) + " ".repeat(ONE_MB);

        HttpResponse<String> response = send(json("/api/v1/cart/items", body, tokenFor(buyer), true));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"status\":413");
    }

    @Test
    void formContentFilter_isNotRegistered() {
        // Nothing in this JSON API sends form bodies on PUT/PATCH/DELETE (the
        // cart's variantId travels in the query string), so the filter was
        // only ever a pre-authentication parser for attackers to feed.
        assertThat(context.getBeansOfType(FormContentFilter.class)).isEmpty();
    }

    // --- what must keep working --------------------------------------------

    @Test
    void chunkedJsonUnderTheCap_fromASignedInUser_stillWorks() throws Exception {
        User buyer = fixtures.customer("rbl-small-buyer");
        Product product = fixtures.product("RBL-SMALL", "SKU-RBL-SMALL", new BigDecimal("20.00"), 5);

        HttpResponse<String> response =
                send(json("/api/v1/cart/items", addItem(product), tokenFor(buyer), true));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"productId\":" + product.getId());
    }

    @Test
    void multipartUploadOverTheJsonCap_isNotAnsweredByTheFilter() throws Exception {
        // A 1 MB image is a normal upload, so the filter must step aside for
        // multipart. Reaching Spring Security's 401 rather than a 413 is the
        // proof. The part itself is never parsed here: security answers
        // before DispatcherServlet gets to it.
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/products/1/image"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(BodyPublishers.ofByteArray(multipart(
                        "Content-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\n"
                                + "Content-Type: image/jpeg", new byte[ONE_MB])))
                .build();

        assertThat(send(request).statusCode()).isEqualTo(401);
    }

    @Test
    void multipartFormFieldOverTheFormPostLimit_isRefused() throws Exception {
        // The flip side of that exemption: Tomcat keeps a multipart part with
        // no filename in memory as a String, bounded only by
        // max-http-form-post-size. At Boot's 2 MB default this anonymous
        // request to a permitAll path was parsed whole before the controller
        // turned it away; at 256 KB Tomcat refuses it while parsing.
        byte[] field = "x".repeat(DEFAULT_MAX_BYTES + 1).getBytes(StandardCharsets.US_ASCII);
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/newsletter/subscribe"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(BodyPublishers.ofByteArray(multipart(
                        "Content-Disposition: form-data; name=\"email\"", field)))
                .build();

        HttpResponse<String> response = send(request);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("too large");
    }

    // --- helpers -----------------------------------------------------------

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String tokenFor(User user) {
        return jwtService.generateToken(user.getId(), user.getRole().name());
    }

    private static final String BOUNDARY = "rbl-boundary";

    /** One-part multipart/form-data body with the given part headers and content. */
    private static byte[] multipart(String partHeaders, byte[] content) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\n" + partHeaders + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    private static String addItem(Product product) {
        return "{\"productId\":" + product.getId() + ",\"quantity\":1}";
    }

    private HttpRequest json(String path, String body, String token, boolean chunked) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(publisher(body, chunked));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, BodyHandlers.ofString());
    }

    /** ofInputStream has no length to declare, so the JDK client sends it chunked. */
    private static BodyPublisher publisher(String body, boolean chunked) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return chunked
                ? BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
                : BodyPublishers.ofByteArray(bytes);
    }
}

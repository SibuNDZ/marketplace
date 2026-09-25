package com.marketplace.api.web;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static com.marketplace.api.web.RequestBodyLimitFilter.DEFAULT_MAX_BYTES;
import static com.marketplace.api.web.RequestBodyLimitFilter.PAYMENT_CALLBACK_MAX_BYTES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter on its own, fed requests whose bodies are live streams the way
 * Tomcat presents them. MockMvc cannot stand in here: a MockHttpServletRequest
 * always reports the length of its content, so the chunked path would never
 * run. RequestBodyLimitTest and WebhookBodyLimitTest cover the same rules
 * through a real Tomcat.
 */
class RequestBodyLimitFilterTest {

    private static final String YOCO  = "/api/v1/payments/yoco/webhook";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final long   TEN_MB = 10L * 1024 * 1024;

    private static final String FRONTEND = "http://localhost:5173";

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(new CorsOrigins(FRONTEND), 10_000);

    // --- payment callbacks: 64 KB, any content type ------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/payments/stripe/webhook",
            "/api/v1/payments/yoco/webhook",
            "/api/v1/payments/payfast/itn",
            // Not on main yet. Covered anyway because the scope is the prefix,
            // which is the point: no provider has to remember to opt in.
            "/api/v1/payments/paystack/webhook"})
    void paymentCallbackOverCap_413_beforeTheChain_withoutReadingTheBody(String path) throws Exception {
        GeneratedBody body = new GeneratedBody(PAYMENT_CALLBACK_MAX_BYTES + 1);
        Outcome outcome = run(streamed("POST", path, body, PAYMENT_CALLBACK_MAX_BYTES + 1));

        assertThat(outcome.response.getStatus()).isEqualTo(413);
        assertThat(outcome.response.getContentType()).isEqualTo("application/problem+json");
        assertThat(outcome.chain.getRequest()).isNull();
        assertThat(body.served).isZero();
    }

    @Test
    void paymentCallbackAtCap_passesTheOriginalRequestThrough() throws Exception {
        GeneratedBody body = new GeneratedBody(PAYMENT_CALLBACK_MAX_BYTES);
        MockHttpServletRequest request = streamed("POST", YOCO, body, PAYMENT_CALLBACK_MAX_BYTES);
        Outcome outcome = run(request);

        // Same instance, nothing read: Tomcat already stops at the declared
        // length, so the filter adds no copy on the path every real delivery takes.
        assertThat(outcome.chain.getRequest()).isSameAs(request);
        assertThat(body.served).isZero();
    }

    @Test
    void multipartToAPaymentCallback_isStillHeldTo64Kb() throws Exception {
        // The multipart exemption must not become a way around the tighter
        // rule: the prefix is checked first.
        MockHttpServletRequest request = streamed("POST", YOCO,
                new GeneratedBody(TEN_MB), PAYMENT_CALLBACK_MAX_BYTES + 1);
        request.setContentType("multipart/form-data; boundary=x");

        assertThat(run(request).response.getStatus()).isEqualTo(413);
    }

    @Test
    void percentEncodedPaymentPath_isStillHeldTo64Kb() throws Exception {
        // Spring decodes path segments before matching, so this reaches the
        // Stripe controller. Matching the raw URI would have missed it.
        MockHttpServletRequest request = streamed("POST", "/api/v1/%70ayments/stripe/webhook",
                new GeneratedBody(TEN_MB), PAYMENT_CALLBACK_MAX_BYTES + 1);

        assertThat(run(request).response.getStatus()).isEqualTo(413);
    }

    @Test
    void forwardedPrefix_doesNotHideAPaymentPath() throws Exception {
        // In prod ForwardedHeaderFilter runs first and turns a caller's
        // X-Forwarded-Prefix into a context path, so getRequestURI() becomes
        // /anything/api/v1/payments/... while routing still strips it.
        MockHttpServletRequest request = streamed("POST", "/anything" + YOCO,
                new GeneratedBody(TEN_MB), PAYMENT_CALLBACK_MAX_BYTES + 1);
        request.setContextPath("/anything");

        assertThat(run(request).response.getStatus()).isEqualTo(413);
    }

    @Test
    void chunkedPaymentCallbackOverCap_isCutOffAtTheCap() throws Exception {
        // 16 MB on offer, generated on demand so the test itself stays small.
        GeneratedBody body = new GeneratedBody(16L * 1024 * 1024);
        Outcome outcome = run(streamed("POST", YOCO, body, -1));

        assertThat(outcome.response.getStatus()).isEqualTo(413);
        assertThat(outcome.chain.getRequest()).isNull();
        assertThat(body.served).isEqualTo(PAYMENT_CALLBACK_MAX_BYTES + 1);
    }

    // --- everything else: 256 KB -------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void otherEndpointsOverTheDefaultCap_413(String method) throws Exception {
        GeneratedBody body = new GeneratedBody(DEFAULT_MAX_BYTES + 1);
        Outcome outcome = run(streamed(method, "/api/v1/cart/items/1", body, DEFAULT_MAX_BYTES + 1));

        assertThat(outcome.response.getStatus()).isEqualTo(413);
        assertThat(outcome.chain.getRequest()).isNull();
        assertThat(body.served).isZero();
    }

    @Test
    void the413_isReadableByTheFrontend_butNotByOtherOrigins() throws Exception {
        MockHttpServletRequest fromFrontend = streamed("PUT", "/api/v1/products/1",
                new GeneratedBody(TEN_MB), DEFAULT_MAX_BYTES + 1);
        fromFrontend.addHeader("Origin", FRONTEND);
        MockHttpServletResponse allowed = run(fromFrontend).response;

        assertThat(allowed.getStatus()).isEqualTo(413);
        assertThat(allowed.getHeader("Access-Control-Allow-Origin")).isEqualTo(FRONTEND);
        assertThat(allowed.getHeader("Access-Control-Expose-Headers")).contains("X-Request-Id");

        MockHttpServletRequest fromElsewhere = streamed("PUT", "/api/v1/products/1",
                new GeneratedBody(TEN_MB), DEFAULT_MAX_BYTES + 1);
        fromElsewhere.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse refused = run(fromElsewhere).response;

        assertThat(refused.getStatus()).isEqualTo(413);
        assertThat(refused.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void otherEndpointsAtTheDefaultCap_passThrough() throws Exception {
        MockHttpServletRequest request = streamed("POST", LOGIN,
                new GeneratedBody(DEFAULT_MAX_BYTES), DEFAULT_MAX_BYTES);

        assertThat(run(request).chain.getRequest()).isSameAs(request);
    }

    @Test
    void chunkedBodyOverTheDefaultCap_isCutOffAtTheCap() throws Exception {
        GeneratedBody body = new GeneratedBody(16L * 1024 * 1024);
        Outcome outcome = run(streamed("POST", LOGIN, body, -1));

        assertThat(outcome.response.getStatus()).isEqualTo(413);
        assertThat(body.served).isEqualTo(DEFAULT_MAX_BYTES + 1);
    }

    @Test
    void multipartOutsidePayments_isLeftToTheMultipartLimits() throws Exception {
        GeneratedBody body = new GeneratedBody(TEN_MB);
        MockHttpServletRequest request = streamed("POST", "/api/v1/products/1/image", body, TEN_MB);
        request.setContentType("Multipart/Form-Data; boundary=x");
        Outcome outcome = run(request);

        assertThat(outcome.chain.getRequest()).isSameAs(request);
        assertThat(body.served).isZero();
    }

    @Test
    void noLengthAndNoTransferEncoding_meansNoBody_andIsNotWrapped() throws Exception {
        // Every plain GET. HTTP/1.1 frames a request body only by one of the
        // two headers, so there is nothing to read and nothing to replay.
        GeneratedBody body = new GeneratedBody(TEN_MB);
        MockHttpServletRequest request = streamed("GET", "/api/v1/products", body, -1);
        request.removeHeader("Transfer-Encoding");
        Outcome outcome = run(request);

        assertThat(outcome.chain.getRequest()).isSameAs(request);
        assertThat(body.served).isZero();
    }

    // --- the replay --------------------------------------------------------

    @Test
    void chunkedBodyAtCap_isReplayedByteForByte() throws Exception {
        byte[] payload = new byte[PAYMENT_CALLBACK_MAX_BYTES];
        Arrays.fill(payload, (byte) ' ');
        byte[] json = "{\"type\":\"payment.succeeded\"}".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(json, 0, payload, 0, json.length);

        Outcome outcome = run(streamed("POST", YOCO, new ByteArrayInputStream(payload), -1));

        ServletRequest downstream = outcome.chain.getRequest();
        assertThat(downstream).isNotNull();
        assertThat(downstream.getContentLengthLong()).isEqualTo(PAYMENT_CALLBACK_MAX_BYTES);
        assertThat(downstream.getInputStream().readAllBytes()).isEqualTo(payload);
    }

    @Test
    void chunkedFormPost_parametersComeFromTheReplayedBody_inWireOrder() throws Exception {
        String form = "pf_payment_id=1089250&m_payment_id=42&item_name=eRestyu+order"
                + "&custom_str1=&note=a%26b&m_payment_id=43&bad=%zz&signature=abc";
        MockHttpServletRequest request = streamed("POST", "/api/v1/payments/payfast/itn",
                new ByteArrayInputStream(form.getBytes(StandardCharsets.UTF_8)), -1);
        request.setContentType("application/x-www-form-urlencoded; charset=UTF-8");
        // Tomcat has already parsed the query string by the time the filter
        // runs; the mock needs telling.
        request.setQueryString("source=itn");
        request.addParameter("source", "itn");

        ServletRequest downstream = run(request).chain.getRequest();

        assertThat(downstream.getParameterMap().keySet()).containsExactly(
                "source", "pf_payment_id", "m_payment_id", "item_name",
                "custom_str1", "note", "signature");
        assertThat(downstream.getParameterValues("m_payment_id")).containsExactly("42", "43");
        assertThat(downstream.getParameter("item_name")).isEqualTo("eRestyu order");
        assertThat(downstream.getParameter("custom_str1")).isEmpty();
        assertThat(downstream.getParameter("note")).isEqualTo("a&b");
        assertThat(downstream.getParameter("bad")).isNull();
        assertThat(Collections.list(downstream.getParameterNames())).hasSize(7);
    }

    @Test
    void chunkedFormPost_parsesNoMoreParametersThanTomcatWould() throws Exception {
        // Tomcat stops at server.tomcat.max-parameter-count and keeps what it
        // has; the replay must not be the one parser without a ceiling. The
        // query string counts toward the limit, as it does in Tomcat.
        StringBuilder form = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            form.append(i == 0 ? "" : "&").append("p").append(i).append("=v");
        }
        MockHttpServletRequest request = streamed("POST", "/api/v1/newsletter/subscribe",
                new ByteArrayInputStream(form.toString().getBytes(StandardCharsets.UTF_8)), -1);
        request.setContentType("application/x-www-form-urlencoded");
        request.setQueryString("q=1");
        request.addParameter("q", "1");

        RequestBodyLimitFilter capped = new RequestBodyLimitFilter(new CorsOrigins(FRONTEND), 10);
        MockFilterChain chain = new MockFilterChain();
        capped.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest().getParameterMap().keySet()).containsExactly(
                "q", "p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8");
    }

    // --- helpers -----------------------------------------------------------

    private record Outcome(MockHttpServletResponse response, MockFilterChain chain) {}

    private Outcome run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain);
    }

    /**
     * A request whose body is a live stream. A negative length means the body
     * arrived chunked, framed by Transfer-Encoding as Tomcat would see it.
     */
    private static MockHttpServletRequest streamed(String method, String path,
                                                   InputStream source, long declaredLength) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path) {
            @Override
            public long getContentLengthLong() {
                return declaredLength;
            }

            @Override
            public int getContentLength() {
                return declaredLength > Integer.MAX_VALUE ? -1 : (int) declaredLength;
            }

            @Override
            public ServletInputStream getInputStream() {
                return new DelegatingServletInputStream(source);
            }
        };
        request.setContentType("application/json");
        if (declaredLength < 0) {
            request.addHeader("Transfer-Encoding", "chunked");
        }
        return request;
    }

    /** A body of the given size that is produced as it is read and counts what was taken. */
    private static final class GeneratedBody extends InputStream {
        private final long size;
        long served;

        GeneratedBody(long size) {
            this.size = size;
        }

        @Override
        public int read() {
            if (served >= size) return -1;
            served++;
            return 'x';
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (served >= size) return -1;
            int n = (int) Math.min(len, size - served);
            Arrays.fill(b, off, off + n, (byte) 'x');
            served += n;
            return n;
        }
    }
}

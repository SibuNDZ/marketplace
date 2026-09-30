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

import static com.marketplace.api.web.WebhookBodyLimitFilter.MAX_BODY_BYTES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter on its own, fed requests whose bodies are live streams the way
 * Tomcat presents them. MockMvc cannot stand in here: a MockHttpServletRequest
 * always reports the length of its content, so the chunked path would never
 * run. WebhookBodyLimitTest covers the same rules through a real Tomcat.
 */
class WebhookBodyLimitFilterTest {

    private static final String YOCO = "/api/v1/payments/yoco/webhook";

    private final WebhookBodyLimitFilter filter = new WebhookBodyLimitFilter();

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/payments/stripe/webhook",
            "/api/v1/payments/yoco/webhook",
            "/api/v1/payments/payfast/itn",
            // Not on main yet. Covered anyway because the scope is the prefix,
            // which is the point: no provider has to remember to opt in.
            "/api/v1/payments/paystack/webhook"})
    void declaredLengthOverCap_413_beforeTheChain_withoutReadingTheBody(String path) throws Exception {
        GeneratedBody body = new GeneratedBody(MAX_BODY_BYTES + 1);
        Outcome outcome = run(streamed("POST", path, body, MAX_BODY_BYTES + 1));

        assertThat(outcome.response.getStatus()).isEqualTo(413);
        assertThat(outcome.response.getContentType()).isEqualTo("application/problem+json");
        assertThat(outcome.chain.getRequest()).isNull();
        assertThat(body.served).isZero();
    }

    @Test
    void declaredLengthAtCap_passesTheOriginalRequestThrough() throws Exception {
        GeneratedBody body = new GeneratedBody(MAX_BODY_BYTES);
        MockHttpServletRequest request = streamed("POST", YOCO, body, MAX_BODY_BYTES);
        Outcome outcome = run(request);

        // Same instance, nothing read: Tomcat already stops at the declared
        // length, so the filter adds no copy on the path every real delivery takes.
        assertThat(outcome.chain.getRequest()).isSameAs(request);
        assertThat(body.served).isZero();
    }

    @Test
    void chunkedBodyOverCap_isCutOffAtTheCap() throws Exception {
        // 16 MB on offer, generated on demand so the test itself stays small.
        GeneratedBody body = new GeneratedBody(16L * 1024 * 1024);
        Outcome outcome = run(streamed("POST", YOCO, body, -1));

        assertThat(outcome.response.getStatus()).isEqualTo(413);
        assertThat(outcome.chain.getRequest()).isNull();
        assertThat(body.served).isEqualTo(MAX_BODY_BYTES + 1);
    }

    @Test
    void chunkedBodyAtCap_isReplayedByteForByte() throws Exception {
        byte[] payload = new byte[MAX_BODY_BYTES];
        Arrays.fill(payload, (byte) ' ');
        byte[] json = "{\"type\":\"payment.succeeded\"}".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(json, 0, payload, 0, json.length);

        Outcome outcome = run(streamed("POST", YOCO, new ByteArrayInputStream(payload), -1));

        ServletRequest downstream = outcome.chain.getRequest();
        assertThat(downstream).isNotNull();
        assertThat(downstream.getContentLengthLong()).isEqualTo(MAX_BODY_BYTES);
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
    void pathsOutsideThePaymentsPrefix_areNotCapped() throws Exception {
        GeneratedBody body = new GeneratedBody(10L * 1024 * 1024);
        MockHttpServletRequest request = streamed("POST", "/api/v1/products", body, 10L * 1024 * 1024);
        Outcome outcome = run(request);

        assertThat(outcome.chain.getRequest()).isSameAs(request);
        assertThat(outcome.response.getStatus()).isEqualTo(200);
    }

    // --- helpers -----------------------------------------------------------

    private record Outcome(MockHttpServletResponse response, MockFilterChain chain) {}

    private Outcome run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain);
    }

    /** A request whose body is a live stream and whose length is whatever the header said. */
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

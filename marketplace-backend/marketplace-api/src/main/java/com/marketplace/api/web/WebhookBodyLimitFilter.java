package com.marketplace.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Caps the request body on the payment provider callbacks: the Stripe and
 * Yoco webhooks, the PayFast ITN, and whatever provider comes next.
 *
 * Those endpoints are permitAll, so no JWT stands in front of them, and each
 * controller takes the body as @RequestBody String and checks the signature
 * only once the whole body is in memory. Nothing else bounds that body:
 * spring.servlet.multipart covers uploads and Tomcat's form-post limit covers
 * its own parameter parsing, not JSON. A few concurrent multi-hundred-MB
 * POSTs could exhaust the heap of the single Railway instance. Real
 * provider events are a few KB, so 64 KB leaves generous headroom without
 * being worth a config knob.
 *
 * The size is not always known up front, hence two paths:
 *  - Content-Length present: over the cap is a 413 before a byte is read.
 *    Under it, the request goes through untouched, because Tomcat never
 *    reads past the declared length.
 *  - No Content-Length (chunked): read at most cap + 1 bytes here and 413 if
 *    that extra byte shows up; otherwise the chain gets a request replaying
 *    the buffered body. Reading ahead, rather than capping the stream as the
 *    controller consumes it, matters for two reasons. A cap that trips inside
 *    Spring MVC surfaces as HttpMessageNotReadableException, which
 *    GlobalExceptionHandler reports as a 400 blaming the sender's JSON. And
 *    for a form POST (the PayFast ITN) Spring never calls getInputStream():
 *    it rebuilds the body from getParameterMap(), which Tomcat fills by
 *    reading the connection directly, so a wrapped stream would cap nothing.
 *
 * Scoped by prefix rather than a list of provider paths, so a new provider's
 * callback cannot ship without the cap. The only other endpoint under it is
 * the bodyless GET /api/v1/payments/health. Every method is covered because
 * FormContentFilter reads PUT/PATCH/DELETE form bodies in full before any
 * controller or security check runs.
 *
 * Runs after CorrelationIdFilter so a 413 carries a requestId, and before
 * FormContentFilter and Spring Security so nothing downstream reads first.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class WebhookBodyLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookBodyLimitFilter.class);

    static final String SCOPE = "/api/v1/payments/";
    public static final int MAX_BODY_BYTES = 64 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(SCOPE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            reject(request, response, "Content-Length " + declared);
            return;
        }
        if (declared >= 0) {
            chain.doFilter(request, response);
            return;
        }

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(request, response, "chunked body past " + MAX_BODY_BYTES + " bytes");
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response,
                               String reason) throws IOException {
        // Logged because nothing downstream will be: the controller never
        // runs, so without this line a misbehaving provider (or an attack)
        // leaves no trace beyond the access log.
        log.warn("Payment callback body too large: {} {} from {} ({}) - returning 413",
                request.getMethod(), request.getRequestURI(),
                AuthRateLimitFilter.clientIp(request), reason);

        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"Payload too large",\
                "status":413,"detail":"Payment callback bodies are limited to \
                %d bytes."}""".formatted(MAX_BODY_BYTES));
    }

    /**
     * Replays a body the filter already read, so everything downstream sees
     * an ordinary fixed-length request. Only built on the chunked path.
     */
    static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;
        private Map<String, String[]> parameters;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // The payment controllers are blocking; nothing here
                    // switches to async IO.
                    throw new UnsupportedOperationException("Replayed body supports blocking reads only");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }

        // Tomcat skips form parsing once getInputStream() has been called, and
        // the filter had to call it to measure the body. Without these
        // overrides a chunked PayFast ITN would reach the controller with an
        // empty body and fail its signature check.

        @Override
        public Map<String, String[]> getParameterMap() {
            if (parameters == null) {
                parameters = isFormPost() ? queryThenBodyParameters() : super.getParameterMap();
            }
            return parameters;
        }

        @Override
        public String getParameter(String name) {
            String[] values = getParameterMap().get(name);
            return (values == null || values.length == 0) ? null : values[0];
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(getParameterMap().keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] values = getParameterMap().get(name);
            return values == null ? null : values.clone();
        }

        private boolean isFormPost() {
            String contentType = getContentType();
            if (contentType == null || !"POST".equalsIgnoreCase(getMethod())) {
                return false;
            }
            int semicolon = contentType.indexOf(';');
            String mediaType = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
            return "application/x-www-form-urlencoded".equalsIgnoreCase(mediaType);
        }

        /**
         * Query string first, then body, in wire order: the order Tomcat
         * itself produces. Order is load-bearing, since PayFast signs the
         * fields in the order they were sent and Spring rebuilds the body
         * from this map.
         */
        private Map<String, String[]> queryThenBodyParameters() {
            Map<String, List<String>> merged = new LinkedHashMap<>();
            super.getParameterMap().forEach((name, values) ->
                    merged.computeIfAbsent(name, k -> new ArrayList<>()).addAll(List.of(values)));

            Charset charset = charset();
            for (String pair : new String(body, charset).split("&")) {
                int eq = pair.indexOf('=');
                String rawName = eq < 0 ? pair : pair.substring(0, eq);
                if (rawName.isEmpty()) {
                    continue;
                }
                String name;
                String value;
                try {
                    name = URLDecoder.decode(rawName, charset);
                    value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), charset);
                } catch (IllegalArgumentException malformedEscape) {
                    // Tomcat drops a pair it cannot decode and keeps the rest.
                    continue;
                }
                merged.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }

            Map<String, String[]> result = new LinkedHashMap<>();
            merged.forEach((name, values) -> result.put(name, values.toArray(String[]::new)));
            return Collections.unmodifiableMap(result);
        }

        private Charset charset() {
            String encoding = getCharacterEncoding();
            return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        }
    }
}

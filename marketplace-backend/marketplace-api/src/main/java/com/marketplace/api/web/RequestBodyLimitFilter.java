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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

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
 * Caps request bodies before anything reads them.
 *
 * Nothing else bounds a JSON body. spring.servlet.multipart covers uploads
 * and Tomcat's form-post limit covers only its own parameter parsing, so
 * Jackson (or a @RequestBody String) would take whatever arrived. That hurt
 * in three places: the payment callbacks are permitAll and read the whole
 * body before checking the signature; auth and newsletter are permitAll by
 * nature; and any account holder can sign in and post to the rest. A few
 * concurrent multi-hundred-MB bodies could exhaust the heap of the single
 * Railway instance.
 *
 * The limits, first match wins:
 *  - /api/v1/payments/: 64 KB, whatever the content type. Real provider
 *    events are a few KB. Matched by prefix rather than a list of provider
 *    paths, so a new provider's callback cannot ship without the cap.
 *  - multipart/*: left to the container, because the product image and
 *    listing draft uploads legitimately exceed the JSON cap. Tomcat spools
 *    file parts to disk under spring.servlet.multipart (5 MB file, 6 MB
 *    request) and holds plain form fields in memory only up to
 *    server.tomcat.max-http-form-post-size, which application.yml sets to
 *    the same 256 KB so a multipart body buys no more heap than JSON does.
 *  - everything else: 256 KB. The largest fixed-shape body is a product
 *    (200-character name, 2000-character description, 64-character SKU,
 *    ten 30-character tags), about 16 KB even fully escaped, so this is
 *    sixteen-fold headroom. The one body that grows with data is the admin
 *    payout approval's list of entry ids, which reaches the cap at roughly
 *    30,000 entries. Whatever outgrows it shows up as a logged 413, not a
 *    silent failure.
 *
 * The size is not always known up front, hence two paths:
 *  - Content-Length present: over the cap is a 413 before a byte is read.
 *    Under it, the request goes through untouched, because Tomcat never
 *    reads past the declared length.
 *  - Transfer-Encoding (chunked): read at most cap + 1 bytes here and 413
 *    if that extra byte shows up; otherwise the chain gets a request
 *    replaying the buffered body. Reading ahead, rather than capping the
 *    stream as the controller consumes it, matters for two reasons. A cap
 *    that trips inside Spring MVC surfaces as HttpMessageNotReadableException,
 *    which GlobalExceptionHandler reports as a 400 blaming the sender's JSON.
 *    And for a form POST without a query string (the PayFast ITN) Spring
 *    never calls getInputStream(): it rebuilds the body from
 *    getParameterMap(), which Tomcat fills by reading the connection
 *    directly, so a wrapped stream would cap nothing.
 *  - Neither header: HTTP/1.1 has no body, so there is nothing to read and
 *    the request (every plain GET) is not wrapped.
 *
 * Runs after CorrelationIdFilter so a 413 carries a requestId, and before
 * Spring Security, so the cap holds on authenticated paths too and nothing
 * downstream reads first. FormContentFilter, which used to parse
 * PUT/PATCH/DELETE form bodies ahead of security, is switched off in
 * application.yml.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestBodyLimitFilter.class);

    static final String PAYMENTS_PREFIX = "/api/v1/payments/";
    public static final int PAYMENT_CALLBACK_MAX_BYTES = 64 * 1024;
    public static final int DEFAULT_MAX_BYTES = 256 * 1024;

    private final CorsOrigins allowedOrigins;
    private final int maxParameterCount;

    /**
     * maxParameterCount is Tomcat's own form-parsing limit, read from the same
     * property, so the replay below never parses more than Tomcat would have.
     */
    public RequestBodyLimitFilter(CorsOrigins allowedOrigins,
                                  @Value("${server.tomcat.max-parameter-count:10000}") int maxParameterCount) {
        this.allowedOrigins = allowedOrigins;
        this.maxParameterCount = maxParameterCount;
    }

    /** The cap for this request, or -1 when the multipart limits already bound it. */
    static int limitFor(HttpServletRequest request) {
        // The path Spring routes on, not getRequestURI(): the raw URI still
        // carries percent-encoding (/api/v1/%70ayments/... reaches the same
        // controller) and, behind ForwardedHeaderFilter in prod, whatever
        // X-Forwarded-Prefix the caller sent. Either would drop a payment
        // callback from 64 KB to the general cap.
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (path.startsWith(PAYMENTS_PREFIX)) {
            return PAYMENT_CALLBACK_MAX_BYTES;
        }
        // Same test Spring's multipart resolver uses, so anything it will
        // parse (and bound) is exactly what this filter skips.
        String contentType = request.getContentType();
        if (contentType != null && contentType.regionMatches(true, 0, "multipart/", 0, 10)) {
            return -1;
        }
        return DEFAULT_MAX_BYTES;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return limitFor(request) < 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        int limit = limitFor(request);
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            reject(request, response, limit, "Content-Length " + declared);
            return;
        }
        if (declared >= 0 || request.getHeader("Transfer-Encoding") == null) {
            chain.doFilter(request, response);
            return;
        }

        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            reject(request, response, limit, "chunked body past " + limit + " bytes");
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body, maxParameterCount), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        int limit, String reason) throws IOException {
        // Logged because nothing downstream will be: the controller never
        // runs, so without this line a misbehaving client (or an attack)
        // leaves no trace beyond the access log.
        log.warn("Request body too large: {} {} from {} ({}) - returning 413",
                request.getMethod(), request.getRequestURI(),
                AuthRateLimitFilter.clientIp(request), reason);

        // CORS headers by hand, as AuthRateLimitFilter does for its 429: this
        // runs before the security chain's CorsFilter, and a browser can get
        // here (the product form's description has no length limit, so a big
        // paste does it). Without them the frontend sees an opaque network
        // error instead of a 413 it can explain.
        String origin = request.getHeader("Origin");
        if (origin != null && allowedOrigins.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.addHeader("Vary", "Origin");
            response.setHeader("Access-Control-Expose-Headers", "X-Request-Id");
        }
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"Payload too large",\
                "status":413,"detail":"Request bodies on this endpoint are \
                limited to %d bytes."}""".formatted(limit));
    }

    /**
     * Replays a body the filter already read, so everything downstream sees
     * an ordinary fixed-length request. Only built on the chunked path.
     */
    static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;
        private final int maxParameterCount;
        private Map<String, String[]> parameters;

        BufferedBodyRequest(HttpServletRequest request, byte[] body, int maxParameterCount) {
            super(request);
            this.body = body;
            this.maxParameterCount = maxParameterCount;
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
                    // Every controller in this app reads its body blocking;
                    // nothing switches to servlet async IO.
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
         * itself produces, so anything reading parameters sees what it would
         * have without the filter. Body order is load-bearing: with no query
         * string Spring rebuilds a form body from this map, and PayFast signs
         * its fields in the order they were sent.
         */
        private Map<String, String[]> queryThenBodyParameters() {
            Map<String, List<String>> merged = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<String, String[]> query : super.getParameterMap().entrySet()) {
                merged.computeIfAbsent(query.getKey(), k -> new ArrayList<>()).addAll(List.of(query.getValue()));
                count += query.getValue().length;
            }

            // Stops at the same parameter count Tomcat does, and walks the
            // body by hand because split("&") would build every pair before
            // any limit could apply. Unbounded, 256 KB of a1&a2&... became
            // tens of thousands of map entries: megabytes of heap bought with
            // a body the size cap had already let through.
            Charset charset = charset();
            String form = new String(body, charset);
            int start = 0;
            while (start <= form.length() && count < maxParameterCount) {
                int amp = form.indexOf('&', start);
                int end = amp < 0 ? form.length() : amp;
                String pair = form.substring(start, end);
                start = end + 1;

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
                count++;
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

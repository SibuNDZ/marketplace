package com.marketplace.api.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Transactional email via Resend's REST API.
 *
 * Uses the JDK HttpClient rather than the Resend SDK: two POSTs with a
 * bearer token do not justify a dependency, and the SDK would need pinning
 * against the same Spring-managed Jackson already on the classpath.
 *
 * NOTHING HERE THROWS. Every send returns a boolean, because the caller is
 * registration and a provider outage must not cost a user their account —
 * they get created, told the email failed, and offered a resend. Throwing
 * would roll back the transaction that just created them, and the retry
 * would then hit "email already registered" on an account they cannot log
 * into. That trade is the whole reason this returns a flag.
 *
 * Blank API key disables sending and logs the link instead. That is the dev
 * and test path: no key in application.yml, no outbound calls in CI, and a
 * clickable URL in the console for local flows. It is also the reason a
 * missing key in production is NOT a fail-fast at startup like JWT_SECRET —
 * silence here degrades to "resend button does nothing", not a crash loop.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final URI RESEND_ENDPOINT = URI.create("https://api.resend.com/emails");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String from;
    private final String frontendUrl;

    public EmailService(ObjectMapper objectMapper,
                        @Value("${app.email.resend-api-key:}") String apiKey,
                        @Value("${app.email.from:eRestyu <noreply@erestyu.com>}") String from,
                        @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.from = from;
        // Trailing slash here would produce erestyu.com//verify-email, which
        // some routers 404 — normalise once rather than at each call site.
        this.frontendUrl = frontendUrl.endsWith("/")
                ? frontendUrl.substring(0, frontendUrl.length() - 1)
                : frontendUrl;
    }

    /** @return true if Resend accepted the message. */
    public boolean sendVerification(String to, String firstName, String rawToken) {
        String link = frontendUrl + "/verify-email?token=" + rawToken;
        return send(to, "Confirm your eRestyu account",
                template(firstName,
                        "Confirm your email to finish setting up your eRestyu account.",
                        "Confirm my email", link,
                        "This link expires in 24 hours. If you did not sign up for eRestyu, "
                                + "you can ignore this email."));
    }

    /** @return true if Resend accepted the message. */
    public boolean sendPasswordReset(String to, String firstName, String rawToken) {
        String link = frontendUrl + "/reset-password?token=" + rawToken;
        return send(to, "Reset your eRestyu password",
                template(firstName,
                        "We received a request to reset your eRestyu password.",
                        "Reset my password", link,
                        "This link expires in 1 hour and can only be used once. If you did not "
                                + "request this, ignore this email; your password will not change."));
    }

    /**
     * Package-private so OrderEmailService shares this transport (one client,
     * one key, one swallow-and-log policy) instead of duplicating it.
     *
     * @return true if Resend accepted the message.
     */
    boolean send(String to, String subject, String html) {
        if (apiKey.isBlank()) {
            // Not an error: this is the configured dev/test behaviour.
            log.warn("RESEND_API_KEY not set - email to {} not sent. Subject: {}", to, subject);
            return false;
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "from", from,
                    "to", List.of(to),
                    "subject", subject,
                    "html", html));

            HttpRequest request = HttpRequest.newBuilder(RESEND_ENDPOINT)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 == 2) {
                return true;
            }
            // Body carries Resend's reason (unverified domain, bad key, rate
            // limit). Logging it is the difference between a five-minute fix
            // and guessing.
            log.error("Resend rejected email to {} - HTTP {}: {}",
                    to, response.statusCode(), response.body());
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted sending email to {}", to, e);
            return false;
        } catch (Exception e) {
            log.error("Failed to send email to {}", to, e);
            return false;
        }
    }

    /**
     * Inline styles only, and a visible fallback URL under the button —
     * mail clients strip stylesheets, and a button that fails to render
     * leaves the user with no way to act.
     */
    private String template(String firstName, String intro,
                            String buttonLabel, String link, String footer) {
        return """
               <div style="font-family:system-ui,-apple-system,'Segoe UI',sans-serif;\
               max-width:480px;margin:0 auto;padding:32px 24px;color:#1a1a1a">
                 <div style="font-size:24px;font-weight:800;color:#e2542c;margin-bottom:24px">eRestyu</div>
                 <p style="font-size:16px;margin:0 0 8px">Hi %s,</p>
                 <p style="font-size:15px;line-height:1.5;margin:0 0 24px">%s</p>
                 <a href="%s" style="display:inline-block;background:#1a2e24;color:#ffffff;\
               text-decoration:none;padding:12px 24px;border-radius:8px;font-weight:600;\
               font-size:15px">%s</a>
                 <p style="font-size:13px;color:#666;line-height:1.5;margin:24px 0 0">
                   Or paste this into your browser:<br>
                   <a href="%s" style="color:#2f6f4e;word-break:break-all">%s</a>
                 </p>
                 <p style="font-size:12px;color:#888;line-height:1.5;margin:24px 0 0;\
               border-top:1px solid #e5e5e5;padding-top:16px">%s</p>
               </div>
               """.formatted(escape(firstName), escape(intro), link,
                             escape(buttonLabel), link, link, escape(footer));
    }

    /**
     * The user-controlled values interpolated above (first name) reach an
     * HTML document, so they are escaped here. An unescaped name is stored
     * XSS aimed at whoever opens the mail.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

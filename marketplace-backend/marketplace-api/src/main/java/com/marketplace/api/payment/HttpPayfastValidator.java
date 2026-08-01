package com.marketplace.api.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Real implementation of the server-confirmation check: POST the received
 * params to PayFast's validate endpoint; anything but a 2xx "VALID" body is
 * a refusal. JDK HttpClient per house pattern (EmailService): two POSTs a
 * day do not justify an SDK.
 *
 * Failure mode is REJECT, not allow: if PayFast's validator is unreachable,
 * the ITN is dropped and PayFast retries later (their retry ladder:
 * immediate, 10 minutes, then exponential), by which time the validator is
 * back. Money never moves on our say-so alone.
 */
@Component
public class HttpPayfastValidator implements PayfastValidator {

    private static final Logger log = LoggerFactory.getLogger(HttpPayfastValidator.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final URI validateUrl;

    public HttpPayfastValidator(@Value("${app.payfast.validate-url}") String validateUrl) {
        this.validateUrl = URI.create(validateUrl);
    }

    @Override
    public boolean confirms(String paramString) {
        try {
            HttpRequest request = HttpRequest.newBuilder(validateUrl)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(paramString, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            boolean valid = response.statusCode() / 100 == 2
                    && response.body() != null
                    && response.body().strip().equalsIgnoreCase("VALID");
            if (!valid) {
                log.warn("PayFast validate endpoint refused ITN: HTTP {} body '{}'",
                        response.statusCode(), response.body() == null ? "" : response.body().strip());
            }
            return valid;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted confirming ITN with PayFast", e);
            return false;
        } catch (Exception e) {
            log.error("Failed to confirm ITN with PayFast validate endpoint", e);
            return false;
        }
    }
}

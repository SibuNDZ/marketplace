package com.marketplace.api.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Voyage AI embeddings, used only by the related-products job.
 *
 * The JDK HttpClient rather than the Voyage SDK, matching EmailService's
 * choice for Resend: this is one POST with a JSON body, and a dependency
 * would carry more surface than the call it replaces.
 *
 * FAULT ISOLATION, same rule as AnthropicConfig. A missing or invalid
 * VOYAGE_API_KEY must degrade to "related products fall back to text
 * similarity" and never to "the API will not boot". So the key has a blank
 * default rather than being fail-fast, {@link #isConfigured()} is checked
 * before any call, and every failure path returns empty instead of throwing
 * into the caller. Nothing on the shopping path depends on this class.
 *
 * Anthropic does not offer an embedding model — verified against the docs,
 * which name Voyage as the recommended provider. The ANTHROPIC_API_KEY
 * already in this app cannot be reused here.
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final URI ENDPOINT = URI.create("https://api.voyageai.com/v1/embeddings");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String apiKey;
    private final String model;

    public EmbeddingClient(@Value("${app.embeddings.api-key:}") String apiKey,
                           @Value("${app.embeddings.model:voyage-4}") String model) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model;
    }

    /** False when no key is configured, which is a normal state, not an error. */
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /**
     * Embeds a batch of product texts, in order.
     *
     * input_type=document is deliberate and not cosmetic: Voyage prepends a
     * different instruction for documents than for queries, and mixing the
     * two degrades retrieval quality. Everything embedded here is a stored
     * product, so it is always a document. A future "search by meaning"
     * feature must embed the shopper's text with input_type=query instead.
     *
     * Returns an empty list on ANY failure — no key, transport error, non-200,
     * or a malformed body. The caller treats that as "not embedded yet" and
     * simply tries again on the next run, which is the correct behaviour for
     * a rate limit or a transient outage alike.
     */
    public List<double[]> embedDocuments(List<String> texts) {
        if (!isConfigured() || texts.isEmpty()) return List.of();

        try {
            String body = mapper.writeValueAsString(java.util.Map.of(
                    "input", texts,
                    "model", model,
                    "input_type", "document"));

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(ENDPOINT)
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + apiKey)
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // Body is logged: Voyage explains quota and validation errors
                // there, and it never contains the key.
                log.warn("Voyage embeddings returned HTTP {} - {}",
                        response.statusCode(), truncate(response.body()));
                return List.of();
            }

            JsonNode data = mapper.readTree(response.body()).path("data");
            if (!data.isArray()) {
                log.warn("Voyage embeddings response had no data array");
                return List.of();
            }

            // Sorted by the response's own index rather than trusting array
            // order, so a vector can never be attached to the wrong product.
            List<JsonNode> rows = new ArrayList<>();
            data.forEach(rows::add);
            rows.sort(java.util.Comparator.comparingInt(n -> n.path("index").asInt()));

            List<double[]> out = new ArrayList<>(rows.size());
            for (JsonNode row : rows) {
                JsonNode vector = row.path("embedding");
                if (!vector.isArray() || vector.isEmpty()) return List.of();
                double[] values = new double[vector.size()];
                for (int i = 0; i < vector.size(); i++) values[i] = vector.get(i).asDouble();
                out.add(values);
            }

            // A partial batch would silently misalign texts and vectors.
            if (out.size() != texts.size()) {
                log.warn("Voyage returned {} embeddings for {} inputs; discarding batch",
                        out.size(), texts.size());
                return List.of();
            }
            return out;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("Voyage embeddings call failed: {}", e.toString());
            return List.of();
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}

package com.marketplace.api.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Keeps product embeddings in step with product text.
 *
 * A scheduled sweep rather than an embed-on-save hook, for three reasons:
 * a vendor's save must not block on a third-party HTTP call or fail because
 * Voyage is down; existing products need backfilling anyway, and a sweep
 * handles new and old rows with one code path; and a failed embed must retry
 * on its own, which a fire-and-forget call inside a transaction would not.
 *
 * Idempotent by construction. Work is selected by hash mismatch, so a run
 * with nothing to do costs one indexed query and zero API calls, and a run
 * interrupted halfway simply picks up where it left off.
 */
@Component
public class ProductEmbeddingJob {

    private static final Logger log = LoggerFactory.getLogger(ProductEmbeddingJob.class);

    private final ProductEmbeddingRepository repository;
    private final EmbeddingClient client;
    private final int batchSize;

    public ProductEmbeddingJob(ProductEmbeddingRepository repository,
                               EmbeddingClient client,
                               @Value("${app.embeddings.batch-size:16}") int batchSize) {
        this.repository = repository;
        this.client = client;
        this.batchSize = batchSize;
    }

    /**
     * One batch per run, not a drain loop. A catalogue that suddenly needs
     * hundreds of embeds should spend that over several runs rather than
     * hammering the provider in one burst and risking a rate limit that
     * fails the whole sweep.
     */
    @Scheduled(
            fixedDelayString = "${app.embeddings.sweep-ms:300000}",
            initialDelayString = "${app.embeddings.initial-delay-ms:20000}")
    public void embedStaleProducts() {
        if (!client.isConfigured()) return;   // no key: silently inert, by design

        List<ProductEmbeddingRepository.Pending> pending = repository.findStale(batchSize);
        if (pending.isEmpty()) return;

        List<String> texts = pending.stream().map(ProductEmbeddingRepository.Pending::text).toList();
        List<double[]> vectors = client.embedDocuments(texts);

        // Empty means the call failed and already logged why. Leaving the rows
        // untouched is what makes the retry automatic.
        if (vectors.size() != pending.size()) return;

        for (int i = 0; i < pending.size(); i++) {
            var row = pending.get(i);
            repository.save(row.productId(), sha256Hex(row.text()), vectors.get(i));
        }
        log.info("Embedded {} product(s)", pending.size());
    }

    /**
     * Must match Postgres encode(sha256(convert_to(text,'UTF8')),'hex')
     * exactly, or every row looks permanently stale and re-embeds forever.
     * Same bytes, same algorithm, lower-case hex on both sides.
     */
    static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

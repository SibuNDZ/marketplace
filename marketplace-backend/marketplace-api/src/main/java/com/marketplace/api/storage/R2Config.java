package com.marketplace.api.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * Cloudflare R2 via the S3-compatible API — one S3Client bean pointed at
 * the account endpoint, and one thin service (ObjectStorageService) that
 * owns every object-storage touch. Nothing else in the codebase imports
 * the AWS SDK.
 *
 * R2 specifics vs vanilla S3:
 *  - endpoint is https://<ACCOUNT_ID>.r2.cloudflarestorage.com
 *  - region is literally "auto"
 *  - NO public-read ACLs on put — R2 ignores/rejects them; public access
 *    is a BUCKET-level setting (custom domain), which is the better model
 *    anyway: the app writes, the bucket policy serves.
 *
 * @Lazy is deliberate, unlike the fail-fast-at-boot treatment JWT_SECRET/
 * STRIPE_SECRET_KEY get: ObjectStorageService sits behind ProductService,
 * which nearly every page depends on, so a boot-time failure here takes
 * down the whole site rather than just image upload. Lazy init means a
 * misconfigured R2 env var surfaces as a 500 on the upload endpoint the
 * first time it's actually used, not a crash loop for every request.
 */
@Configuration
class R2Config {

    private static final Logger log = LoggerFactory.getLogger(R2Config.class);

    @Bean
    @Lazy
    S3Client r2Client(@Value("${app.storage.r2.account-id}") String accountId,
                      @Value("${app.storage.r2.access-key-id}") String accessKeyId,
                      @Value("${app.storage.r2.secret-access-key}") String secret,
                      @Value("${app.storage.r2.endpoint-override:}") String endpointOverride) {
        // Trim defensively: a pasted env var with a trailing newline/space is
        // a silent, easy-to-miss way to break URI.create() below — this
        // turned an env var typo into a full crash-loop once already.
        accountId = accountId.trim();
        accessKeyId = accessKeyId.trim();
        secret = secret.trim();
        endpointOverride = endpointOverride.trim();

        // endpointOverride exists ONLY so tests can point this at a MinIO
        // testcontainer; blank in every real environment.
        String endpoint = endpointOverride.isBlank()
                ? "https://" + accountId + ".r2.cloudflarestorage.com"
                : endpointOverride;

        // Never log accessKeyId/secret — account-id and the derived endpoint
        // are not sensitive and this line is the single fastest way to catch
        // a misconfigured env var (e.g. R2_ACCOUNT_ID holding the full URL
        // instead of the bare hex id) from the boot log alone.
        log.info("R2 storage configured: endpoint={}", endpoint);

        try {
            return S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of("auto"))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secret)))
                    .forcePathStyle(true)   // required by MinIO; harmless on R2
                    .build();
        } catch (IllegalArgumentException e) {
            log.error("R2 endpoint '{}' is not a valid URI — check R2_ACCOUNT_ID is the bare " +
                    "account-id hex string, not a full URL: {}", endpoint, e.getMessage());
            throw e;
        }
    }
}

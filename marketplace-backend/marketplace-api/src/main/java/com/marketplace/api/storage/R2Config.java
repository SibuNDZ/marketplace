package com.marketplace.api.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 */
@Configuration
class R2Config {

    @Bean
    S3Client r2Client(@Value("${app.storage.r2.account-id}") String accountId,
                      @Value("${app.storage.r2.access-key-id}") String accessKeyId,
                      @Value("${app.storage.r2.secret-access-key}") String secret,
                      @Value("${app.storage.r2.endpoint-override:}") String endpointOverride) {
        // endpointOverride exists ONLY so tests can point this at a MinIO
        // testcontainer; blank in every real environment.
        String endpoint = endpointOverride.isBlank()
                ? "https://" + accountId + ".r2.cloudflarestorage.com"
                : endpointOverride;
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secret)))
                .forcePathStyle(true)   // required by MinIO; harmless on R2
                .build();
    }
}

package com.marketplace.api.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

@Service
public class ObjectStorageService {

    private final S3Client s3;
    private final String bucket;
    private final String publicBaseUrl;

    public ObjectStorageService(S3Client s3,
                                @Value("${app.storage.r2.bucket}") String bucket,
                                @Value("${app.storage.r2.public-base-url}") String publicBaseUrl) {
        this.s3 = s3;
        this.bucket = bucket;
        // e.g. https://images.erestyu.com — no trailing slash
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public void put(String key, InputStream content, long length, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .cacheControl("public, max-age=31536000, immutable")
                        // immutable is safe because keys are UUIDs — a replaced
                        // image gets a NEW key, so caches never need purging
                        .build(),
                RequestBody.fromInputStream(content, length));
    }

    /** Best-effort: a leaked orphan object is a cost rounding error, not a bug. */
    public void deleteQuietly(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception ignored) {
        }
    }

    public String publicUrl(String key) {
        return publicBaseUrl + "/" + key;
    }
}

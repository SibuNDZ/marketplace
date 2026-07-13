package com.marketplace.api.storage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

@Service
public class ObjectStorageService {

    // ObjectProvider, not a direct S3Client — this is what actually makes
    // r2Client's @Lazy meaningful. @Lazy on a @Bean only skips EAGER
    // pre-instantiation; it still gets built immediately if a normal
    // (non-lazy) singleton constructor demands a concrete instance, which a
    // direct `S3Client s3` constructor parameter does. ObjectProvider defers
    // resolution to the point of .getObject(), i.e. first real use — this is
    // what took the whole site down once already, so don't "simplify" this
    // back to direct injection without re-verifying with a boot test using
    // deliberately-broken R2 config (see R2ConfigFaultIsolationTest).
    private final ObjectProvider<S3Client> s3Provider;
    private final String bucket;
    private final String publicBaseUrl;

    public ObjectStorageService(ObjectProvider<S3Client> s3Provider,
                                @Value("${app.storage.r2.bucket}") String bucket,
                                @Value("${app.storage.r2.public-base-url}") String publicBaseUrl) {
        this.s3Provider = s3Provider;
        this.bucket = bucket;
        // e.g. https://images.erestyu.com — no trailing slash
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public void put(String key, InputStream content, long length, String contentType) {
        s3Provider.getObject().putObject(PutObjectRequest.builder()
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
            s3Provider.getObject().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception ignored) {
        }
    }

    public String publicUrl(String key) {
        return publicBaseUrl + "/" + key;
    }
}

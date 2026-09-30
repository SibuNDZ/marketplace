package com.marketplace.api.vendor;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One store's link to one platform (V36). Holds the normalised HANDLE, never a
 * URL; SocialPlatform.urlFor builds the public link. See seller-social-links.md.
 */
@Entity
@Table(name = "vendor_social_links")
public class VendorSocialLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    private SocialPlatform platform;

    @Column(name = "handle", nullable = false, length = 100)
    private String handle;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected VendorSocialLink() {}

    public VendorSocialLink(Long vendorId, SocialPlatform platform, String handle) {
        this.vendorId = vendorId;
        this.platform = platform;
        this.handle = handle;
    }

    public Long getId() { return id; }
    public Long getVendorId() { return vendorId; }
    public SocialPlatform getPlatform() { return platform; }
    public String getHandle() { return handle; }

    public void setHandle(String handle) {
        this.handle = handle;
        this.updatedAt = LocalDateTime.now();
    }

    public String url() {
        return platform.urlFor(handle);
    }
}

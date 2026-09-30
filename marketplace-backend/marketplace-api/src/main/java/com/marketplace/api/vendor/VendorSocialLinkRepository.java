package com.marketplace.api.vendor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VendorSocialLinkRepository extends JpaRepository<VendorSocialLink, Long> {

    List<VendorSocialLink> findByVendorId(Long vendorId);

    Optional<VendorSocialLink> findByVendorIdAndPlatform(Long vendorId, SocialPlatform platform);

    @Modifying
    @Query("DELETE FROM VendorSocialLink l WHERE l.vendorId = :vendorId AND l.platform = :platform")
    int deleteOne(@Param("vendorId") Long vendorId, @Param("platform") SocialPlatform platform);

    @Modifying
    @Query("DELETE FROM VendorSocialLink l WHERE l.vendorId = :vendorId")
    int deleteAllForVendor(@Param("vendorId") Long vendorId);
}

package com.marketplace.api.controller;

import com.marketplace.api.dto.VendorSettingsDtos.VendorSettingsRequest;
import com.marketplace.api.dto.VendorSettingsDtos.VendorSettingsResponse;
import com.marketplace.api.entity.User;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Vendor self-service settings. Thin enough that no service layer exists yet:
 * one column, scoped to the caller's own row by construction. Grows a service
 * the day it grows a second concern.
 */
@RestController
@RequestMapping("/api/v1/vendor/settings")
@PreAuthorize("hasRole('VENDOR')")
public class VendorSettingsController {

    private final UserRepository userRepository;

    public VendorSettingsController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public VendorSettingsResponse get(@AuthenticationPrincipal UserPrincipal me) {
        User vendor = userRepository.findById(me.getId()).orElseThrow();
        return new VendorSettingsResponse(vendor.getDeliveryFee());
    }

    @PutMapping
    @Transactional
    public VendorSettingsResponse update(@Valid @RequestBody VendorSettingsRequest request,
                                         @AuthenticationPrincipal UserPrincipal me) {
        User vendor = userRepository.findById(me.getId()).orElseThrow();
        vendor.setDeliveryFee(request.deliveryFee());
        return new VendorSettingsResponse(vendor.getDeliveryFee());
    }
}

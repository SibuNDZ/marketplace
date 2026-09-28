package com.marketplace.api.controller;

import com.marketplace.api.entity.ReferralSource;
import com.marketplace.api.entity.UserRole;
import com.marketplace.api.repository.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Where sellers say they came from, admin-only.
 *
 * This exists so the attribution column is readable without a psql session.
 * A write-only column is not a measurement: the question was added so a
 * recruitment campaign could be evaluated on registrations rather than on
 * impressions, and that only works if the counts can be looked at monthly by
 * the person paying for the campaign.
 *
 * Double-gated like the other admin controllers: the /api/v1/admin/** rule in
 * SecurityConfig requires ROLE_ADMIN before routing, and @PreAuthorize
 * repeats it here.
 *
 * Deliberately not per-vendor rows. The useful unit is the channel, and a
 * list naming which vendor said what turns a marketing number into a file on
 * individuals for no extra insight.
 */
@RestController
@RequestMapping("/api/v1/admin/sellers")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSellerSourceController {

    private final UserRepository userRepository;

    public AdminSellerSourceController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * unknown is reported separately from the sources rather than as one of
     * them. Every vendor who registered before this shipped is unknown, so
     * early on it is the largest bucket by far, and a reader who cannot see
     * that will read a two-vendor channel as a trend.
     */
    public record SellerSources(
            long totalVendors,
            long answered,
            long unknown,
            List<SourceCount> sources,
            List<String> otherLabels
    ) {}

    public record SourceCount(ReferralSource source, long count) {}

    @GetMapping("/sources")
    @Transactional(readOnly = true)
    public SellerSources sources() {
        List<UserRepository.ReferralSourceCount> rows =
                userRepository.countByReferralSource(UserRole.VENDOR);

        long unknown = rows.stream()
                .filter(r -> r.getSource() == null)
                .mapToLong(UserRepository.ReferralSourceCount::getTotal)
                .sum();

        List<SourceCount> sources = rows.stream()
                .filter(r -> r.getSource() != null)
                .map(r -> new SourceCount(r.getSource(), r.getTotal()))
                .toList();

        long answered = sources.stream().mapToLong(SourceCount::count).sum();

        return new SellerSources(
                answered + unknown,
                answered,
                unknown,
                sources,
                userRepository.otherReferralDetails(UserRole.VENDOR));
    }
}

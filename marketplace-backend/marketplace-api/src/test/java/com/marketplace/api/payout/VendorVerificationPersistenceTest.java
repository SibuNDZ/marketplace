package com.marketplace.api.payout;

import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.DocumentType;
import com.marketplace.api.entity.SettlementMode;
import com.marketplace.api.entity.User;
import com.marketplace.api.entity.VendorVerification;
import com.marketplace.api.entity.VerificationOutcome;
import com.marketplace.api.entity.VerificationProviderResult;
import com.marketplace.api.repository.UserRepository;
import com.marketplace.api.repository.VendorVerificationRepository;
import com.marketplace.api.service.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V38 round trip: the vendor_verifications table, the three new users
 * columns and settlement_mode all map (Hibernate validates the schema at
 * boot, so a mismatch fails every test in this class, not just one), and
 * the entity's toString() keeps the sensitive fields out of log lines.
 */
@Testcontainers
@SpringBootTest
class VendorVerificationPersistenceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret",
                () -> "dGhpcy1pcy1hLXRlc3Qtb25seS1zZWNyZXQta2V5LTMyYnl0ZXM=");
    }

    @Autowired VendorVerificationRepository verifications;
    @Autowired UserRepository               userRepository;
    @Autowired TestFixtures                 fixtures;

    private static String uniq(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("a verification row saves and reads back with every column, and the latest-row lookup finds it")
    void roundTrip() {
        User vendor = fixtures.vendor(uniq("vv-vendor"));

        VendorVerification v = new VendorVerification();
        v.setVendor(vendor);
        v.setDocumentType(DocumentType.ID_NUMBER);
        v.setDocumentLast4("4321");
        v.setAccountHolderName("Thandi Dlamini");
        v.setBankCode("198765");
        v.setAccountLast4("6789");
        v.setAccountType(BankAccountType.CHEQUE);
        v.setProviderResult(VerificationProviderResult.NOT_RUN);
        v.setNote("first submission");
        Long id = verifications.save(v).getId();

        VendorVerification read = verifications.findById(id).orElseThrow();
        assertThat(read.getVendor().getId()).isEqualTo(vendor.getId());
        assertThat(read.getDocumentType()).isEqualTo(DocumentType.ID_NUMBER);
        assertThat(read.getDocumentLast4()).isEqualTo("4321");
        assertThat(read.getAccountHolderName()).isEqualTo("Thandi Dlamini");
        assertThat(read.getBankCode()).isEqualTo("198765");
        assertThat(read.getAccountLast4()).isEqualTo("6789");
        assertThat(read.getAccountType()).isEqualTo(BankAccountType.CHEQUE);
        assertThat(read.getProvider()).isEqualTo("PAYSTACK");
        assertThat(read.getProviderResult()).isEqualTo(VerificationProviderResult.NOT_RUN);
        assertThat(read.getOutcome()).isEqualTo(VerificationOutcome.PENDING);
        assertThat(read.getAccountHolderMatch()).isNull();
        assertThat(read.getVerifiedBy()).isNull();
        assertThat(read.getVerifiedAt()).isNull();
        assertThat(read.getNote()).isEqualTo("first submission");
        assertThat(read.getCreatedAt()).isNotNull();

        assertThat(verifications.findFirstByVendorIdOrderByCreatedAtDescIdDesc(vendor.getId()))
                .map(VendorVerification::getId).contains(id);
        assertThat(verifications.findLatestWithOutcome(VerificationOutcome.PENDING))
                .extracting(VendorVerification::getId).contains(id);
    }

    @Test
    @DisplayName("toString() carries no holder name and neither last-4")
    void toStringIsSafeToLog() {
        VendorVerification v = new VendorVerification();
        v.setAccountHolderName("Thandi Dlamini");
        v.setAccountLast4("6789");
        v.setDocumentLast4("4321");
        v.setBankCode("198765");

        String s = v.toString();
        assertThat(s)
                .doesNotContain("Thandi")
                .doesNotContain("6789")
                .doesNotContain("4321")
                .doesNotContain("198765");
    }

    @Test
    @DisplayName("the new users columns persist, and completeness now needs the bank code")
    void userColumnsAndCompleteness() {
        User vendor = fixtures.vendor(uniq("vv-cols"));
        vendor.setAccountHolderName("Holder");
        vendor.setBankName("Nedbank");
        vendor.setAccountNumber("1122336789");
        vendor.setBranchCode("198765");
        vendor.setAccountType(BankAccountType.CHEQUE);
        assertThat(vendor.hasCompleteBankingDetails())
                .as("everything but the bank code is not complete any more")
                .isFalse();

        vendor.setBankCode("198765");
        vendor.setVendorApprovedAt(java.time.LocalDateTime.of(2026, 10, 18, 9, 0));
        vendor.setPaystackSubaccountCode("ACCT_test_placeholder");
        assertThat(vendor.hasCompleteBankingDetails()).isTrue();
        userRepository.save(vendor);

        User read = userRepository.findById(vendor.getId()).orElseThrow();
        assertThat(read.getBankCode()).isEqualTo("198765");
        assertThat(read.getVendorApprovedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 18, 9, 0));
        assertThat(read.getPaystackSubaccountCode()).isEqualTo("ACCT_test_placeholder");
        // The toString() rule still holds for the new columns.
        assertThat(read.toString()).doesNotContain("198765").doesNotContain("ACCT_");
    }

    @Test
    @DisplayName("settlement mode defaults to EFT on the entity, matching the column default")
    void settlementModeDefault() {
        assertThat(new com.marketplace.api.entity.VendorPayoutEntry().getSettlementMode())
                .isEqualTo(SettlementMode.EFT);
    }
}

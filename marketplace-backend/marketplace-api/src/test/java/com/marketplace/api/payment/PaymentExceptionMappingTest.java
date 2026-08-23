package com.marketplace.api.payment;

import com.marketplace.api.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentExceptionMappingTest {

    private final PaymentHealth health = new PaymentHealth(
            "stripe", "sk_test_x", "", "", "", "");
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(health);

    @Test
    void misconfiguredSetsRfcTypeAndRecordsHealth() {
        ProblemDetail pd = handler.paymentProviderMisconfigured(
                new PaymentExceptions.PaymentProviderMisconfiguredException("keys", null));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(pd.getTitle()).isEqualTo("Payment provider unavailable");
        assertThat(pd.getType()).isEqualTo(URI.create(PaymentExceptions.TYPE_MISCONFIGURED));
        assertThat(pd.getProperties()).containsEntry("code", PaymentExceptions.CODE_MISCONFIGURED);
        assertThat(health.snapshot().lastErrorType()).isEqualTo(PaymentExceptions.CODE_MISCONFIGURED);
    }

    @Test
    void unavailableSetsRfcTypeAndRecordsHealth() {
        ProblemDetail pd = handler.paymentProviderUnavailable(
                new PaymentExceptions.PaymentProviderUnavailableException("down", null));
        assertThat(pd.getType()).isEqualTo(URI.create(PaymentExceptions.TYPE_UNAVAILABLE));
        assertThat(pd.getProperties()).containsEntry("code", PaymentExceptions.CODE_UNAVAILABLE);
        assertThat(health.snapshot().lastErrorType()).isEqualTo(PaymentExceptions.CODE_UNAVAILABLE);
    }

    @Test
    void healthSnapshotNeverIncludesSecrets() {
        PaymentHealth.Snapshot snap = health.snapshot();
        assertThat(snap.provider()).isEqualTo("stripe");
        assertThat(snap.mode()).isEqualTo("test");
        assertThat(snap.configured()).isTrue();
        assertThat(snap.toString()).doesNotContain("sk_test_x");
    }

    @Test
    void fromHttpStatus_401_isMisconfigured() {
        assertThat(PaymentExceptions.fromHttpStatus("Yoco HTTP 401", 401))
                .isInstanceOf(PaymentExceptions.PaymentProviderMisconfiguredException.class);
    }

    @Test
    void fromHttpStatus_502_isUnavailable() {
        assertThat(PaymentExceptions.fromHttpStatus("Yoco HTTP 502", 502))
                .isInstanceOf(PaymentExceptions.PaymentProviderUnavailableException.class);
    }
}

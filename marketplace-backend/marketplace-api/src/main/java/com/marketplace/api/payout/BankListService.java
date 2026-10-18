package com.marketplace.api.payout;

import com.marketplace.api.payment.PaystackClient;
import com.marketplace.api.payment.PaystackExceptions.PaystackUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The bank picker's list (paystack-subaccounts.md §4.1): Paystack's South
 * African banks enabled for account verification, cached in memory for 24
 * hours. The code the vendor picks is the code the provider keys payouts
 * on, so there is deliberately NO hardcoded fallback: a stale or guessed
 * list could send money to the wrong bank. With Paystack down and nothing
 * cached, the card is told so and the vendor tries again later.
 */
@Service
public class BankListService {

    private static final Logger log = LoggerFactory.getLogger(BankListService.class);
    static final Duration TTL = Duration.ofHours(24);

    /** What the picker needs: label and value. */
    public record BankOption(String name, String code) {}

    /** Paystack unreachable and nothing cached: 503 at the API. */
    public static class BankListUnavailableException extends RuntimeException {
        public BankListUnavailableException(Throwable cause) {
            super("Bank list unavailable, try again shortly", cause);
        }
    }

    private final PaystackClient paystack;
    private volatile List<BankOption> cached = List.of();
    private volatile Instant fetchedAt = Instant.EPOCH;

    public BankListService(PaystackClient paystack) {
        this.paystack = paystack;
    }

    public List<BankOption> banks() {
        if (!cached.isEmpty() && fetchedAt.plus(TTL).isAfter(Instant.now())) {
            return cached;
        }
        synchronized (this) {
            if (!cached.isEmpty() && fetchedAt.plus(TTL).isAfter(Instant.now())) {
                return cached;
            }
            try {
                List<BankOption> fresh = paystack.listBanks("south africa", true).stream()
                        .filter(b -> b.name() != null && b.code() != null)
                        .map(b -> new BankOption(b.name(), b.code()))
                        .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                        .toList();
                if (fresh.isEmpty()) {
                    // An empty list is not a list; keep whatever was cached
                    // (possibly stale) rather than offer a picker with nothing.
                    log.warn("Paystack returned no verification-enabled SA banks; keeping cached list of {}",
                            cached.size());
                    if (cached.isEmpty()) {
                        throw new BankListUnavailableException(null);
                    }
                    return cached;
                }
                cached = fresh;
                fetchedAt = Instant.now();
                log.info("Bank list refreshed from Paystack: {} banks", fresh.size());
                return fresh;
            } catch (PaystackUnavailableException e) {
                if (!cached.isEmpty()) {
                    log.warn("Paystack bank list unavailable; serving the cached list of {} (fetched {})",
                            cached.size(), fetchedAt);
                    return cached;
                }
                throw new BankListUnavailableException(e);
            }
        }
    }

    /** Test seam: forget the cache so the next call goes upstream. */
    void evict() {
        cached = List.of();
        fetchedAt = Instant.EPOCH;
    }

    /** Test seam: expire the TTL but keep the entries, to exercise stale-on-failure. */
    void evictTtlOnly() {
        fetchedAt = Instant.EPOCH;
    }
}

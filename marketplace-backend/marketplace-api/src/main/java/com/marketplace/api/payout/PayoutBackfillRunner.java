package com.marketplace.api.payout;

import com.marketplace.api.entity.OrderStatus;
import com.marketplace.api.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * One-time catch-up for orders that went PAID before the ledger existed
 * (vendor-payouts.md Tier 0: money kept, debt unrecorded).
 *
 * DRY-RUN BY DEFAULT. Every boot it lists what it would write and writes
 * nothing; setting PAYOUT_BACKFILL_COMMIT=true for ONE deploy commits the
 * entries, after which the work list is empty and the runner logs a quiet
 * zero forever. Commit-once-then-unset is the intended ceremony — writing
 * money rows from historical data should be a decision someone made on
 * purpose, with the dry-run log in front of them.
 *
 * Scope: PAID, SHIPPED, DELIVERED — states where the money was kept.
 * REFUNDED orders are skipped entirely: the money went back, and writing
 * entries only to void them in the same breath is noise, not history. (No
 * clawbacks are owed either — nothing was ever paid out under Tier 0.)
 *
 * Rates: entries are snapshotted at TODAY'S resolved rate, because the rate
 * that "applied" historically was never defined — that is what Tier 0 means.
 * The dry-run log is where the operator sanity-checks that before committing.
 *
 * Each order commits in its OWN transaction (recordOnPaidById), so one bad
 * historical order skips loudly instead of rolling back the whole batch —
 * the same per-item isolation the expiry job uses.
 */
@Component
public class PayoutBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PayoutBackfillRunner.class);

    private static final EnumSet<OrderStatus> MONEY_KEPT =
            EnumSet.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED);

    private final OrderRepository orderRepository;
    private final CommissionLedgerService ledger;
    private final boolean commit;

    public PayoutBackfillRunner(OrderRepository orderRepository,
                                CommissionLedgerService ledger,
                                @Value("${app.payouts.backfill-commit:false}") boolean commit) {
        this.orderRepository = orderRepository;
        this.ledger = ledger;
        this.commit = commit;
    }

    @Override
    public void run(ApplicationArguments args) {
        MDC.put("requestId", "job-payout-backfill-" + UUID.randomUUID());
        try {
            List<Long> missing = orderRepository.findIdsMissingPayoutEntries(MONEY_KEPT);
            if (missing.isEmpty()) {
                log.info("Payout backfill: ledger is complete, nothing to do");
                return;
            }

            if (!commit) {
                log.warn("Payout backfill DRY RUN: {} order(s) have no ledger entries. "
                        + "Set PAYOUT_BACKFILL_COMMIT=true for one deploy to write them.",
                        missing.size());
                for (Long orderId : missing) {
                    for (String line : ledger.previewById(orderId)) {
                        log.warn("Payout backfill would write: {}", line);
                    }
                }
                return;
            }

            log.warn("Payout backfill COMMITTING entries for {} order(s)", missing.size());
            int written = 0, failed = 0;
            for (Long orderId : missing) {
                try {
                    ledger.recordOnPaidById(orderId);
                    written++;
                } catch (Exception e) {
                    // Loud skip, not batch abort: the remaining orders' debts
                    // still deserve recording even if one is malformed.
                    failed++;
                    log.error("Payout backfill failed for order {}", orderId, e);
                }
            }
            log.warn("Payout backfill done: {} order(s) written, {} failed. "
                    + "Unset PAYOUT_BACKFILL_COMMIT now.", written, failed);
        } finally {
            MDC.remove("requestId");
        }
    }
}

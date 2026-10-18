package com.marketplace.api.entity;

/**
 * Which rail settles a ledger entry (paystack-subaccounts.md §6.2).
 * Lockstep with vendor_payout_entries_settlement_mode_check in V38.
 *
 * EFT: the collect-then-pay path (batch, bank file, human upload). Every
 * entry written before the Paystack split exists is EFT, by column default.
 * SPLIT: Paystack paid the vendor's share at the transaction; the entry
 * records that, and the payout run never touches it.
 */
public enum SettlementMode {
    EFT,
    SPLIT
}

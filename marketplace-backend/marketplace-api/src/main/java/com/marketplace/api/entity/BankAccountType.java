package com.marketplace.api.entity;

/**
 * South African bank account types, as EFT bulk files classify them.
 * Lockstep with users_account_type_check in V28 (the V7 discipline).
 */
public enum BankAccountType {
    CHEQUE,
    SAVINGS
}

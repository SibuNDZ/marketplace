package com.marketplace.api.entity;

/**
 * What a {@link UserToken} authorises. Persisted as a string and mirrored by
 * a CHECK constraint in V13 — adding a value here without adding it there
 * fails on insert, which is the intended loud failure.
 */
public enum TokenPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RESET
}

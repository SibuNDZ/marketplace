package com.marketplace.api.entity;

/**
 * How a seller says they found eRestyu (V33).
 *
 * Stored as the enum NAME, so this list is the schema for the only
 * attribution signal the platform has. Two rules follow from that:
 *
 *   1. Never rename a constant. The name is in the database; renaming it
 *      orphans every row that holds the old spelling and Hibernate will fail
 *      to read them back. Add a new one instead.
 *   2. Keep the list short. A long dropdown gets answered carelessly, and
 *      ten channels that each collect two vendors tell you nothing. These are
 *      the channels actually in use or plausibly next; anything else is OTHER
 *      with a free-text label, which is how a new channel announces itself
 *      before it earns a constant.
 */
public enum ReferralSource {

    FACEBOOK,
    TIKTOK,
    INSTAGRAM,
    /** A group, a broadcast, or a message from someone who already sells. */
    WHATSAPP,
    /** Google or any other search engine. */
    SEARCH,
    /** Word of mouth: a friend, family, or another vendor. */
    FRIEND,
    /** A physical market, expo, or community event. */
    EVENT,
    /** Anything else. Pairs with referral_source_detail. */
    OTHER;

    /**
     * Parses a wire value, tolerating case and surrounding space, and
     * rejecting anything unknown by returning null rather than throwing.
     *
     * A bad value here is a client sending a channel we do not know about,
     * which must not cost someone their registration: losing the attribution
     * for one signup is a rounding error, losing the signup is not. The
     * caller treats null as "no answer".
     */
    public static ReferralSource parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

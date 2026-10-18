package com.marketplace.api.payout;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.marketplace.api.entity.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The identity document a vendor supplies with their banking details
 * (paystack-subaccounts.md §4.1, §4.2). The container the number travels
 * in, built so the number cannot leak by accident:
 *
 * - toString() prints the type and the LAST FOUR only, so a logged request
 *   or exception message never carries the number;
 * - the number is WRITE_ONLY for Jackson, so no response, error body or
 *   serialised copy ever includes it;
 * - last4() is the only thing the server keeps (vendor_verifications
 *   .document_last4). The full number is dropped after that, and in this
 *   slice nothing reads it at all: the provider validate call is not built.
 *
 * Length and shape are sanity rails, not document validation: an SA ID is
 * 13 digits, a passport is alphanumeric, a CIPC number is like
 * 2026/123456/07. The provider is the only real validator.
 */
public record IdentityDocumentRequest(
        @NotNull DocumentType type,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank
        @Size(min = 4, max = 40)
        @Pattern(regexp = "[A-Za-z0-9/\\- ]+", message = "Document number may contain letters, digits, / and -")
        String number
) {

    /** The only part of the number the server keeps. */
    public String last4() {
        return last4(number);
    }

    public static String last4(String number) {
        String s = number == null ? "" : number.strip();
        return s.length() <= 4 ? s : s.substring(s.length() - 4);
    }

    @Override
    public String toString() {
        return "IdentityDocumentRequest[type=" + type + ", last4=" + last4() + "]";
    }
}

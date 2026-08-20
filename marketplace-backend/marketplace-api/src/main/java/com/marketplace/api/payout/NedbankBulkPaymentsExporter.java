package com.marketplace.api.payout;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Renders a payout batch as a Nedbank NetBank bulk-payment CSV.
 *
 * ⚠ THE REAL NETBANK PROFILE FORMAT IS UNCONFIRMED (owner action in
 * vendor-payouts.md). The default below — Name,AccountNumber,BranchCode,
 * Amount,Reference — is a sane guess at a bulk EFT layout, and THE POINT of
 * this class is that it is the ONLY file that knows the column order,
 * header, and field formatting. When the operator downloads the real profile
 * spec from NetBank, matching it is an edit here and nowhere else.
 *
 * The file intentionally carries the FULL account number: the bank needs it
 * to move money. Masking (last 4) applies to API responses and UI surfaces,
 * never to the bank file itself — which is exactly why the export endpoint
 * is admin-gated and the file is generated on demand rather than stored.
 */
@Component
public class NedbankBulkPaymentsExporter {

    /** One vendor's payment line, already summed by the caller. */
    public record PayoutLine(
            String accountHolderName,
            String accountNumber,
            String branchCode,
            BigDecimal amount,
            String reference
    ) {}

    private static final String HEADER = "Name,AccountNumber,BranchCode,Amount,Reference";

    public String export(List<PayoutLine> lines) {
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        for (PayoutLine line : lines) {
            csv.append(field(line.accountHolderName())).append(',')
               .append(field(line.accountNumber())).append(',')
               .append(field(line.branchCode())).append(',')
               // Plain 2dp, no thousands separators — bank imports are the
               // last place to be locale-clever. HALF_UP is a formality:
               // every ledger amount is already exact cents, so it never
               // rounds — it just avoids setScale's default-throw path.
               .append(line.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()).append(',')
               .append(field(line.reference())).append("\r\n");
        }
        return csv.toString();
    }

    /** Minimal CSV escaping: quote when the value contains a delimiter. */
    private static String field(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}

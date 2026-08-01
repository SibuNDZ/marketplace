package com.marketplace.api.payment;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * PayFast's MD5-plus-passphrase signature scheme, both directions. The rules
 * come from developers.payfast.co.za (fetched 2026-08-01) and carry two
 * asymmetries that are THE known integration footguns:
 *
 * 1. ORDERING. Request signatures use the DOCUMENTED attribute order (the
 *    order fields are added to the map — hence SequencedMap in, never a
 *    HashMap). ITN verification uses the ORDER RECEIVED on the wire, all
 *    fields up to but excluding "signature". Alphabetical ordering is the
 *    API-signature format and is wrong for both of these.
 *
 * 2. BLANKS. Request signing SKIPS blank values; ITN verification INCLUDES
 *    them (PayFast posts empty fields and signs them as name=).
 *
 * Encoding: uppercase percent-encoding with spaces as '+', which is exactly
 * what java.net.URLEncoder produces. MD5 is fine here: it is a shared-secret
 * MAC construction prescribed by the provider, not a collision-resistant
 * digest we chose.
 */
final class PayfastSignature {

    private PayfastSignature() {}

    /** Request signature: documented field order, blanks skipped. */
    static String sign(SequencedMap<String, String> fields, String passphrase) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (!sb.isEmpty()) sb.append('&');
            sb.append(e.getKey()).append('=').append(encode(e.getValue()));
        }
        return md5(withPassphrase(sb, passphrase));
    }

    /**
     * ITN verification: fields in the order received, INCLUDING blanks,
     * stopping before "signature" (PayFast's own reference implementation
     * breaks at that key rather than filtering it, so anything after it is
     * excluded too).
     */
    static boolean verifyItn(List<Map.Entry<String, String>> orderedParams,
                             String receivedSignature, String passphrase) {
        if (receivedSignature == null || receivedSignature.isBlank()) return false;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : orderedParams) {
            if ("signature".equals(e.getKey())) break;
            if (!sb.isEmpty()) sb.append('&');
            sb.append(e.getKey()).append('=').append(encode(e.getValue() == null ? "" : e.getValue()));
        }
        return md5(withPassphrase(sb, passphrase)).equals(receivedSignature);
    }

    /**
     * Parses a raw application/x-www-form-urlencoded body preserving wire
     * order. Servlet parameter maps do not guarantee order, and order is
     * load-bearing for the signature, so the controller hands us the raw
     * body instead.
     */
    static List<Map.Entry<String, String>> parseOrdered(String rawBody) {
        List<Map.Entry<String, String>> params = new ArrayList<>();
        if (rawBody == null || rawBody.isBlank()) return params;
        for (String pair : rawBody.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.add(new AbstractMap.SimpleEntry<>(
                    URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8)));
        }
        return params;
    }

    static String encode(String value) {
        return URLEncoder.encode(value.trim(), StandardCharsets.UTF_8);
    }

    private static String withPassphrase(StringBuilder paramString, String passphrase) {
        if (passphrase == null || passphrase.isBlank()) return paramString.toString();
        return paramString + "&passphrase=" + encode(passphrase);
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e); // JDK-guaranteed algorithm
        }
    }
}

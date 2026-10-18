package com.marketplace.api.payment;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketplace.api.payment.PaystackExceptions.PaystackRejectedException;
import com.marketplace.api.payment.PaystackExceptions.PaystackUnavailableException;
import com.marketplace.api.payout.IdentityDocumentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The Paystack calls the subaccount rollout needs (paystack-subaccounts.md
 * §3): bank list, account validation, subaccount create / update / fetch.
 * Built the way {@link PaystackCheckoutService} does its initialize call
 * (java.net.http, bearer header, Jackson), so that service can later move
 * onto this class.
 *
 * Fault isolation, the same for every call: 5s connect / 10s read timeout,
 * ONE retry on a connect failure only (the request never arrived, so a
 * retry cannot double anything), and every failure is typed:
 * {@link PaystackUnavailableException} for unreachable / timeout / 5xx,
 * {@link PaystackRejectedException} for 4xx with Paystack's message.
 *
 * Secrets: the bearer key is held here and in the checkout service, nowhere
 * else. Request bodies are built by hand from the request records, never by
 * serialising them, so the records can keep their sensitive fields
 * WRITE_ONLY for Jackson and last-4 in toString(); a logged record shows no
 * account or document number. Paystack responses are logged at DEBUG only,
 * with any account_number field redacted first.
 */
@Service
public class PaystackClient {

    private static final Logger log = LoggerFactory.getLogger(PaystackClient.class);

    // ── request / response records ──────────────────────────────────────

    public record PaystackBank(String name, String code, String slug) {}

    /**
     * POST /bank/validate. Account and document numbers are WRITE_ONLY and
     * print as last four; the body is assembled from the accessors.
     */
    public record ValidateRequest(
            String accountName,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String accountNumber,
            String accountType,      // "personal" | "business"
            String bankCode,
            String countryCode,      // "ZA"
            String documentType,     // identityNumber | passportNumber | businessRegistrationNumber | null
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String documentNumber
    ) {
        @Override
        public String toString() {
            return "ValidateRequest[accountName=" + accountName
                    + ", accountLast4=" + IdentityDocumentRequest.last4(accountNumber)
                    + ", accountType=" + accountType + ", bankCode=" + bankCode
                    + ", countryCode=" + countryCode + ", documentType=" + documentType
                    + ", documentLast4=" + IdentityDocumentRequest.last4(documentNumber) + "]";
        }
    }

    /** Flags are null when Paystack's response did not carry them. */
    public record ValidateResult(
            boolean verified,
            Boolean accountHolderMatch,
            Boolean accountOpen,
            Boolean accountOpenForMoreThanThreeMonths,
            Boolean accountAcceptsCredits,
            String message
    ) {}

    public record CreateSubaccountRequest(
            String businessName,
            String bankCode,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String accountNumber,
            int percentageCharge,
            String primaryContactEmail,
            String description,
            Long vendorId
    ) {
        @Override
        public String toString() {
            return "CreateSubaccountRequest[businessName=" + businessName + ", bankCode=" + bankCode
                    + ", accountLast4=" + IdentityDocumentRequest.last4(accountNumber)
                    + ", percentageCharge=" + percentageCharge + ", vendorId=" + vendorId + "]";
        }
    }

    public record UpdateSubaccountRequest(
            String businessName,
            String bankCode,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String accountNumber
    ) {
        @Override
        public String toString() {
            return "UpdateSubaccountRequest[businessName=" + businessName + ", bankCode=" + bankCode
                    + ", accountLast4=" + IdentityDocumentRequest.last4(accountNumber) + "]";
        }
    }

    public record Subaccount(String code, boolean isVerified, String settlementSchedule) {}

    // ── wiring ──────────────────────────────────────────────────────────

    /** Default per spec §3: the docs' table says bank_code, the sample says settlement_bank. */
    static final String BANK_FIELD_PRIMARY = "bank_code";
    static final String BANK_FIELD_FALLBACK = "settlement_bank";

    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final URI baseUrl;
    private final String secretKey;
    private final Duration readTimeout;

    @Autowired
    public PaystackClient(ObjectMapper objectMapper,
                          @Value("${app.paystack.api-base-url:https://api.paystack.co}") String apiBaseUrl,
                          @Value("${app.paystack.secret-key:}") String secretKey) {
        this(objectMapper, apiBaseUrl, secretKey, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    /** Timeouts injectable for tests; production uses the 5s / 10s defaults. */
    PaystackClient(ObjectMapper objectMapper, String apiBaseUrl, String secretKey,
                   Duration connectTimeout, Duration readTimeout) {
        this.objectMapper = objectMapper;
        this.baseUrl = URI.create(apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl);
        this.secretKey = secretKey.trim();
        this.readTimeout = readTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    // ── calls ───────────────────────────────────────────────────────────

    /** GET /bank?country=...&enabled_for_verification=... */
    public List<PaystackBank> listBanks(String country, boolean enabledForVerification) {
        String query = "country=" + URLEncoder.encode(country, StandardCharsets.UTF_8)
                + "&enabled_for_verification=" + enabledForVerification + "&perPage=100";
        JsonNode data = call("GET", "/bank?" + query, null, "list banks");
        List<PaystackBank> banks = new ArrayList<>();
        for (JsonNode b : data) {
            banks.add(new PaystackBank(b.path("name").asText(null), b.path("code").asText(null),
                    b.path("slug").asText(null)));
        }
        return banks;
    }

    /** POST /bank/validate (South Africa). document_* omitted when the request has no document. */
    public ValidateResult validateAccount(ValidateRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("account_name", request.accountName());
        body.put("account_number", request.accountNumber());
        body.put("account_type", request.accountType());
        body.put("bank_code", request.bankCode());
        body.put("country_code", request.countryCode());
        if (request.documentType() != null) {
            body.put("document_type", request.documentType());
            if (request.documentNumber() != null) {
                body.put("document_number", request.documentNumber());
            }
        }
        JsonNode data = call("POST", "/bank/validate", body, "validate account");
        return new ValidateResult(
                data.path("verified").asBoolean(false),
                flag(data, "accountHolderMatch"),
                flag(data, "accountOpen"),
                flag(data, "accountOpenForMoreThanThreeMonths"),
                flag(data, "accountAcceptsCredits"),
                data.path("verificationMessage").asText(null));
    }

    /**
     * POST /subaccount. FIND OUT A1: sends {@code bank_code} first; if
     * Paystack rejects that field by name, resends with
     * {@code settlement_bank} and logs which one it accepted, so the test
     * run records the answer rather than the code guessing it.
     */
    public Subaccount createSubaccount(CreateSubaccountRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("business_name", request.businessName());
        body.put("account_number", request.accountNumber());
        body.put("percentage_charge", request.percentageCharge());
        if (request.primaryContactEmail() != null) {
            body.put("primary_contact_email", request.primaryContactEmail());
        }
        if (request.description() != null) {
            body.put("description", request.description());
        }
        body.putObject("metadata").put("vendorId", request.vendorId());
        JsonNode data = withBankField(body, request.bankCode(), "/subaccount", "POST", "create subaccount");
        return subaccount(data);
    }

    /** PUT /subaccount/{code}. Same bank-field fallback as create. */
    public Subaccount updateSubaccount(String code, UpdateSubaccountRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("business_name", request.businessName());
        body.put("account_number", request.accountNumber());
        JsonNode data = withBankField(body, request.bankCode(), "/subaccount/" + enc(code), "PUT",
                "update subaccount " + code);
        return subaccount(data);
    }

    /** GET /subaccount/{code}: is_verified is the dashboard verification the owner does by hand. */
    public Subaccount fetchSubaccount(String code) {
        return subaccount(call("GET", "/subaccount/" + enc(code), null, "fetch subaccount " + code));
    }

    // ── internals ───────────────────────────────────────────────────────

    private JsonNode withBankField(ObjectNode body, String bankCode, String path, String method, String what) {
        body.put(BANK_FIELD_PRIMARY, bankCode);
        try {
            return call(method, path, body, what);
        } catch (PaystackRejectedException primaryRejected) {
            String msg = primaryRejected.getMessage() == null ? "" : primaryRejected.getMessage().toLowerCase();
            if (!(msg.contains(BANK_FIELD_FALLBACK) || msg.contains("bank"))) {
                throw primaryRejected;
            }
            log.info("Paystack rejected {} with '{}' ({}); retrying with {} [FIND OUT A1]",
                    BANK_FIELD_PRIMARY, primaryRejected.getMessage(), what, BANK_FIELD_FALLBACK);
            body.remove(BANK_FIELD_PRIMARY);
            body.put(BANK_FIELD_FALLBACK, bankCode);
            JsonNode data = call(method, path, body, what);
            log.info("Paystack accepted {} for {} [FIND OUT A1: record in the spec]", BANK_FIELD_FALLBACK, what);
            return data;
        }
    }

    private static Subaccount subaccount(JsonNode data) {
        return new Subaccount(
                data.path("subaccount_code").asText(null),
                data.path("is_verified").asBoolean(false),
                data.path("settlement_schedule").asText(null));
    }

    private static Boolean flag(JsonNode data, String field) {
        JsonNode n = data.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asBoolean();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * One call, typed outcome. Returns the response's {@code data} node.
     * {@code what} names the operation for logs and exception messages and
     * must never carry a number.
     */
    private JsonNode call(String method, String path, ObjectNode body, String what) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(readTimeout)
                .header("Authorization", "Bearer " + secretKey)
                .header("Accept", "application/json");
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            String json;
            try {
                json = objectMapper.writeValueAsString(body);
            } catch (Exception e) {
                throw new PaystackUnavailableException("Failed to serialise Paystack request (" + what + ")", e);
            }
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        }
        HttpRequest request = b.build();

        HttpResponse<String> response;
        try {
            response = send(request);
        } catch (ConnectException | HttpConnectTimeoutException neverArrived) {
            log.warn("Paystack connect failure ({}), retrying once", what);
            try {
                response = send(request);
            } catch (Exception retryFailed) {
                throw new PaystackUnavailableException("Failed to reach Paystack (" + what + ")", retryFailed);
            }
        } catch (HttpTimeoutException timedOut) {
            throw new PaystackUnavailableException("Paystack timed out (" + what + ")", timedOut);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaystackUnavailableException("Interrupted calling Paystack (" + what + ")", e);
        } catch (Exception e) {
            throw new PaystackUnavailableException("Failed calling Paystack (" + what + ")", e);
        }

        int status = response.statusCode();
        if (log.isDebugEnabled()) {
            log.debug("Paystack {} {} -> HTTP {} body {}", method, path, status, redact(response.body()));
        }
        if (status >= 500) {
            throw new PaystackUnavailableException(
                    "Paystack returned HTTP " + status + " (" + what + ")", null);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response.body() == null ? "" : response.body());
        } catch (Exception e) {
            if (status / 100 == 2) {
                throw new PaystackUnavailableException("Unparseable Paystack response (" + what + ")", e);
            }
            root = objectMapper.createObjectNode();
        }
        if (status / 100 != 2) {
            String message = root.path("message").asText("HTTP " + status);
            log.warn("Paystack rejected {}: HTTP {} '{}'", what, status, message);
            throw new PaystackRejectedException(status, message);
        }
        if (!root.path("status").asBoolean(true)) {
            // 2xx with status:false is Paystack's shape for a declined request.
            throw new PaystackRejectedException(status, root.path("message").asText("Paystack declined the request"));
        }
        return root.path("data");
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Masks any account_number value in a response body before it is logged. */
    static String redact(String body) {
        if (body == null) return null;
        return body.replaceAll("(\"account_number\"\\s*:\\s*\")[^\"]*(\")", "$1***$2");
    }

    /** Spec §4.1 mapping from our document types to Paystack's parameter values. */
    public static String documentTypeParam(com.marketplace.api.entity.DocumentType type) {
        if (type == null) return null;
        return switch (type) {
            case ID_NUMBER -> "identityNumber";
            case PASSPORT -> "passportNumber";
            case CIPC -> "businessRegistrationNumber";
        };
    }

    /**
     * Spec §4.1: Paystack knows two account types, personal and business.
     * Ours (BankAccountType) are CHEQUE and SAVINGS, which say nothing about
     * who holds the account, so the document decides: a CIPC registration
     * number means a business account, anything else (or no document) a
     * personal one. The ticket's "PERSONAL-type account" does not exist.
     */
    public static String accountTypeParam(com.marketplace.api.entity.DocumentType documentType,
                                          com.marketplace.api.entity.BankAccountType accountType) {
        return documentType == com.marketplace.api.entity.DocumentType.CIPC ? "business" : "personal";
    }
}

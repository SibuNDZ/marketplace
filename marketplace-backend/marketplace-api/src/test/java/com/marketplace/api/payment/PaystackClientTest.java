package com.marketplace.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.DocumentType;
import com.marketplace.api.payment.PaystackClient.CreateSubaccountRequest;
import com.marketplace.api.payment.PaystackClient.PaystackBank;
import com.marketplace.api.payment.PaystackClient.Subaccount;
import com.marketplace.api.payment.PaystackClient.UpdateSubaccountRequest;
import com.marketplace.api.payment.PaystackClient.ValidateRequest;
import com.marketplace.api.payment.PaystackClient.ValidateResult;
import com.marketplace.api.payment.PaystackExceptions.PaystackRejectedException;
import com.marketplace.api.payment.PaystackExceptions.PaystackUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PaystackClient against a local stub: what goes on the wire, what comes
 * back parsed, and how each failure class is typed. No Spring context.
 */
class PaystackClientTest {

    static HttpServer stub;
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicReference<String> RESPONSE = new AtomicReference<>("{}");
    static final AtomicReference<Function<Integer, String>> RESPONSE_BY_CALL = new AtomicReference<>();
    static final AtomicInteger DELAY_MS = new AtomicInteger(0);
    static final List<String> METHODS = new ArrayList<>();
    static final List<String> PATHS = new ArrayList<>();
    static final List<String> BODIES = new ArrayList<>();
    static final AtomicReference<String> AUTH = new AtomicReference<>();

    static final String ACCOUNT = "1122336789";
    static final String ID_NUMBER = "9001015009087";

    PaystackClient client;
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void start() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            synchronized (METHODS) {
                METHODS.add(exchange.getRequestMethod());
                PATHS.add(exchange.getRequestURI().toString());
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try { Thread.sleep(DELAY_MS.get()); } catch (InterruptedException ignored) { }
            Function<Integer, String> byCall = RESPONSE_BY_CALL.get();
            String body = byCall != null ? byCall.apply(BODIES.size()) : RESPONSE.get();
            int status = byCall != null && body.startsWith("4") ? Integer.parseInt(body.substring(0, 3)) : STATUS.get();
            if (byCall != null && body.startsWith("4")) body = body.substring(3);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        stub.start();
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
    }

    @BeforeEach
    void reset() {
        STATUS.set(200);
        RESPONSE.set("{}");
        RESPONSE_BY_CALL.set(null);
        DELAY_MS.set(0);
        synchronized (METHODS) { METHODS.clear(); PATHS.clear(); BODIES.clear(); }
        client = new PaystackClient(mapper, "http://127.0.0.1:" + stub.getAddress().getPort(),
                "sk_test_stub", Duration.ofSeconds(2), Duration.ofMillis(800));
    }

    private JsonNode lastBody() throws Exception {
        return mapper.readTree(BODIES.get(BODIES.size() - 1));
    }

    // ── serialisation safety ─────────────────────────────────────────────

    @Test
    @DisplayName("request records print and serialise without the account or document number, yet the wire body carries both")
    void recordsKeepNumbersOffLogsButOnTheWire() throws Exception {
        ValidateRequest req = new ValidateRequest("Thandi Dlamini", ACCOUNT, "personal", "198765", "ZA",
                "identityNumber", ID_NUMBER);
        assertThat(req.toString()).doesNotContain(ACCOUNT).doesNotContain(ID_NUMBER)
                .contains("accountLast4=6789").contains("documentLast4=9087");
        assertThat(mapper.writeValueAsString(req)).doesNotContain(ACCOUNT).doesNotContain(ID_NUMBER);

        CreateSubaccountRequest create = new CreateSubaccountRequest("Cavioure", "198765", ACCOUNT, 10,
                "v@test.local", "eRestyu vendor 7", 7L);
        assertThat(create.toString()).doesNotContain(ACCOUNT).contains("accountLast4=6789");
        assertThat(mapper.writeValueAsString(create)).doesNotContain(ACCOUNT);
        assertThat(new UpdateSubaccountRequest("Cavioure", "198765", ACCOUNT).toString()).doesNotContain(ACCOUNT);

        RESPONSE.set("{\"status\":true,\"message\":\"ok\",\"data\":{\"verified\":true,"
                + "\"verificationMessage\":\"Personal Account Verification successful\","
                + "\"accountHolderMatch\":true,\"accountOpen\":true,"
                + "\"accountOpenForMoreThanThreeMonths\":false,\"accountAcceptsCredits\":true}}");
        ValidateResult result = client.validateAccount(req);

        JsonNode sent = lastBody();
        assertThat(sent.get("account_number").asText()).isEqualTo(ACCOUNT);
        assertThat(sent.get("document_number").asText()).isEqualTo(ID_NUMBER);
        assertThat(sent.get("document_type").asText()).isEqualTo("identityNumber");
        assertThat(sent.get("account_type").asText()).isEqualTo("personal");
        assertThat(sent.get("country_code").asText()).isEqualTo("ZA");
        assertThat(PATHS.get(0)).isEqualTo("/bank/validate");
        assertThat(AUTH.get()).isEqualTo("Bearer sk_test_stub");

        assertThat(result.verified()).isTrue();
        assertThat(result.accountHolderMatch()).isTrue();
        assertThat(result.accountOpenForMoreThanThreeMonths()).isFalse();
        assertThat(result.accountAcceptsCredits()).isTrue();
        assertThat(result.message()).contains("successful");
    }

    @Test
    @DisplayName("validate without a document sends no document_* fields, and absent flags parse as null")
    void validateWithoutDocument() throws Exception {
        RESPONSE.set("{\"status\":true,\"data\":{\"verified\":false,\"verificationMessage\":\"no match\"}}");
        ValidateResult result = client.validateAccount(
                new ValidateRequest("X", ACCOUNT, "business", "198765", "ZA", null, null));
        assertThat(lastBody().has("document_type")).isFalse();
        assertThat(lastBody().has("document_number")).isFalse();
        assertThat(result.verified()).isFalse();
        assertThat(result.accountHolderMatch()).isNull();
        assertThat(result.accountOpen()).isNull();
    }

    // ── parsing ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("listBanks parses name, code and slug and asks for the verification-enabled SA list")
    void listBanks() {
        RESPONSE.set("{\"status\":true,\"data\":[{\"name\":\"Nedbank\",\"code\":\"198765\",\"slug\":\"nedbank\"},"
                + "{\"name\":\"Capitec Bank\",\"code\":\"470010\",\"slug\":\"capitec-bank\"}]}");
        List<PaystackBank> banks = client.listBanks("south africa", true);
        assertThat(banks).containsExactly(
                new PaystackBank("Nedbank", "198765", "nedbank"),
                new PaystackBank("Capitec Bank", "470010", "capitec-bank"));
        assertThat(METHODS.get(0)).isEqualTo("GET");
        assertThat(PATHS.get(0)).startsWith("/bank?country=south+africa&enabled_for_verification=true");
    }

    @Test
    @DisplayName("createSubaccount sends the spec's fields with bank_code and parses the subaccount")
    void createSubaccount() throws Exception {
        RESPONSE.set("{\"status\":true,\"data\":{\"subaccount_code\":\"ACCT_abc\",\"is_verified\":false,"
                + "\"settlement_schedule\":\"AUTO\"}}");
        Subaccount sub = client.createSubaccount(new CreateSubaccountRequest(
                "Cavioure Designers", "198765", ACCOUNT, 10, "v@test.local", "eRestyu vendor 7", 7L));
        assertThat(sub).isEqualTo(new Subaccount("ACCT_abc", false, "AUTO"));
        JsonNode sent = lastBody();
        assertThat(sent.get("business_name").asText()).isEqualTo("Cavioure Designers");
        assertThat(sent.get("bank_code").asText()).isEqualTo("198765");
        assertThat(sent.has("settlement_bank")).isFalse();
        assertThat(sent.get("account_number").asText()).isEqualTo(ACCOUNT);
        assertThat(sent.get("percentage_charge").asInt()).isEqualTo(10);
        assertThat(sent.get("primary_contact_email").asText()).isEqualTo("v@test.local");
        assertThat(sent.get("description").asText()).isEqualTo("eRestyu vendor 7");
        assertThat(sent.path("metadata").get("vendorId").asLong()).isEqualTo(7L);
        assertThat(METHODS.get(0)).isEqualTo("POST");
        assertThat(PATHS.get(0)).isEqualTo("/subaccount");
    }

    @Test
    @DisplayName("FIND OUT A1: when bank_code is rejected by name, the call is resent once with settlement_bank")
    void bankFieldFallback() throws Exception {
        RESPONSE_BY_CALL.set(n -> n == 1
                ? "400{\"status\":false,\"message\":\"settlement_bank is required\"}"
                : "{\"status\":true,\"data\":{\"subaccount_code\":\"ACCT_fb\",\"is_verified\":false}}");
        Subaccount sub = client.createSubaccount(new CreateSubaccountRequest(
                "S", "198765", ACCOUNT, 10, null, null, 1L));
        assertThat(sub.code()).isEqualTo("ACCT_fb");
        assertThat(BODIES).hasSize(2);
        assertThat(mapper.readTree(BODIES.get(0)).has("bank_code")).isTrue();
        JsonNode second = mapper.readTree(BODIES.get(1));
        assertThat(second.has("bank_code")).isFalse();
        assertThat(second.get("settlement_bank").asText()).isEqualTo("198765");
    }

    @Test
    @DisplayName("update and fetch hit /subaccount/{code} with PUT and GET")
    void updateAndFetch() throws Exception {
        RESPONSE.set("{\"status\":true,\"data\":{\"subaccount_code\":\"ACCT_x\",\"is_verified\":true,"
                + "\"settlement_schedule\":\"AUTO\"}}");
        Subaccount updated = client.updateSubaccount("ACCT_x", new UpdateSubaccountRequest("S", "470010", ACCOUNT));
        assertThat(updated.isVerified()).isTrue();
        assertThat(METHODS.get(0)).isEqualTo("PUT");
        assertThat(PATHS.get(0)).isEqualTo("/subaccount/ACCT_x");
        assertThat(lastBody().get("bank_code").asText()).isEqualTo("470010");

        Subaccount fetched = client.fetchSubaccount("ACCT_x");
        assertThat(fetched.code()).isEqualTo("ACCT_x");
        assertThat(METHODS.get(1)).isEqualTo("GET");
        assertThat(PATHS.get(1)).isEqualTo("/subaccount/ACCT_x");
    }

    // ── failure typing ───────────────────────────────────────────────────

    @Test
    @DisplayName("4xx is PaystackRejectedException carrying Paystack's message")
    void rejected() {
        STATUS.set(400);
        RESPONSE.set("{\"status\":false,\"message\":\"Invalid bank code\"}");
        assertThatThrownBy(() -> client.fetchSubaccount("ACCT_nope"))
                .isInstanceOf(PaystackRejectedException.class)
                .hasMessage("Invalid bank code");
        assertThat(BODIES).hasSize(1); // no retry on a rejection
    }

    @Test
    @DisplayName("5xx is PaystackUnavailableException, not retried")
    void serverError() {
        STATUS.set(503);
        RESPONSE.set("{\"status\":false,\"message\":\"down\"}");
        assertThatThrownBy(() -> client.listBanks("south africa", true))
                .isInstanceOf(PaystackUnavailableException.class);
        assertThat(BODIES).hasSize(1);
    }

    @Test
    @DisplayName("a read timeout is PaystackUnavailableException")
    void timeout() {
        DELAY_MS.set(1500); // read timeout is 800ms in this test
        assertThatThrownBy(() -> client.listBanks("south africa", true))
                .isInstanceOf(PaystackUnavailableException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("an unreachable host is retried once, then PaystackUnavailableException")
    void unreachable() {
        PaystackClient dead = new PaystackClient(mapper, "http://127.0.0.1:1", "sk_test_stub",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThatThrownBy(() -> dead.listBanks("south africa", true))
                .isInstanceOf(PaystackUnavailableException.class)
                .hasMessageContaining("reach");
    }

    @Test
    @DisplayName("2xx with status:false is a rejection too")
    void twoHundredButDeclined() {
        RESPONSE.set("{\"status\":false,\"message\":\"Subaccount not found\"}");
        assertThatThrownBy(() -> client.fetchSubaccount("ACCT_gone"))
                .isInstanceOf(PaystackRejectedException.class)
                .hasMessage("Subaccount not found");
    }

    // ── mappings and redaction ───────────────────────────────────────────

    @Test
    @DisplayName("document and account type parameters map as the spec says, and redaction masks account_number")
    void mappings() {
        assertThat(PaystackClient.documentTypeParam(DocumentType.ID_NUMBER)).isEqualTo("identityNumber");
        assertThat(PaystackClient.documentTypeParam(DocumentType.PASSPORT)).isEqualTo("passportNumber");
        assertThat(PaystackClient.documentTypeParam(DocumentType.CIPC)).isEqualTo("businessRegistrationNumber");
        assertThat(PaystackClient.documentTypeParam(null)).isNull();
        // BankAccountType is CHEQUE/SAVINGS; personal vs business comes from the document.
        assertThat(PaystackClient.accountTypeParam(DocumentType.CIPC, BankAccountType.CHEQUE)).isEqualTo("business");
        assertThat(PaystackClient.accountTypeParam(DocumentType.ID_NUMBER, BankAccountType.SAVINGS)).isEqualTo("personal");
        assertThat(PaystackClient.accountTypeParam(null, BankAccountType.CHEQUE)).isEqualTo("personal");
        assertThat(PaystackClient.redact("{\"account_number\":\"1122336789\",\"x\":1}"))
                .isEqualTo("{\"account_number\":\"***\",\"x\":1}");
    }
}

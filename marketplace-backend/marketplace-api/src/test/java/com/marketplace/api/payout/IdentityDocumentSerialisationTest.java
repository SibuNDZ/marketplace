package com.marketplace.api.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.api.dto.VendorSettingsDtos.BankingDetailsRequest;
import com.marketplace.api.entity.BankAccountType;
import com.marketplace.api.entity.DocumentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The document number is kept out of every serialised and printed form BY
 * CONSTRUCTION (paystack-subaccounts.md §4.2): this is the test the spec
 * asks for, in the shape of the User.toString() guard.
 */
class IdentityDocumentSerialisationTest {

    private static final String NUMBER = "9001015009087";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("the record serialises without its number, and reads it back in")
    void numberIsWriteOnly() throws Exception {
        IdentityDocumentRequest req = new IdentityDocumentRequest(DocumentType.ID_NUMBER, NUMBER);

        String json = mapper.writeValueAsString(req);
        assertThat(json).contains("ID_NUMBER").doesNotContain(NUMBER).doesNotContain("\"number\"");

        IdentityDocumentRequest read = mapper.readValue(
                "{\"type\":\"PASSPORT\",\"number\":\"A1234567\"}", IdentityDocumentRequest.class);
        assertThat(read.type()).isEqualTo(DocumentType.PASSPORT);
        assertThat(read.number()).isEqualTo("A1234567");
    }

    @Test
    @DisplayName("toString() is type and last four only")
    void toStringIsLast4() {
        IdentityDocumentRequest req = new IdentityDocumentRequest(DocumentType.CIPC, "2026/123456/07");
        assertThat(req.toString()).isEqualTo("IdentityDocumentRequest[type=CIPC, last4=6/07]");
        assertThat(req.toString()).doesNotContain("123456");
    }

    @Test
    @DisplayName("last4() keeps exactly the last four characters")
    void last4() {
        assertThat(IdentityDocumentRequest.last4(NUMBER)).isEqualTo("9087");
        assertThat(IdentityDocumentRequest.last4(" A1234567 ")).isEqualTo("4567");
        assertThat(IdentityDocumentRequest.last4("12")).isEqualTo("12");
        assertThat(IdentityDocumentRequest.last4(null)).isEmpty();
    }

    @Test
    @DisplayName("the full banking request serialises without the document number, and prints without the account number")
    void bankingRequestCarriesNeither() throws Exception {
        BankingDetailsRequest request = new BankingDetailsRequest(
                "Holder", "Nedbank", "1122336789", "198765", "198765", BankAccountType.CHEQUE,
                new IdentityDocumentRequest(DocumentType.ID_NUMBER, NUMBER));

        String json = mapper.writeValueAsString(request);
        assertThat(json).doesNotContain(NUMBER);

        String printed = request.toString();
        assertThat(printed).doesNotContain(NUMBER).doesNotContain("1122336789");
        assertThat(printed).contains("last4=9087");
    }
}

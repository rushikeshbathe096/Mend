package com.mend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 U6 resolver table. Payload shapes are doc-derived from Razorpay's payment entity
 * (https://razorpay.com/docs/api/payments/ — fields id, order_id, invoice_id); order_id on a payment is RECORDED (fixture 21).
 * AMBIGUOUS: no recorded payment.failed webhook, so whether failed subscription payments always carry invoice_id is unknown.
 */
@DisplayName("M2 U6: invoice key derivation")
class InvoiceKeyResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final UUID campaignId = UUID.randomUUID();

    private String key(String entityJson) {
        JsonNode root = MAPPER.readTree("{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":" + entityJson + "}}}");
        return InvoiceKeyResolver.fromPayload(root, campaignId);
    }

    @Test
    void invoiceIdWinsOverOrderAndPayment() {
        assertEquals("inv:inv_Abc123", key("{\"id\":\"pay_P1\",\"order_id\":\"order_O1\",\"invoice_id\":\"inv_Abc123\"}"));
    }

    @Test
    void orderIdWhenNoInvoice() {
        assertEquals("ord:order_Tk6XLmzgSWNi1r", key("{\"id\":\"pay_Tk6YGMVnG6oWIq\",\"order_id\":\"order_Tk6XLmzgSWNi1r\",\"invoice_id\":null}"));
    }

    @Test
    void paymentIdWhenNoOrderOrInvoice() {
        assertEquals("pay:pay_Tk6YGMVnG6oWIq", key("{\"id\":\"pay_Tk6YGMVnG6oWIq\",\"order_id\":null}"));
    }

    @Test
    void malformedIdsFallBackToTheCampaignInsteadOfTrustingThem() {
        String fallback = "cmp:" + campaignId;
        assertEquals(fallback, key("{\"id\":\"pay_P1\",\"invoice_id\":\"inv_bad id\"}"), "space");
        assertEquals(fallback, key("{\"id\":\"pay_P1\",\"order_id\":\"ord_123\"}"), "wrong prefix");
        assertEquals(fallback, key("{\"id\":\"pay_P1\",\"order_id\":\"order_x;DROP\"}"), "injection chars");
        assertEquals(fallback, key("{\"id\":\"plink_X\"}"), "not a payment id");
        assertEquals(fallback, key("{\"id\":\"pay_" + "a".repeat(61) + "\"}"), "too long");
    }

    @Test
    void nonStringOrBlankFieldsAreIgnored() {
        assertEquals("pay:pay_P1", key("{\"id\":\"pay_P1\",\"order_id\":12345,\"invoice_id\":\"  \"}"));
        assertEquals("cmp:" + campaignId, key("{\"id\":{\"nested\":true}}"));
    }

    @Test
    void missingEntityFallsBack() {
        assertEquals("cmp:" + campaignId, InvoiceKeyResolver.fromPayload(MAPPER.readTree("{\"payload\":{}}"), campaignId));
        assertEquals("cmp:" + campaignId, InvoiceKeyResolver.fromPayload(null, campaignId));
    }

    @Test
    void differentInvoicesNeverShareAKey() {
        assertNotEquals(key("{\"id\":\"pay_A\",\"order_id\":\"order_A\"}"), key("{\"id\":\"pay_B\",\"order_id\":\"order_B\"}"));
        assertNotEquals(key("{\"id\":\"pay_A\"}"), key("{\"id\":\"pay_B\"}"));
    }
}

package com.mend.taxonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 U2.1: one test per documented Razorpay reason (https://razorpay.com/docs/errors/payments/list/, fetched 2026-10-18).
 * The expected class is written here independently of the table, so a wrong table row fails.
 */
class FailureTaxonomyTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "insufficient_funds, INSUFFICIENT_FUNDS",
            "transaction_limit_exceeded, LIMIT_EXCEEDED",
            "transaction_daily_limit_exceeded, LIMIT_EXCEEDED",
            "transaction_daily_count_exceeded, LIMIT_EXCEEDED",
            "transaction_frequency_limit_exceeded, LIMIT_EXCEEDED",
            "credit_limit_exceeded, LIMIT_EXCEEDED",
            "mcc_amount_limit_exceeded, LIMIT_EXCEEDED",
            "bank_technical_error, BANK_TECHNICAL",
            "bank_not_available, BANK_TECHNICAL",
            "bank_cutoff_in_progress, BANK_TECHNICAL",
            "issuer_technical_error, BANK_TECHNICAL",
            "upi_app_technical_error, BANK_TECHNICAL",
            "psp_app_not_available, BANK_TECHNICAL",
            "psp_not_available, BANK_TECHNICAL",
            "gateway_technical_error, GATEWAY_ERROR",
            "invalid_response_from_gateway, GATEWAY_ERROR",
            "payment_declined_due_to_high_traffic, GATEWAY_ERROR",
            "request_timed_out, GATEWAY_ERROR",
            "server_error, GATEWAY_ERROR",
            "authentication_failed, AUTHENTICATION_FAILURE",
            "incorrect_otp, AUTHENTICATION_FAILURE",
            "incorrect_pin, AUTHENTICATION_FAILURE",
            "incorrect_atm_pin, AUTHENTICATION_FAILURE",
            "incorrect_cvv, AUTHENTICATION_FAILURE",
            "otp_attempts_exceeded, AUTHENTICATION_FAILURE",
            "otp_expired, AUTHENTICATION_FAILURE",
            "pin_attempts_exceeded, AUTHENTICATION_FAILURE",
            "payment_cancelled, CUSTOMER_DECLINED",
            "payment_timed_out, CUSTOMER_DECLINED",
            "card_expired, INSTRUMENT_INVALID",
            "debit_instrument_blocked, INSTRUMENT_INVALID",
            "debit_instrument_inactive, INSTRUMENT_INVALID",
            "mandate_creation_declined, MANDATE_INVALID",
            "mandate_creation_expired, MANDATE_INVALID",
            "mandate_creation_failed, MANDATE_INVALID",
            "mandate_creation_timeout, MANDATE_INVALID",
            "reqauth_mandate_not_acknowledged, MANDATE_INVALID",
    })
    void documentedReason(String reason, PaymentFailureClass expected) {
        FailureClassification c = FailureTaxonomy.classify(ProviderFailure.of("BAD_REQUEST_ERROR", reason, "customer"));
        assertEquals(expected, c.failureClass());
        assertEquals(FailureClassification.MatchedOn.REASON, c.matchedOn());
        assertEquals(reason, c.matchedValue());
        assertEquals(FailureTaxonomy.REASONS_DOC, c.documentation());
        assertEquals("razorpay-error-taxonomy-v1", c.tableVersion());
    }

    @Test
    @DisplayName("The table has exactly the 37 reasons tested above (no untested mapping)")
    void tableIsFullyTested() {
        assertEquals(37, FailureTaxonomy.reasonTable().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"card_declined", "payment_declined", "debit_declined", "payment_failed", "input_validation_failed",
            "invalid_amount", "authorisation_declined_by_psp", "payment_session_expired", "payment_collect_request_expired",
            "not_a_razorpay_reason"})
    @DisplayName("Unmapped reason -> UNCLASSIFIED whatever the source (never guessed, never overridden by the source)")
    void unmappedReasonIsUnclassified(String reason) {
        assertEquals(PaymentFailureClass.UNCLASSIFIED, FailureTaxonomy.classify(ProviderFailure.of(null, reason, "gateway")).failureClass());
        assertEquals(PaymentFailureClass.UNCLASSIFIED, FailureTaxonomy.classify(ProviderFailure.of(null, reason, "razorpay")).failureClass());
        assertEquals(PaymentFailureClass.UNCLASSIFIED, FailureTaxonomy.classify(ProviderFailure.of(null, reason, "customer")).failureClass());
        assertEquals(PaymentFailureClass.UNCLASSIFIED, FailureTaxonomy.classify(ProviderFailure.of(null, reason, "business")).failureClass());
        assertEquals(PaymentFailureClass.UNCLASSIFIED, FailureTaxonomy.classify(ProviderFailure.of(null, reason, null)).failureClass());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gateway", "razorpay", "GATEWAY"})
    @DisplayName("No reason, documented transient source -> GATEWAY_ERROR (matched on SOURCE)")
    void transientSourceFallback(String source) {
        FailureClassification c = FailureTaxonomy.classify(ProviderFailure.of("GATEWAY_ERROR", null, source));
        assertEquals(PaymentFailureClass.GATEWAY_ERROR, c.failureClass());
        assertEquals(FailureClassification.MatchedOn.SOURCE, c.matchedOn());
        assertEquals(PaymentFailureClass.GATEWAY_ERROR, FailureTaxonomy.classify(ProviderFailure.of(null, " ", source)).failureClass());
    }

    @Test
    @DisplayName("Nothing usable -> UNCLASSIFIED; error_code alone never decides; reason is case-insensitive and trimmed")
    void edgeCases() {
        assertEquals(PaymentFailureClass.UNCLASSIFIED, FailureTaxonomy.classify(null).failureClass());
        assertEquals(PaymentFailureClass.UNCLASSIFIED, FailureTaxonomy.classify(ProviderFailure.of(" ", "", null)).failureClass());
        FailureClassification codeOnly = FailureTaxonomy.classify(ProviderFailure.of("GATEWAY_ERROR", null, null));
        assertEquals(PaymentFailureClass.UNCLASSIFIED, codeOnly.failureClass());
        assertEquals(FailureClassification.MatchedOn.NONE, codeOnly.matchedOn());
        assertNull(codeOnly.documentation());
        assertEquals(PaymentFailureClass.INSUFFICIENT_FUNDS,
                FailureTaxonomy.classify(ProviderFailure.of(null, "  Insufficient_Funds ", null)).failureClass());
        // the canonical constructor normalises too
        assertEquals(PaymentFailureClass.INSUFFICIENT_FUNDS,
                FailureTaxonomy.classify(new ProviderFailure(null, " insufficient_funds", null)).failureClass());
        assertEquals(PaymentFailureClass.GATEWAY_ERROR, FailureTaxonomy.classify(new ProviderFailure(null, " ", "gateway")).failureClass());
        // only the documented transient sources decide; customer / business never do
        assertEquals(PaymentFailureClass.UNCLASSIFIED, FailureTaxonomy.classify(ProviderFailure.of(null, null, "customer")).failureClass());
        assertEquals(PaymentFailureClass.UNCLASSIFIED, FailureTaxonomy.classify(ProviderFailure.of(null, null, "business")).failureClass());
    }

    @Test
    @DisplayName("Doc-derived failed payment fixture (payment_cancelled / customer) -> CUSTOMER_DECLINED")
    void docDerivedFixture() throws Exception {
        JsonNode body = new ObjectMapper().readTree(Files.readString(
                Path.of("src/test/resources/razorpay/doc-derived/payment_failed_entity.json"))).path("response").path("body");
        ProviderFailure failure = ProviderFailure.of(body.path("error_code").asString(null), body.path("error_reason").asString(null),
                body.path("error_source").asString(null));
        assertEquals("payment_cancelled", failure.reason());
        assertEquals(PaymentFailureClass.CUSTOMER_DECLINED, FailureTaxonomy.classify(failure).failureClass());
    }
}

package com.mend.taxonomy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static com.mend.taxonomy.PaymentFailureClass.*;

/**
 * M3 U2.1: maps a failed Razorpay payment to a {@link PaymentFailureClass} from documented error fields only (ADR-003).
 *
 * <ol>
 *   <li>{@code error_reason}: Razorpay documents it as "the exact error reason. It can be handled programmatically"
 *       ({@value #ERRORS_DOC}); every row below is a reason listed at {@value #REASONS_DOC}.</li>
 *   <li>Only when there is no {@code error_reason}: {@code error_source} {@code gateway} ("usually transient") and
 *       {@code razorpay} ("retry after a short delay"), documented at {@value #REASONS_DOC} →
 *       {@link PaymentFailureClass#GATEWAY_ERROR}. A reason that is present but not in the table is never overridden
 *       by its source.</li>
 *   <li>Otherwise {@link PaymentFailureClass#UNCLASSIFIED}. {@code error_code} values are not enumerated in the docs, so
 *       the code never decides a class.</li>
 * </ol>
 * Deliberately unmapped (documented, but the docs do not say why it failed or who must act): {@code card_declined},
 * {@code payment_declined}, {@code debit_declined}, {@code payment_failed}, and every business-side / integration reason
 * (e.g. {@code input_validation_failed}, {@code invalid_amount}). They are UNCLASSIFIED, whatever their source.
 */
public final class FailureTaxonomy {

    public static final String TABLE_VERSION = "razorpay-error-taxonomy-v1";
    public static final String REASONS_DOC = "https://razorpay.com/docs/errors/payments/list/";
    public static final String ERRORS_DOC = "https://razorpay.com/docs/errors/";

    private static final Map<String, PaymentFailureClass> BY_REASON;
    private static final Map<String, PaymentFailureClass> BY_SOURCE = Map.of(
            "gateway", GATEWAY_ERROR,
            "razorpay", GATEWAY_ERROR);

    static {
        Map<String, PaymentFailureClass> table = new LinkedHashMap<>();
        // All reasons below are listed (with descriptions) at REASONS_DOC, fetched 2026-10-18.
        put(table, INSUFFICIENT_FUNDS, "insufficient_funds");
        put(table, LIMIT_EXCEEDED, "transaction_limit_exceeded", "transaction_daily_limit_exceeded",
                "transaction_daily_count_exceeded", "transaction_frequency_limit_exceeded", "credit_limit_exceeded",
                "mcc_amount_limit_exceeded");
        put(table, BANK_TECHNICAL, "bank_technical_error", "bank_not_available", "bank_cutoff_in_progress",
                "issuer_technical_error", "upi_app_technical_error", "psp_app_not_available", "psp_not_available");
        put(table, GATEWAY_ERROR, "gateway_technical_error", "invalid_response_from_gateway",
                "payment_declined_due_to_high_traffic", "request_timed_out", "server_error");
        put(table, AUTHENTICATION_FAILURE, "authentication_failed", "incorrect_otp", "incorrect_pin", "incorrect_atm_pin",
                "incorrect_cvv", "otp_attempts_exceeded", "otp_expired", "pin_attempts_exceeded");
        put(table, CUSTOMER_DECLINED, "payment_cancelled", "payment_timed_out", "payment_session_expired",
                "payment_collect_request_expired", "authorisation_declined_by_psp");
        put(table, INSTRUMENT_INVALID, "card_expired", "debit_instrument_blocked", "debit_instrument_inactive");
        put(table, MANDATE_INVALID, "mandate_creation_declined", "mandate_creation_expired", "mandate_creation_failed",
                "mandate_creation_timeout", "reqauth_mandate_not_acknowledged");
        BY_REASON = Collections.unmodifiableMap(table);
    }

    private FailureTaxonomy() {
    }

    private static void put(Map<String, PaymentFailureClass> table, PaymentFailureClass failureClass, String... reasons) {
        for (String reason : reasons) {
            if (table.put(reason, failureClass) != null) {
                throw new IllegalStateException("duplicate taxonomy reason " + reason);
            }
        }
    }

    public static FailureClassification classify(ProviderFailure failure) {
        if (failure != null && failure.reason() != null) {
            String reason = failure.reason().toLowerCase(Locale.ROOT);
            PaymentFailureClass byReason = BY_REASON.get(reason);
            if (byReason != null) {
                return new FailureClassification(byReason, FailureClassification.MatchedOn.REASON, reason, REASONS_DOC, TABLE_VERSION);
            }
        }
        // The source decides only when Razorpay sent no reason: a reason MEND has not mapped stays UNCLASSIFIED.
        if (failure != null && failure.reason() == null && failure.source() != null) {
            String source = failure.source().toLowerCase(Locale.ROOT);
            PaymentFailureClass bySource = BY_SOURCE.get(source);
            if (bySource != null) {
                return new FailureClassification(bySource, FailureClassification.MatchedOn.SOURCE, source, REASONS_DOC, TABLE_VERSION);
            }
        }
        return new FailureClassification(UNCLASSIFIED, FailureClassification.MatchedOn.NONE, null, null, TABLE_VERSION);
    }

    /** The reason table (read-only), for tests and documentation. */
    public static Map<String, PaymentFailureClass> reasonTable() {
        return BY_REASON;
    }
}

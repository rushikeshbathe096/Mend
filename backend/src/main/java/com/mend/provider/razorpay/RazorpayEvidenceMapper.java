package com.mend.provider.razorpay;

import com.mend.provider.PaymentEvidence;
import com.mend.provider.PaymentEvidenceStatus;
import com.mend.provider.PaymentLinkState;
import com.mend.provider.PaymentStatusInterpreter;
import com.mend.provider.razorpay.dto.RazorpayPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Locale;

/**
 * The single place where Razorpay status strings are interpreted. Only {@code captured} is money;
 * anything unknown is {@code UNRECOGNIZED}, never success or failure.
 */
public class RazorpayEvidenceMapper implements PaymentStatusInterpreter {

    private static final Logger log = LoggerFactory.getLogger(RazorpayEvidenceMapper.class);

    @Override
    public PaymentEvidenceStatus mapPaymentStatus(String status) {
        return switch (normalize(status)) {
            case "captured" -> PaymentEvidenceStatus.CAPTURED;
            case "authorized" -> PaymentEvidenceStatus.AUTHORIZED_NOT_CAPTURED;
            case "created" -> PaymentEvidenceStatus.PENDING;
            case "failed" -> PaymentEvidenceStatus.FAILED;
            case "refunded" -> PaymentEvidenceStatus.REFUNDED;
            // late_authorized stays unrecognised until a recording shows its semantics.
            default -> {
                warnUnrecognized("payment", status);
                yield PaymentEvidenceStatus.UNRECOGNIZED;
            }
        };
    }

    @Override
    public PaymentLinkState mapLinkStatus(String status) {
        return switch (normalize(status)) {
            case "paid" -> PaymentLinkState.PAID;
            case "partially_paid" -> PaymentLinkState.PARTIALLY_PAID;
            case "created" -> PaymentLinkState.CREATED;
            case "expired" -> PaymentLinkState.EXPIRED;
            case "cancelled" -> PaymentLinkState.CANCELLED;
            default -> {
                warnUnrecognized("payment_link", status);
                yield PaymentLinkState.UNRECOGNIZED;
            }
        };
    }

    public PaymentEvidence toEvidence(RazorpayPayment payment, Instant observedAt) throws MalformedProviderResponse {
        if (payment == null || payment.id() == null || payment.id().isBlank()) {
            throw new MalformedProviderResponse("MISSING_ID");
        }
        if (payment.amount() == null || payment.amount() < 0) {
            throw new MalformedProviderResponse("MISSING_AMOUNT");
        }
        if (payment.currency() == null || payment.currency().isBlank()) {
            throw new MalformedProviderResponse("MISSING_CURRENCY");
        }
        return new PaymentEvidence(payment.id(), mapPaymentStatus(payment.status()), payment.amount(), payment.currency(),
                payment.orderId(), payment.errorCode(), payment.errorReason(), observedAt);
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }

    private static void warnUnrecognized(String kind, String status) {
        log.warn("event=unrecognized_provider_status kind={} value={}", kind, sanitize(status));
    }

    /** Status values are untrusted: bound the length and drop anything that is not a plain token character. */
    private static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_\\-]", "?");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) + "..." : cleaned;
    }
}

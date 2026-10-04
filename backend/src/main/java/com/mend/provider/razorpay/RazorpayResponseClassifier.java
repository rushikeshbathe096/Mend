package com.mend.provider.razorpay;

import com.mend.provider.RejectionKind;
import com.mend.provider.razorpay.dto.RazorpayErrorEnvelope;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure classification of a {@link RawResponse} per M1-provider-correctness.md §9. No I/O, no clock.
 * Operations (U4/U5) convert these classifier-level results into domain results; some §9 rows
 * (400 on create link → lookup first) are decided there, not here.
 */
class RazorpayResponseClassifier {

    private final ObjectMapper readMapper;

    RazorpayResponseClassifier(ObjectMapper objectMapper) {
        this.readMapper = objectMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    sealed interface WriteClassification {
        /** 2xx. The body is unvalidated; the operation decides whether it is usable. */
        record Accepted2xx(int status, String body) implements WriteClassification {
        }

        record Rejected(RejectionKind kind, int status, RazorpayErrorEnvelope.Error error) implements WriteClassification {
        }

        record NotSent(String cause) implements WriteClassification {
        }

        record Unknown(String cause, Integer status, RazorpayErrorEnvelope.Error error) implements WriteClassification {
        }
    }

    sealed interface ReadClassification {
        record Found2xx(int status, String body) implements ReadClassification {
        }

        record NotFound(int status, RazorpayErrorEnvelope.Error error) implements ReadClassification {
        }

        record Unavailable(String cause, Integer status, RazorpayErrorEnvelope.Error error) implements ReadClassification {
        }
    }

    WriteClassification classifyWrite(RawResponse response) {
        return switch (response) {
            case RawResponse.TransportFailure failure -> failure.kind() == TransportFailureKind.NOT_SENT
                    ? new WriteClassification.NotSent(failure.cause())
                    : new WriteClassification.Unknown(failure.cause(), null, null);
            case RawResponse.HttpReply reply -> classifyWriteStatus(reply);
        };
    }

    ReadClassification classifyRead(RawResponse response) {
        return switch (response) {
            case RawResponse.TransportFailure failure -> new ReadClassification.Unavailable(failure.cause(), null, null);
            case RawResponse.HttpReply reply -> {
                int status = reply.status();
                if (status >= 200 && status < 300) {
                    yield new ReadClassification.Found2xx(status, reply.body());
                }
                RazorpayErrorEnvelope.Error error = parseError(reply.body());
                if (status == 404) {
                    yield new ReadClassification.NotFound(status, error);
                }
                yield new ReadClassification.Unavailable("HTTP_" + status, status, error);
            }
        };
    }

    private WriteClassification classifyWriteStatus(RawResponse.HttpReply reply) {
        int status = reply.status();
        if (status >= 200 && status < 300) {
            return new WriteClassification.Accepted2xx(status, reply.body());
        }
        RazorpayErrorEnvelope.Error error = parseError(reply.body());
        return switch (status) {
            case 400 -> new WriteClassification.Rejected(RejectionKind.VALIDATION, status, error);
            case 401 -> new WriteClassification.Rejected(RejectionKind.AUTHENTICATION, status, error);
            case 404 -> new WriteClassification.Rejected(RejectionKind.NOT_SUPPORTED, status, error);
            case 429 -> new WriteClassification.Rejected(RejectionKind.RATE_LIMITED, status, error);
            // 409, every 5xx, and anything undocumented: the provider may have acted.
            default -> new WriteClassification.Unknown("HTTP_" + status, status, error);
        };
    }

    /** Best effort: a missing or malformed error body never changes the classification. */
    RazorpayErrorEnvelope.Error parseError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            RazorpayErrorEnvelope envelope = readMapper.readValue(body, RazorpayErrorEnvelope.class);
            return envelope == null ? null : envelope.error();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

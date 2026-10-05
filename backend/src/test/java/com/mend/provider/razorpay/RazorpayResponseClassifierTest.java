package com.mend.provider.razorpay;

import com.mend.provider.RejectionKind;
import com.mend.provider.razorpay.RazorpayResponseClassifier.ReadClassification;
import com.mend.provider.razorpay.RazorpayResponseClassifier.WriteClassification;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** One test per M1-provider-correctness.md §9 row that the classifier owns. */
class RazorpayResponseClassifierTest {

    private static final String ERROR_400 = "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"extra fields sent\",\"source\":\"NA\",\"step\":\"NA\",\"reason\":\"NA\",\"metadata\":{}}}";

    private final RazorpayResponseClassifier classifier = new RazorpayResponseClassifier(new ObjectMapper());

    private WriteClassification write(int status, String body) {
        return classifier.classifyWrite(new RawResponse.HttpReply(status, body, 1));
    }

    private ReadClassification read(int status, String body) {
        return classifier.classifyRead(new RawResponse.HttpReply(status, body, 1));
    }

    @Test
    void write2xxIsAcceptedAtThisLayer() {
        assertInstanceOf(WriteClassification.Accepted2xx.class, write(200, "{\"id\":\"plink_1\"}"));
        assertInstanceOf(WriteClassification.Accepted2xx.class, write(201, "{}"));
    }

    @Test
    void write2xxWithEmptyBodyIsStillAccepted2xxTheOperationDecides() {
        WriteClassification result = write(200, "");
        assertEquals(new WriteClassification.Accepted2xx(200, ""), result);
    }

    @Test
    void write400IsValidationRejectionWithParsedError() {
        WriteClassification.Rejected rejected = assertInstanceOf(WriteClassification.Rejected.class, write(400, ERROR_400));
        assertEquals(RejectionKind.VALIDATION, rejected.kind());
        assertEquals(400, rejected.status());
        assertEquals("BAD_REQUEST_ERROR", rejected.error().code());
        assertEquals("extra fields sent", rejected.error().description());
    }

    @Test
    void write401IsAuthenticationRejection() {
        WriteClassification.Rejected rejected = assertInstanceOf(WriteClassification.Rejected.class,
                write(401, "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Authentication failed\"}}"));
        assertEquals(RejectionKind.AUTHENTICATION, rejected.kind());
    }

    @Test
    void write404IsNotSupportedRejection() {
        WriteClassification.Rejected rejected = assertInstanceOf(WriteClassification.Rejected.class,
                write(404, "{\"message\":\"no Route matched with those values\"}"));
        assertEquals(RejectionKind.NOT_SUPPORTED, rejected.kind());
        assertNull(rejected.error());
    }

    @Test
    void write409IsUnknown() {
        WriteClassification.Unknown unknown = assertInstanceOf(WriteClassification.Unknown.class, write(409, "{}"));
        assertEquals(409, unknown.status());
    }

    @Test
    void write429IsRateLimitedRejection() {
        WriteClassification.Rejected rejected = assertInstanceOf(WriteClassification.Rejected.class, write(429, ""));
        assertEquals(RejectionKind.RATE_LIMITED, rejected.kind());
    }

    @Test
    void write5xxIsUnknown() {
        for (int status : new int[]{500, 502, 503, 504}) {
            WriteClassification.Unknown unknown = assertInstanceOf(WriteClassification.Unknown.class, write(status, ""));
            assertEquals(status, unknown.status());
            assertEquals("HTTP_" + status, unknown.cause());
        }
    }

    @Test
    void writeUndocumentedStatusIsUnknown() {
        for (int status : new int[]{302, 402, 403, 422}) {
            assertInstanceOf(WriteClassification.Unknown.class, write(status, ""));
        }
    }

    @Test
    void writeTransportNotSentIsNotSent() {
        WriteClassification result = classifier.classifyWrite(
                new RawResponse.TransportFailure(TransportFailureKind.NOT_SENT, "CONNECTION_REFUSED", 3));
        assertEquals(new WriteClassification.NotSent("CONNECTION_REFUSED"), result);
    }

    @Test
    void writeTransportSentNoResponseIsUnknown() {
        WriteClassification result = classifier.classifyWrite(
                new RawResponse.TransportFailure(TransportFailureKind.SENT_NO_RESPONSE, "READ_TIMEOUT", 300));
        assertEquals(new WriteClassification.Unknown("READ_TIMEOUT", null, null), result);
    }

    @Test
    void malformedErrorJsonDoesNotChangeClassification() {
        WriteClassification.Rejected rejected = assertInstanceOf(WriteClassification.Rejected.class, write(400, "{not json"));
        assertEquals(RejectionKind.VALIDATION, rejected.kind());
        assertNull(rejected.error());
        assertInstanceOf(WriteClassification.Unknown.class, write(503, "<html>bad gateway</html>"));
    }

    @Test
    void read2xxIsFound() {
        assertInstanceOf(ReadClassification.Found2xx.class, read(200, "{\"id\":\"pay_1\"}"));
    }

    @Test
    void read404IsNotFound() {
        assertInstanceOf(ReadClassification.NotFound.class, read(404, ""));
    }

    @Test
    void readOtherStatusesAreUnavailableAndKeepTheParsedError() {
        ReadClassification.Unavailable unavailable = assertInstanceOf(ReadClassification.Unavailable.class,
                read(400, "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"The id provided does not exist\",\"reason\":\"input_validation_failed\"}}"));
        assertEquals(400, unavailable.status());
        assertEquals("input_validation_failed", unavailable.error().reason());
        assertInstanceOf(ReadClassification.Unavailable.class, read(401, ""));
        assertInstanceOf(ReadClassification.Unavailable.class, read(429, ""));
        assertInstanceOf(ReadClassification.Unavailable.class, read(503, ""));
    }

    @Test
    void readTransportFailureIsUnavailable() {
        assertInstanceOf(ReadClassification.Unavailable.class, classifier.classifyRead(
                new RawResponse.TransportFailure(TransportFailureKind.NOT_SENT, "UNKNOWN_HOST", 1)));
        assertInstanceOf(ReadClassification.Unavailable.class, classifier.classifyRead(
                new RawResponse.TransportFailure(TransportFailureKind.SENT_NO_RESPONSE, "READ_TIMEOUT", 1)));
    }
}

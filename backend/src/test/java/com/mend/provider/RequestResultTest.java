package com.mend.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestResultTest {

    // No default branch: this only compiles while the hierarchy is sealed and every case is covered.
    private static String describe(RequestResult<String> result) {
        return switch (result) {
            case RequestResult.Accepted<String> accepted -> "accepted:" + accepted.value();
            case RequestResult.Rejected<String> rejected -> "rejected:" + rejected.kind();
            case RequestResult.NotSent<String> notSent -> "not-sent";
            case RequestResult.OutcomeUnknown<String> unknown -> "unknown";
        };
    }

    @Test
    void exhaustiveSwitchCoversEveryResult() {
        assertEquals("accepted:x", describe(new RequestResult.Accepted<>("x")));
        assertEquals("rejected:VALIDATION",
                describe(new RequestResult.Rejected<>(RejectionKind.VALIDATION, "BAD_REQUEST_ERROR", "amount: cannot be blank.", 400)));
        assertEquals("not-sent", describe(new RequestResult.NotSent<>("connection refused")));
        assertEquals("unknown", describe(new RequestResult.OutcomeUnknown<>("read timeout", null)));
    }

    @Test
    void rejectedRequiresKind() {
        assertThrows(IllegalArgumentException.class, () -> new RequestResult.Rejected<String>(null, null, null, null));
    }

    @Test
    void localPreconditionRejectionNeedsNoProviderDetails() {
        RequestResult.Rejected<String> rejected = new RequestResult.Rejected<>(RejectionKind.LOCAL_PRECONDITION, null, null, null);
        assertNull(rejected.httpStatus());
    }

    @Test
    void requiredComponentsAreNullChecked() {
        assertThrows(IllegalArgumentException.class, () -> new RequestResult.Accepted<String>(null));
        assertThrows(IllegalArgumentException.class, () -> new RequestResult.NotSent<String>(null));
        assertThrows(IllegalArgumentException.class, () -> new RequestResult.OutcomeUnknown<String>(null, 504));
    }

    @Test
    void onlyAcceptedIsAccepted() {
        assertTrue(new RequestResult.Accepted<>("x").isAccepted());
        assertFalse(new RequestResult.OutcomeUnknown<String>("timeout", null).isAccepted());
    }
}

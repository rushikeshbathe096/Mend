package com.mend.provider.razorpay;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** Exception-chain mapping for cases real sockets cannot produce reliably (connect timeout, DNS). */
class RazorpayHttpFailureKindTest {

    private static RawResponse.TransportFailure map(Throwable t) {
        return RazorpayHttp.failure(t, System.nanoTime());
    }

    @Test
    void connectTimeoutIsNotSentEvenThoughItIsAnHttpTimeoutException() {
        RawResponse.TransportFailure failure = map(new ResourceAccessException("x", new HttpConnectTimeoutException("connect timed out")));
        assertEquals(TransportFailureKind.NOT_SENT, failure.kind());
        assertEquals("CONNECT_TIMEOUT", failure.cause());
    }

    @Test
    void unknownHostIsNotSent() {
        RawResponse.TransportFailure failure = map(new ResourceAccessException("x", new UnknownHostException("api.razorpay.com")));
        assertEquals(TransportFailureKind.NOT_SENT, failure.kind());
        assertEquals("UNKNOWN_HOST", failure.cause());
    }

    @Test
    void connectionRefusedDeepInChainIsNotSent() {
        Throwable chain = new RuntimeException(new ResourceAccessException("x", new ConnectException("refused")));
        assertEquals(TransportFailureKind.NOT_SENT, map(chain).kind());
    }

    @Test
    void readTimeoutIsNeverNotSent() {
        RawResponse.TransportFailure failure = map(new ResourceAccessException("x", new HttpTimeoutException("request timed out")));
        assertEquals(TransportFailureKind.SENT_NO_RESPONSE, failure.kind());
        assertEquals("READ_TIMEOUT", failure.cause());
    }

    @Test
    void otherIoAndUnexpectedErrorsAreSentNoResponse() {
        assertEquals(TransportFailureKind.SENT_NO_RESPONSE, map(new IOException("reset")).kind());
        RawResponse.TransportFailure unexpected = map(new IllegalStateException("boom"));
        assertEquals(TransportFailureKind.SENT_NO_RESPONSE, unexpected.kind());
        assertEquals("UNEXPECTED_ERROR", unexpected.cause());
    }

    @Test
    void causesAreMachineCodesNotExceptionMessages() {
        assertFalse(map(new IOException("secret-ish detail https://x")).cause().contains("secret-ish"));
    }
}

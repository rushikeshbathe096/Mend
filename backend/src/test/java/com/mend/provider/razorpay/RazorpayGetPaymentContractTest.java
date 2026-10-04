package com.mend.provider.razorpay;

import com.mend.provider.LookupResult;
import com.mend.provider.MerchantRef;
import com.mend.provider.PaymentEvidence;
import com.mend.provider.PaymentEvidenceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RazorpayGetPaymentContractTest {

    private static final String BASE = "https://api.razorpay.com";
    private static final MerchantRef MERCHANT = new MerchantRef(UUID.randomUUID());
    private static final RazorpayCredentials CREDS = new RazorpayCredentials("rzp_test_key", "secret");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private MockRestServiceServer server;
    private RazorpayPaymentProvider provider;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper mapper = new ObjectMapper();
        RazorpayHttp http = new RazorpayHttp(builder, BASE, mapper);
        provider = new RazorpayPaymentProvider(http, merchant -> merchant.equals(MERCHANT) ? Optional.of(CREDS) : Optional.empty(),
                mapper, Clock.fixed(NOW, ZoneOffset.UTC), sleeps::add);
    }

    private void expect(RazorpayFixture fixture) {
        server.expect(requestTo(BASE + fixture.path()))
                .andExpect(method(HttpMethod.valueOf(fixture.method())))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andRespond(withStatus(HttpStatus.valueOf(fixture.status()))
                        .contentType(MediaType.APPLICATION_JSON).body(fixture.responseBody()));
    }

    @Test
    void recordedUnknownIdIsNotFound_fixture13() {
        RazorpayFixture fixture = RazorpayFixture.recorded("13_fetch_payment_not_found");
        expect(fixture);

        LookupResult<PaymentEvidence> result = provider.getPayment(MERCHANT, "pay_M1doesnotexist");

        assertInstanceOf(LookupResult.NotFound.class, result);
        assertTrue(sleeps.isEmpty(), "a 400 must not be retried");
        server.verify();
    }

    @Test
    void failedPaymentEntityMapsToFailedEvidence_docDerived() {
        RazorpayFixture fixture = RazorpayFixture.docDerived("payment_failed_entity");
        expect(fixture);

        LookupResult<PaymentEvidence> result = provider.getPayment(MERCHANT, "pay_Kb8P4crStfXJea");

        if (!(result instanceof LookupResult.Found<PaymentEvidence> found)) {
            fail("expected Found, got " + result);
            return;
        }
        PaymentEvidence evidence = found.value();
        assertEquals("pay_Kb8P4crStfXJea", evidence.providerPaymentId());
        assertEquals(PaymentEvidenceStatus.FAILED, evidence.status());
        assertFalse(evidence.status().isRecovery());
        assertEquals(10000L, evidence.amountMinor());
        assertEquals("INR", evidence.currency());
        assertEquals("BAD_REQUEST_ERROR", evidence.errorCode());
        assertEquals("payment_cancelled", evidence.errorReason());
        assertEquals(NOW, evidence.observedAt());
        server.verify();
    }

    @Test
    void notFound404IsNotFound() {
        server.expect(requestTo(BASE + "/v1/payments/pay_abc")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertInstanceOf(LookupResult.NotFound.class, provider.getPayment(MERCHANT, "pay_abc"));
        server.verify();
    }

    @Test
    void serverErrorIsRetriedTwiceWithBoundedBackoffThenUnavailable() {
        server.expect(times(3), requestTo(BASE + "/v1/payments/pay_abc")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        LookupResult<PaymentEvidence> result = provider.getPayment(MERCHANT, "pay_abc");

        LookupResult.Unavailable<PaymentEvidence> unavailable = assertInstanceOf(LookupResult.Unavailable.class, result);
        assertEquals(503, unavailable.httpStatus());
        assertEquals(2, sleeps.size());
        assertTrue(sleeps.get(0).toMillis() <= 200);
        assertTrue(sleeps.get(1).toMillis() <= 400);
        server.verify();
    }

    @Test
    void transportFailureIsRetriedAndCanRecover() {
        server.expect(requestTo(BASE + "/v1/payments/pay_abc")).andRespond(withException(new ConnectException("refused")));
        server.expect(requestTo(BASE + "/v1/payments/pay_abc")).andRespond(withSuccess(
                "{\"id\":\"pay_abc\",\"amount\":100,\"currency\":\"INR\",\"status\":\"captured\"}", MediaType.APPLICATION_JSON));

        LookupResult<PaymentEvidence> result = provider.getPayment(MERCHANT, "pay_abc");

        assertInstanceOf(LookupResult.Found.class, result);
        assertEquals(1, sleeps.size());
        server.verify();
    }

    @Test
    void authenticationFailureIsUnavailableAndNotRetried() {
        server.expect(requestTo(BASE + "/v1/payments/pay_abc")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body(RazorpayFixture.recorded("14_bad_auth").responseBody()));

        LookupResult.Unavailable<PaymentEvidence> unavailable = assertInstanceOf(LookupResult.Unavailable.class,
                provider.getPayment(MERCHANT, "pay_abc"));

        assertEquals(401, unavailable.httpStatus());
        assertTrue(sleeps.isEmpty());
        server.verify();
    }

    @Test
    void malformed2xxIsUnavailableNeverFabricated() {
        server.expect(requestTo(BASE + "/v1/payments/pay_abc")).andRespond(withSuccess(
                "{\"amount\":100,\"currency\":\"INR\",\"status\":\"captured\"}", MediaType.APPLICATION_JSON));
        assertEquals(new LookupResult.Unavailable<>("MALFORMED", null), provider.getPayment(MERCHANT, "pay_abc"));
        server.verify();
    }

    @Test
    void fractionalAmountIsMalformedNotTruncated() {
        server.expect(requestTo(BASE + "/v1/payments/pay_abc")).andRespond(withSuccess(
                "{\"id\":\"pay_abc\",\"amount\":100.5,\"currency\":\"INR\",\"status\":\"captured\"}", MediaType.APPLICATION_JSON));
        assertEquals(new LookupResult.Unavailable<>("MALFORMED", null), provider.getPayment(MERCHANT, "pay_abc"));
        server.verify();
    }

    @Test
    void invalidIdsMakeZeroHttpCalls() {
        for (String id : new String[]{null, "", "abc", "pay_", "pay_abc/../orders", "pay_abc?x=1", "PAY_abc", "pay_" + "a".repeat(41)}) {
            assertInstanceOf(LookupResult.NotFound.class, provider.getPayment(MERCHANT, id), String.valueOf(id));
        }
        server.verify();
    }

    @Test
    void unknownMerchantMakesZeroHttpCalls() {
        assertEquals(new LookupResult.Unavailable<>("NO_CREDENTIALS_FOR_MERCHANT", null),
                provider.getPayment(new MerchantRef(UUID.randomUUID()), "pay_abc"));
        server.verify();
    }
}

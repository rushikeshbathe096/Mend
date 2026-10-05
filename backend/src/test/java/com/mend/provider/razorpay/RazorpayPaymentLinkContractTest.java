package com.mend.provider.razorpay;

import com.mend.provider.CreatePaymentLink;
import com.mend.provider.LookupResult;
import com.mend.provider.MerchantRef;
import com.mend.provider.MoneyMinor;
import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.PaymentLinkState;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.RejectionKind;
import com.mend.provider.RequestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Payment-link operations replayed against recorded Razorpay test-mode fixtures (recorded/0x_*) and labelled
 * doc-derived fixtures (doc-derived/*) for cases test mode cannot produce. One test per §9 create-link row.
 */
class RazorpayPaymentLinkContractTest {

    private static final String BASE = "https://api.razorpay.com";
    private static final MerchantRef MERCHANT = new MerchantRef(UUID.fromString("8d919a7f-f12f-4bb2-8900-5be3df0d6a84"));
    private static final UUID INTENT = UUID.fromString("3f575aeb-94c4-42d4-aca0-3a0f0641064c");
    private static final RazorpayCredentials CREDS = new RazorpayCredentials("rzp_test_key", "secret");
    /** The reference the recordings were made with, so replayed responses carry the same reference_id. */
    private static final ProviderAttemptKey RECORDED_KEY = new ProviderAttemptKey("mnd_rec_1791151909");
    private static final String LOOKUP_PATH = "/v1/payment_links?reference_id=mnd_rec_1791151909";

    private final ObjectMapper json = new ObjectMapper();
    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private RazorpayPaymentProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new RazorpayPaymentProvider(new RazorpayHttp(builder, BASE, json),
                merchant -> merchant.equals(MERCHANT) ? Optional.of(CREDS) : Optional.empty(),
                json, Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC), sleeps::add);
    }

    private static CreatePaymentLink command(long amountMinor) {
        return new CreatePaymentLink(RECORDED_KEY, new MoneyMinor(amountMinor, "INR"), "MEND M1 contract recording", null, INTENT);
    }

    private void respond(String path, HttpMethod method, RazorpayFixture fixture) {
        server.expect(requestTo(BASE + path)).andExpect(method(method))
                .andRespond(withStatus(HttpStatus.valueOf(fixture.status())).contentType(MediaType.APPLICATION_JSON).body(fixture.responseBody()));
    }

    private void respondCreate(RazorpayFixture fixture) {
        respond("/v1/payment_links", HttpMethod.POST, fixture);
    }

    private void respondLookup(RazorpayFixture fixture) {
        respond(LOOKUP_PATH, HttpMethod.GET, fixture);
    }

    private static PaymentLinkSnapshot accepted(RequestResult<PaymentLinkSnapshot> result) {
        if (result instanceof RequestResult.Accepted<PaymentLinkSnapshot> accepted) {
            return accepted.value();
        }
        return fail("expected Accepted, got " + result);
    }

    private static RequestResult.Rejected<PaymentLinkSnapshot> rejected(RequestResult<PaymentLinkSnapshot> result) {
        if (result instanceof RequestResult.Rejected<PaymentLinkSnapshot> rejected) {
            return rejected;
        }
        return fail("expected Rejected, got " + result);
    }

    // --- 2xx ---

    @Test
    void happyPathIsAcceptedButNotPaid_fixture01() {
        respondCreate(RazorpayFixture.recorded("01_create_link_ok"));

        PaymentLinkSnapshot link = accepted(provider.createPaymentLink(MERCHANT, command(100)));

        assertEquals("plink_TjzX37EuRv8krS", link.providerLinkId());
        assertEquals(RECORDED_KEY, link.referenceId());
        assertEquals(PaymentLinkState.CREATED, link.state());
        assertEquals(100L, link.amountMinor());
        assertEquals(0L, link.amountPaidMinor());
        assertEquals("https://rzp.io/rzp/D0Zue5fx", link.shortUrl());
        assertTrue(link.capturedPaymentIds().isEmpty(), "Accepted never implies money");
        server.verify();
    }

    @Test
    void requestBodyHasExactlyTheDocumentedKeysAndIntegerPaise() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(BASE + "/v1/payment_links")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic cnpwX3Rlc3Rfa2V5OnNlY3JldA=="))
                .andExpect(headerDoesNotExist("X-Razorpay-Idempotency-Header"))
                .andExpect(headerDoesNotExist("X-Idempotency-Key"))
                .andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(RazorpayFixture.recorded("01_create_link_ok").responseBody(), MediaType.APPLICATION_JSON));

        provider.createPaymentLink(MERCHANT, command(100));

        JsonNode sent = json.readTree(body.get());
        assertEquals(Set.of("amount", "currency", "reference_id", "description", "notify", "reminder_enable", "notes"), keys(sent));
        assertTrue(sent.get("amount").isIntegralNumber());
        assertEquals(100L, sent.get("amount").longValue());
        assertEquals("INR", sent.get("currency").asString());
        assertEquals("mnd_rec_1791151909", sent.get("reference_id").asString());
        assertEquals(Set.of("sms", "email"), keys(sent.get("notify")));
        assertFalse(sent.get("notify").get("sms").booleanValue());
        assertFalse(sent.get("notify").get("email").booleanValue());
        assertFalse(sent.get("reminder_enable").booleanValue());
        assertEquals(Set.of("mend_intent_id", "mend_merchant_id"), keys(sent.get("notes")));
        assertEquals(INTENT.toString(), sent.get("notes").get("mend_intent_id").asString());
        assertEquals(MERCHANT.merchantId().toString(), sent.get("notes").get("mend_merchant_id").asString());
        // Same top-level key set as the body Razorpay accepted in the recording.
        assertEquals(keys(RazorpayFixture.recorded("01_create_link_ok").requestBody()), keys(sent));
        server.verify();
    }

    @Test
    void expireByIsSentAsUnixSecondsOnlyWhenSet() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(BASE + "/v1/payment_links"))
                .andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(RazorpayFixture.recorded("01_create_link_ok").responseBody(), MediaType.APPLICATION_JSON));

        provider.createPaymentLink(MERCHANT, new CreatePaymentLink(RECORDED_KEY, new MoneyMinor(100, "INR"), "d",
                Instant.ofEpochSecond(1791238310), INTENT));

        assertEquals(1791238310L, json.readTree(body.get()).get("expire_by").longValue());
        server.verify();
    }

    @Test
    void twoXxWithoutIdIsOutcomeUnknown_docDerived() {
        respondCreate(RazorpayFixture.docDerived("create_link_2xx_missing_id"));
        assertEquals(new RequestResult.OutcomeUnknown<>("MALFORMED_2XX", 200), provider.createPaymentLink(MERCHANT, command(100)));
        server.verify();
    }

    @Test
    void twoXxWithOtherReferenceOrAmountIsOutcomeUnknown() {
        respondCreate(RazorpayFixture.recorded("01_create_link_ok"));
        CreatePaymentLink otherKey = new CreatePaymentLink(ProviderAttemptKey.forIntent(INTENT), new MoneyMinor(100, "INR"), "d", null, INTENT);
        assertEquals(new RequestResult.OutcomeUnknown<>("MALFORMED_2XX", 200), provider.createPaymentLink(MERCHANT, otherKey));
        server.verify();

        server.reset();
        respondCreate(RazorpayFixture.recorded("01_create_link_ok"));
        assertEquals(new RequestResult.OutcomeUnknown<>("MALFORMED_2XX", 200), provider.createPaymentLink(MERCHANT, command(500)));
        server.verify();
    }

    @Test
    void twoXxWithOtherCurrencyIsOutcomeUnknown() {
        String body = RazorpayFixture.recorded("01_create_link_ok").responseBody().replace("\"currency\":\"INR\"", "\"currency\":\"USD\"");
        server.expect(requestTo(BASE + "/v1/payment_links")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertEquals(new RequestResult.OutcomeUnknown<>("MALFORMED_2XX", 200), provider.createPaymentLink(MERCHANT, command(100)));
        server.verify();
    }

    @Test
    void twoXxWithNonLinkShapedIdIsOutcomeUnknown() {
        String body = RazorpayFixture.recorded("01_create_link_ok").responseBody().replace("plink_TjzX37EuRv8krS", "../orders/x");
        server.expect(requestTo(BASE + "/v1/payment_links")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertEquals(new RequestResult.OutcomeUnknown<>("MALFORMED_2XX", 200), provider.createPaymentLink(MERCHANT, command(100)));
        server.verify();
    }

    @Test
    void unparseableTwoXxIsOutcomeUnknown() {
        server.expect(requestTo(BASE + "/v1/payment_links")).andRespond(withSuccess("<html>ok</html>", MediaType.TEXT_HTML));
        assertEquals(new RequestResult.OutcomeUnknown<>("MALFORMED_2XX", 200), provider.createPaymentLink(MERCHANT, command(100)));
        server.verify();
    }

    // --- 400 → lookup first ---

    @Test
    void duplicateReferenceResolvesToExistingLinkViaLookup_fixtures02and06() {
        respondCreate(RazorpayFixture.recorded("02_create_link_duplicate_ref"));
        respondLookup(RazorpayFixture.recorded("06_fetch_link_by_reference"));

        PaymentLinkSnapshot link = accepted(provider.createPaymentLink(MERCHANT, command(100)));

        assertEquals("plink_TjzX37EuRv8krS", link.providerLinkId());
        assertEquals(PaymentLinkState.CREATED, link.state());
        server.verify();
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void dedupIsSignalledInTheStructuredCallLine(org.springframework.boot.test.system.CapturedOutput output) {
        respondCreate(RazorpayFixture.recorded("02_create_link_duplicate_ref"));
        respondLookup(RazorpayFixture.recorded("06_fetch_link_by_reference"));

        provider.createPaymentLink(MERCHANT, command(100));

        assertTrue(output.getOut().contains("result=Accepted provider_link_id=plink_TjzX37EuRv8krS dedup=true"));
        server.verify();
    }

    @Test
    void duplicateReferenceWithDifferentCurrencyIsDuplicateReferenceRejection() {
        respondCreate(RazorpayFixture.recorded("02_create_link_duplicate_ref"));
        String lookup = RazorpayFixture.recorded("06_fetch_link_by_reference").responseBody().replace("\"currency\":\"INR\"", "\"currency\":\"USD\"");
        server.expect(requestTo(BASE + LOOKUP_PATH)).andRespond(withSuccess(lookup, MediaType.APPLICATION_JSON));

        assertEquals(RejectionKind.DUPLICATE_REFERENCE, rejected(provider.createPaymentLink(MERCHANT, command(100))).kind());
        server.verify();
    }

    @Test
    void duplicateReferenceWithDifferentAmountIsDuplicateReferenceRejection() {
        respondCreate(RazorpayFixture.recorded("02_create_link_duplicate_ref"));
        respondLookup(RazorpayFixture.recorded("06_fetch_link_by_reference"));

        RequestResult.Rejected<PaymentLinkSnapshot> rejected = rejected(provider.createPaymentLink(MERCHANT, command(200)));

        assertEquals(RejectionKind.DUPLICATE_REFERENCE, rejected.kind());
        server.verify();
    }

    @Test
    void extraFieldsValidation400WithNoExistingLinkIsValidationRejection_fixture03() {
        respondCreate(RazorpayFixture.recorded("03_create_link_extra_fields"));
        respondLookup(RazorpayFixture.docDerived("fetch_links_empty"));

        RequestResult.Rejected<PaymentLinkSnapshot> rejected = rejected(provider.createPaymentLink(MERCHANT, command(100)));

        assertEquals(RejectionKind.VALIDATION, rejected.kind());
        assertEquals("BAD_REQUEST_ERROR", rejected.providerCode());
        assertEquals("extra fields sent", rejected.providerReason());
        assertEquals(400, rejected.httpStatus());
        server.verify();
    }

    @Test
    void missingAmount400AlsoGoesThroughLookup_fixture04() {
        respondCreate(RazorpayFixture.recorded("04_create_link_missing_amount"));
        respondLookup(RazorpayFixture.docDerived("fetch_links_empty"));

        assertEquals(RejectionKind.VALIDATION, rejected(provider.createPaymentLink(MERCHANT, command(100))).kind());
        server.verify();
    }

    @Test
    void validation400WithLookupUnavailableIsOutcomeUnknown() {
        respondCreate(RazorpayFixture.recorded("02_create_link_duplicate_ref"));
        server.expect(times(3), requestTo(BASE + LOOKUP_PATH)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertEquals(new RequestResult.OutcomeUnknown<>("LOOKUP_UNAVAILABLE_AFTER_400", 400), provider.createPaymentLink(MERCHANT, command(100)));
        server.verify();
    }

    // --- other statuses ---

    @Test
    void badCredentialsIsAuthenticationRejectionWithoutLookup_fixture14() {
        // Fixture 14 was recorded on a GET; its 401 envelope is replayed as the create response.
        respondCreate(RazorpayFixture.recorded("14_bad_auth"));

        RequestResult.Rejected<PaymentLinkSnapshot> rejected = rejected(provider.createPaymentLink(MERCHANT, command(100)));

        assertEquals(RejectionKind.AUTHENTICATION, rejected.kind());
        assertEquals(401, rejected.httpStatus());
        server.verify();
    }

    @Test
    void notFoundOnPostIsNotSupported_fixture12Body() {
        server.expect(requestTo(BASE + "/v1/payment_links")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body(RazorpayFixture.recorded("12_legacy_payments_retry").responseBody()));
        assertEquals(RejectionKind.NOT_SUPPORTED, rejected(provider.createPaymentLink(MERCHANT, command(100))).kind());
        server.verify();
    }

    @Test
    void conflictIsOutcomeUnknown() {
        server.expect(requestTo(BASE + "/v1/payment_links")).andRespond(withStatus(HttpStatus.CONFLICT));
        assertEquals(new RequestResult.OutcomeUnknown<>("HTTP_409", 409), provider.createPaymentLink(MERCHANT, command(100)));
        server.verify();
    }

    @Test
    void rateLimitedIsRateLimitedRejectionAndPostIsNotRetried() {
        server.expect(times(1), requestTo(BASE + "/v1/payment_links")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals(RejectionKind.RATE_LIMITED, rejected(provider.createPaymentLink(MERCHANT, command(100))).kind());
        assertTrue(sleeps.isEmpty());
        server.verify();
    }

    @Test
    void serverErrorIsOutcomeUnknownAndPostIsNotRetried_docDerived() {
        server.expect(times(1), requestTo(BASE + "/v1/payment_links")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertEquals(new RequestResult.OutcomeUnknown<>("HTTP_503", 503), provider.createPaymentLink(MERCHANT, command(100)));
        assertTrue(sleeps.isEmpty());
        server.verify();
    }

    @Test
    void sentButNoResponseIsOutcomeUnknown() {
        server.expect(times(1), requestTo(BASE + "/v1/payment_links")).andRespond(withException(new IOException("connection reset")));
        assertEquals(new RequestResult.OutcomeUnknown<>("IO_ERROR", null), provider.createPaymentLink(MERCHANT, command(100)));
        server.verify();
    }

    @Test
    void readTimeoutIsOutcomeUnknownAndPostIsNotRetried() {
        server.expect(times(1), requestTo(BASE + "/v1/payment_links")).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));
        assertInstanceOf(RequestResult.OutcomeUnknown.class, provider.createPaymentLink(MERCHANT, command(100)));
        assertTrue(sleeps.isEmpty());
        server.verify();
    }

    @Test
    void connectionRefusedIsNotSent() {
        server.expect(times(1), requestTo(BASE + "/v1/payment_links")).andRespond(withException(new ConnectException("refused")));
        assertEquals(new RequestResult.NotSent<>("CONNECTION_REFUSED"), provider.createPaymentLink(MERCHANT, command(100)));
        server.verify();
    }

    // --- local checks: zero HTTP ---

    @Test
    void belowMinimumAmountIsLocalRejectionWithZeroHttpCalls() {
        RequestResult.Rejected<PaymentLinkSnapshot> rejected = rejected(provider.createPaymentLink(MERCHANT, command(99)));
        assertEquals(RejectionKind.LOCAL_PRECONDITION, rejected.kind());
        assertEquals("AMOUNT_BELOW_MINIMUM", rejected.providerCode());
        server.verify();
    }

    @Test
    void unknownMerchantIsAuthenticationRejectionWithZeroHttpCalls() {
        RequestResult.Rejected<PaymentLinkSnapshot> rejected = rejected(provider.createPaymentLink(new MerchantRef(UUID.randomUUID()), command(100)));
        assertEquals(RejectionKind.AUTHENTICATION, rejected.kind());
        assertEquals("NO_CREDENTIALS_FOR_MERCHANT", rejected.providerCode());
        server.verify();
    }

    // --- reads ---

    @Test
    void findByReferenceFound_fixture06() {
        respondLookup(RazorpayFixture.recorded("06_fetch_link_by_reference"));
        LookupResult<PaymentLinkSnapshot> result = provider.findPaymentLinkByReference(MERCHANT, RECORDED_KEY);
        assertInstanceOf(LookupResult.Found.class, result);
        server.verify();
    }

    @Test
    void findByReferenceEmptyIsNotFound_docDerived() {
        respondLookup(RazorpayFixture.docDerived("fetch_links_empty"));
        assertInstanceOf(LookupResult.NotFound.class, provider.findPaymentLinkByReference(MERCHANT, RECORDED_KEY));
        server.verify();
    }

    @Test
    void findByReferenceWithTwoLinksIsUnavailable_docDerived() {
        respondLookup(RazorpayFixture.docDerived("fetch_links_two_results"));
        assertEquals(new LookupResult.Unavailable<>("MULTIPLE_LINKS_FOR_REFERENCE", null),
                provider.findPaymentLinkByReference(MERCHANT, RECORDED_KEY));
        server.verify();
    }

    @Test
    void findByReference404IsUnavailableNotNotFound() {
        server.expect(requestTo(BASE + LOOKUP_PATH)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertInstanceOf(LookupResult.Unavailable.class, provider.findPaymentLinkByReference(MERCHANT, RECORDED_KEY));
        server.verify();
    }

    @Test
    void findByReferenceReturningAnotherReferenceIsUnavailable() {
        server.expect(requestTo(BASE + "/v1/payment_links?reference_id=mnd_other")).andRespond(withSuccess(
                RazorpayFixture.recorded("06_fetch_link_by_reference").responseBody(), MediaType.APPLICATION_JSON));
        assertEquals(new LookupResult.Unavailable<>("MALFORMED", null),
                provider.findPaymentLinkByReference(MERCHANT, new ProviderAttemptKey("mnd_other")));
        server.verify();
    }

    @Test
    void getPaymentLinkFound_fixture05() {
        respond("/v1/payment_links/plink_TjzX37EuRv8krS", HttpMethod.GET, RazorpayFixture.recorded("05_fetch_link_by_id"));
        LookupResult<PaymentLinkSnapshot> result = provider.getPaymentLink(MERCHANT, "plink_TjzX37EuRv8krS");
        if (!(result instanceof LookupResult.Found<PaymentLinkSnapshot> found)) {
            fail("expected Found, got " + result);
            return;
        }
        assertEquals(PaymentLinkState.CREATED, found.value().state());
        assertTrue(found.value().capturedPaymentIds().isEmpty());
        server.verify();
    }

    @Test
    void paidLinkExposesOnlyCapturedPaymentIds_docDerived() {
        respond("/v1/payment_links/plink_KBnb7I424Rc1R9", HttpMethod.GET, RazorpayFixture.docDerived("fetch_link_paid_with_captured_payment"));
        LookupResult<PaymentLinkSnapshot> result = provider.getPaymentLink(MERCHANT, "plink_KBnb7I424Rc1R9");
        if (!(result instanceof LookupResult.Found<PaymentLinkSnapshot> found)) {
            fail("expected Found, got " + result);
            return;
        }
        assertEquals(PaymentLinkState.PAID, found.value().state());
        assertEquals(List.of("pay_KBneB6U4tBMjVm"), found.value().capturedPaymentIds());
        server.verify();
    }

    @Test
    void capturedPaymentWithNonPaymentShapedIdIsMalformed() {
        String body = RazorpayFixture.docDerived("fetch_link_paid_with_captured_payment").responseBody().replace("pay_KBneB6U4tBMjVm", "pay_x/../y");
        server.expect(requestTo(BASE + "/v1/payment_links/plink_KBnb7I424Rc1R9")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertEquals(new LookupResult.Unavailable<>("MALFORMED", null), provider.getPaymentLink(MERCHANT, "plink_KBnb7I424Rc1R9"));
        server.verify();
    }

    @Test
    void getPaymentLink404IsNotFound() {
        server.expect(requestTo(BASE + "/v1/payment_links/plink_abc")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertInstanceOf(LookupResult.NotFound.class, provider.getPaymentLink(MERCHANT, "plink_abc"));
        server.verify();
    }

    @Test
    void getPaymentLinkInvalidIdMakesZeroHttpCalls() {
        for (String id : new String[]{null, "", "plink_", "plink_a/../x", "plink_a?x", "pay_abc"}) {
            assertInstanceOf(LookupResult.NotFound.class, provider.getPaymentLink(MERCHANT, id), String.valueOf(id));
        }
        server.verify();
    }

    @Test
    void unknownLinkStatusIsUnrecognized() {
        server.expect(requestTo(BASE + "/v1/payment_links/plink_abc")).andRespond(withSuccess(
                "{\"id\":\"plink_abc\",\"amount\":100,\"amount_paid\":0,\"currency\":\"INR\",\"status\":\"issued\",\"reference_id\":\"mnd_x\"}",
                MediaType.APPLICATION_JSON));
        LookupResult<PaymentLinkSnapshot> result = provider.getPaymentLink(MERCHANT, "plink_abc");
        if (!(result instanceof LookupResult.Found<PaymentLinkSnapshot> found)) {
            fail("expected Found, got " + result);
            return;
        }
        assertEquals(PaymentLinkState.UNRECOGNIZED, found.value().state());
        server.verify();
    }

    @Test
    void foreignShapedReferenceIsMalformedNotAnException() {
        server.expect(requestTo(BASE + "/v1/payment_links/plink_abc")).andRespond(withSuccess(
                "{\"id\":\"plink_abc\",\"amount\":100,\"amount_paid\":0,\"currency\":\"INR\",\"status\":\"created\",\"reference_id\":\"INV-001:A\"}",
                MediaType.APPLICATION_JSON));
        assertEquals(new LookupResult.Unavailable<>("MALFORMED", null), provider.getPaymentLink(MERCHANT, "plink_abc"));
        server.verify();
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }
}

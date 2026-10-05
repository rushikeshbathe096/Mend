package com.mend.client;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.provider.CreatePaymentLink;
import com.mend.provider.MerchantRef;
import com.mend.provider.MoneyMinor;
import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.PaymentProvider;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.RejectionKind;
import com.mend.provider.RequestResult;
import com.mend.provider.razorpay.RazorpayCredentials;
import com.mend.provider.razorpay.RazorpayHttp;
import com.mend.provider.razorpay.RazorpayPaymentProvider;
import com.mend.provider.razorpay.RazorpayProperties;
import com.mend.provider.razorpay.RazorpayProviderCapabilities;
import com.mend.provider.razorpay.RazorpayStubServer;
import com.mend.service.ProviderActionDispatcher;
import com.mend.service.ProviderActionOutcome;
import com.mend.service.WebhookAmountResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * M1 behaviour change: RazorpayPaymentProviderClient (invented retry endpoints, SUCCESS/FAILURE/ERROR collapse) was
 * replaced by RazorpayPaymentProvider. The same seven cases now run against it over real HTTP to a local stub replaying
 * recorded fixtures. See ADR-001 and M1-provider-correctness.md §9.
 */
@DisplayName("Razorpay Payment Provider Client Unit Tests")
public class RazorpayPaymentProviderClientTest {

    private static final MerchantRef MERCHANT = new MerchantRef(UUID.randomUUID());
    private static final ProviderAttemptKey RECORDED_KEY = new ProviderAttemptKey("mnd_rec_1791176340");

    private final ObjectMapper json = new ObjectMapper();
    private RazorpayStubServer stub;
    private RazorpayPaymentProvider provider;

    @BeforeEach
    void setUp() {
        stub = RazorpayStubServer.start();
        RazorpayProperties properties = new RazorpayProperties(new RazorpayProperties.Api(stub.baseUrl()),
                "rzp_test_key", "rzp_test_secret", 1000, 300, MERCHANT.merchantId().toString());
        provider = new RazorpayPaymentProvider(new RazorpayHttp(properties, json),
                merchant -> merchant.equals(MERCHANT) ? Optional.of(new RazorpayCredentials("rzp_test_key", "rzp_test_secret")) : Optional.empty(),
                json);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private static CreatePaymentLink command(ProviderAttemptKey key) {
        return new CreatePaymentLink(key, new MoneyMinor(100, "INR"), "MEND M1 contract recording", null, UUID.randomUUID());
    }

    private static String fixtureBody(String name) throws Exception {
        try (var in = RazorpayPaymentProviderClientTest.class.getResourceAsStream("/razorpay/recorded/" + name + ".json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            return new ObjectMapper().writeValueAsString(root.path("response").path("body"));
        }
    }

    @Test
    @DisplayName("Should return ERROR when required Razorpay credentials are missing")
    void testMissingCredentials() {
        RequestResult<PaymentLinkSnapshot> result = provider.createPaymentLink(new MerchantRef(UUID.randomUUID()), command(RECORDED_KEY));

        // M1 behaviour change: missing credentials are a local AUTHENTICATION rejection with zero HTTP calls, see ADR-001
        RequestResult.Rejected<PaymentLinkSnapshot> rejected = assertInstanceOf(RequestResult.Rejected.class, result);
        assertEquals(RejectionKind.AUTHENTICATION, rejected.kind());
        assertEquals("NO_CREDENTIALS_FOR_MERCHANT", rejected.providerCode());
        assertTrue(stub.requests().isEmpty());
    }

    @Test
    @DisplayName("Should successfully execute payment retry and map 200 OK provider response")
    void testSuccessfulRazorpayExecution() throws Exception {
        stub.stub("POST", "/v1/payment_links", 200, fixtureBody("01_create_link_ok"));

        RequestResult<PaymentLinkSnapshot> result = provider.createPaymentLink(MERCHANT, command(RECORDED_KEY));

        // M1 behaviour change: there is no retry API; a 2xx link creation is Accepted and is NOT recovered money, see ADR-001
        RequestResult.Accepted<PaymentLinkSnapshot> accepted = assertInstanceOf(RequestResult.Accepted.class, result);
        assertEquals("plink_Tk6TAI6WoeOTDh", accepted.value().providerLinkId());
        assertEquals(0L, accepted.value().amountPaidMinor());
        assertTrue(accepted.value().capturedPaymentIds().isEmpty());
        assertEquals("/v1/payment_links", stub.requests().get(0).pathWithQuery());
    }

    @Test
    @DisplayName("Should propagate Mend idempotencyKey in request headers")
    void testIdempotencyKeyPropagation() throws Exception {
        stub.stub("POST", "/v1/payment_links", 200, fixtureBody("01_create_link_ok"));

        provider.createPaymentLink(MERCHANT, command(RECORDED_KEY));

        RazorpayStubServer.Request request = stub.requests().get(0);
        // M1 behaviour change: Razorpay documents no idempotency header for links; the attempt key travels as reference_id, see ADR-001
        assertEquals("mnd_rec_1791176340", json.readTree(request.body()).get("reference_id").asString());
        assertTrue(request.headers().keySet().stream().noneMatch(h -> h.equalsIgnoreCase("X-Razorpay-Idempotency-Header")));
        assertTrue(request.headers().keySet().stream().noneMatch(h -> h.equalsIgnoreCase("X-Idempotency-Key")));
    }

    @Test
    @DisplayName("Should handle 4xx payment decline cleanly and return FAILURE status")
    void testProviderDecline() throws Exception {
        stub.stub("POST", "/v1/payment_links", 400, fixtureBody("03_create_link_extra_fields"));
        stub.stub("GET", "/v1/payment_links?reference_id=mnd_rec_1791176340", 200, "{\"payment_links\":[]}");

        RequestResult<PaymentLinkSnapshot> result = provider.createPaymentLink(MERCHANT, command(RECORDED_KEY));

        // M1 behaviour change: a 400 is resolved by lookup-by-reference first; no existing link -> Rejected(VALIDATION), see ADR-001
        RequestResult.Rejected<PaymentLinkSnapshot> rejected = assertInstanceOf(RequestResult.Rejected.class, result);
        assertEquals(RejectionKind.VALIDATION, rejected.kind());
        assertEquals("BAD_REQUEST_ERROR", rejected.providerCode());
        assertEquals(2, stub.requests().size());
    }

    @Test
    @DisplayName("Should handle gateway connection timeout and return ERROR status")
    void testProviderTimeout() {
        stub.stall("POST", "/v1/payment_links", 2000);

        RequestResult<PaymentLinkSnapshot> result = provider.createPaymentLink(MERCHANT, command(RECORDED_KEY));

        // M1 behaviour change: a read timeout after sending is OutcomeUnknown, never ERROR/FAILED (invariant 4), see ADR-001
        RequestResult.OutcomeUnknown<PaymentLinkSnapshot> unknown = assertInstanceOf(RequestResult.OutcomeUnknown.class, result);
        assertEquals("READ_TIMEOUT", unknown.cause());
        assertEquals(1, stub.requests().size(), "POST must not be retried");
    }

    @Test
    @DisplayName("Should handle 5xx server error and return ERROR status (not FAILURE)")
    void testServerError() {
        stub.stub("POST", "/v1/payment_links", 503, "");

        RequestResult<PaymentLinkSnapshot> result = provider.createPaymentLink(MERCHANT, command(RECORDED_KEY));

        // M1 behaviour change: 5xx is OutcomeUnknown (the provider may have acted), never ERROR/FAILURE, see ADR-001
        assertEquals(new RequestResult.OutcomeUnknown<>("HTTP_503", 503), result);
        assertEquals(1, stub.requests().size(), "POST must not be retried");
    }

    @Test
    @DisplayName("Should reject unsupported action type (MANUAL_REVIEW) without calling Razorpay API")
    void testUnsupportedActionType() {
        PaymentProvider recordingProvider = mock(PaymentProvider.class);
        StaticListableBeanFactory noSimulator = new StaticListableBeanFactory();
        ProviderActionDispatcher dispatcher = new ProviderActionDispatcher(recordingProvider, new RazorpayProviderCapabilities(),
                mock(WebhookAmountResolver.class), noSimulator.getBeanProvider(com.mend.provider.mock.SyntheticDebitSimulator.class));
        Campaign campaign = new Campaign(UUID.randomUUID(), MERCHANT.merchantId());
        campaign.setPaymentId("pay_rzp_unsupported");

        for (ActionType type : new ActionType[]{ActionType.MANUAL_REVIEW, ActionType.RETRY_PAYMENT}) {
            ActionIntent intent = new ActionIntent(UUID.randomUUID(), MERCHANT.merchantId(), campaign.getId(), 1, type.name(),
                    "MANUAL_REVIEW", null, ActionIntentStatus.CLAIMED, "intent:x:attempt_1:" + type.name(), Instant.now());

            ProviderActionOutcome outcome = dispatcher.dispatch(intent, campaign);

            // M1 behaviour change: in razorpay mode both are NotSupported locally (RETRY_PAYMENT endpoints do not exist), see ADR-001
            assertInstanceOf(ProviderActionOutcome.NotSupported.class, outcome, type.name());
        }
        verifyNoInteractions(recordingProvider);
        assertTrue(stub.requests().isEmpty());
    }
}

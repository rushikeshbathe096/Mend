package com.mend.client;

import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.provider.CreatePaymentLink;
import com.mend.provider.MerchantRef;
import com.mend.provider.MoneyMinor;
import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.PaymentLinkState;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.RejectionKind;
import com.mend.provider.RequestResult;
import com.mend.provider.mock.MockPaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1 behaviour change: MockPaymentProviderClient was replaced by MockPaymentProvider (the provider port); the same five
 * cases now run against it. Results are SYNTHETIC and only exist in mock mode. See ADR-001.
 */
@DisplayName("Mock Payment Provider Client Unit Tests")
public class MockPaymentProviderClientTest {

    private MockPaymentProvider client;
    private MerchantRef merchant;
    private UUID campaignId;

    @BeforeEach
    void setUp() {
        client = new MockPaymentProvider();
        client.reset();
        merchant = new MerchantRef(UUID.randomUUID());
        campaignId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should execute payment retry successfully by default")
    void testSuccessfulPaymentRetry() {
        UUID intentId = UUID.randomUUID();
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intentId);

        RequestResult<String> result = client.simulateDebit(merchant, campaignId, intentId, key);

        RequestResult.Accepted<String> accepted = assertInstanceOf(RequestResult.Accepted.class, result);
        // M1 behaviour change: synthetic ids are prefixed pay_mock_ (was mock_ref_), see ADR-001
        assertTrue(accepted.value().startsWith("pay_mock_"));
        assertEquals(1, client.getInvocationCount());
        assertEquals(key, client.getRecordedRequests().get(0).attemptKey());
        assertEquals(intentId, client.getRecordedRequests().get(0).intentId());
    }

    @Test
    @DisplayName("Should simulate provider failure when configured")
    void testSimulatedProviderFailure() {
        client.setSimulatedStatus(PaymentExecutionStatus.FAILURE);
        client.setSimulatedFailureReason("Insufficient funds");
        UUID intentId = UUID.randomUUID();

        RequestResult<String> result = client.simulateDebit(merchant, campaignId, intentId, ProviderAttemptKey.forIntent(intentId));

        RequestResult.Rejected<String> rejected = assertInstanceOf(RequestResult.Rejected.class, result);
        assertEquals(RejectionKind.VALIDATION, rejected.kind());
        assertEquals("MOCK_DECLINED", rejected.providerCode());
        assertEquals("Insufficient funds", rejected.providerReason());
    }

    @Test
    @DisplayName("Should simulate provider error/timeout when configured")
    void testSimulatedProviderError() {
        client.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        UUID intentId = UUID.randomUUID();

        RequestResult<String> result = client.simulateDebit(merchant, campaignId, intentId, ProviderAttemptKey.forIntent(intentId));

        // M1 behaviour change: a provider error/timeout is an unknown outcome, never a failure (invariant 4), see ADR-001
        assertInstanceOf(RequestResult.OutcomeUnknown.class, result);
    }

    @Test
    @DisplayName("Should propagate idempotency key deterministically across executions")
    void testIdempotencyKeyPropagation() {
        UUID intent1 = UUID.randomUUID();
        UUID intent2 = UUID.randomUUID();

        RequestResult<String> result1 = client.simulateDebit(merchant, campaignId, intent1, ProviderAttemptKey.forIntent(intent1));
        RequestResult<String> result2 = client.simulateDebit(merchant, campaignId, intent2, ProviderAttemptKey.forIntent(intent2));

        // M1 behaviour change: the propagated key is the attempt key derived from the intent id, see ADR-001
        assertEquals(ProviderAttemptKey.forIntent(intent1), client.getRecordedRequests().get(0).attemptKey());
        assertEquals(ProviderAttemptKey.forIntent(intent2), client.getRecordedRequests().get(1).attemptKey());
        assertNotEquals(((RequestResult.Accepted<String>) result1).value(), ((RequestResult.Accepted<String>) result2).value());
        assertEquals(2, client.getInvocationCount());
    }

    @Test
    @DisplayName("Should produce deterministic result references for identical idempotency keys")
    void testDeterministicResultBehavior() {
        UUID intentId = UUID.randomUUID();
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intentId);
        CreatePaymentLink command = new CreatePaymentLink(key, new MoneyMinor(49900, "INR"), "Payment for campaign x", null, intentId);

        RequestResult<PaymentLinkSnapshot> res1 = client.createPaymentLink(merchant, command);
        RequestResult<PaymentLinkSnapshot> res2 = new MockPaymentProvider().createPaymentLink(merchant, command);

        PaymentLinkSnapshot link1 = ((RequestResult.Accepted<PaymentLinkSnapshot>) res1).value();
        PaymentLinkSnapshot link2 = ((RequestResult.Accepted<PaymentLinkSnapshot>) res2).value();
        assertEquals(link1.providerLinkId(), link2.providerLinkId());
        assertTrue(link1.providerLinkId().startsWith("plink_mock_"));
        // A synthetic link is still not money.
        assertEquals(PaymentLinkState.CREATED, link1.state());
        assertTrue(link1.capturedPaymentIds().isEmpty());
    }
}

package com.mend.client;

import com.mend.domain.enums.ActionType;
import com.mend.dto.payment.PaymentExecutionRequest;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.dto.payment.PaymentExecutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Mock Payment Provider Client Unit Tests")
public class MockPaymentProviderClientTest {

    private MockPaymentProviderClient client;
    private UUID merchantId;
    private UUID campaignId;

    @BeforeEach
    void setUp() {
        client = new MockPaymentProviderClient();
        client.reset();
        merchantId = UUID.randomUUID();
        campaignId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should execute payment retry successfully by default")
    void testSuccessfulPaymentRetry() {
        String idempotencyKey = "intent:camp-1:attempt_1:RETRY_PAYMENT";
        PaymentExecutionRequest request = new PaymentExecutionRequest(
                merchantId,
                campaignId,
                UUID.randomUUID(),
                "pay_1001",
                "sub_1001",
                ActionType.RETRY_PAYMENT,
                1,
                idempotencyKey
        );

        PaymentExecutionResult result = client.executeAction(request);

        assertNotNull(result);
        assertEquals(PaymentExecutionStatus.SUCCESS, result.getStatus());
        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
        assertFalse(result.isError());
        assertNotNull(result.getExternalReference());
        assertTrue(result.getExternalReference().startsWith("mock_ref_"));
        assertEquals(idempotencyKey, result.getIdempotencyKey());
        assertEquals(1, client.getInvocationCount());
        assertEquals(request, client.getRecordedRequests().get(0));
    }

    @Test
    @DisplayName("Should simulate provider failure when configured")
    void testSimulatedProviderFailure() {
        client.setSimulatedStatus(PaymentExecutionStatus.FAILURE);
        client.setSimulatedFailureReason("Card declined due to insufficient funds");

        String idempotencyKey = "intent:camp-2:attempt_1:RETRY_PAYMENT";
        PaymentExecutionRequest request = new PaymentExecutionRequest(
                merchantId,
                campaignId,
                UUID.randomUUID(),
                "pay_1002",
                null,
                ActionType.RETRY_PAYMENT,
                1,
                idempotencyKey
        );

        PaymentExecutionResult result = client.executeAction(request);

        assertNotNull(result);
        assertEquals(PaymentExecutionStatus.FAILURE, result.getStatus());
        assertTrue(result.isFailure());
        assertFalse(result.isSuccess());
        assertEquals("Card declined due to insufficient funds", result.getMessage());
        assertEquals("MOCK_DECLINED", result.getResponseCode());
        assertEquals(idempotencyKey, result.getIdempotencyKey());
    }

    @Test
    @DisplayName("Should simulate provider error/timeout when configured")
    void testSimulatedProviderError() {
        client.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        client.setSimulatedErrorMessage("Gateway HTTP 504 Gateway Timeout");

        String idempotencyKey = "intent:camp-3:attempt_1:RETRY_PAYMENT";
        PaymentExecutionRequest request = new PaymentExecutionRequest(
                merchantId,
                campaignId,
                UUID.randomUUID(),
                "pay_1003",
                null,
                ActionType.RETRY_PAYMENT,
                1,
                idempotencyKey
        );

        PaymentExecutionResult result = client.executeAction(request);

        assertNotNull(result);
        assertEquals(PaymentExecutionStatus.ERROR, result.getStatus());
        assertTrue(result.isError());
        assertEquals("Gateway HTTP 504 Gateway Timeout", result.getMessage());
        assertEquals("PROVIDER_ERROR", result.getResponseCode());
        assertEquals(idempotencyKey, result.getIdempotencyKey());
    }

    @Test
    @DisplayName("Should propagate idempotency key deterministically across executions")
    void testIdempotencyKeyPropagation() {
        String key1 = "intent:camp-4:attempt_1:RETRY_PAYMENT";
        String key2 = "intent:camp-4:attempt_2:RETRY_PAYMENT";

        PaymentExecutionRequest request1 = new PaymentExecutionRequest(
                merchantId, campaignId, UUID.randomUUID(), "pay_1004", null, ActionType.RETRY_PAYMENT, 1, key1
        );
        PaymentExecutionRequest request2 = new PaymentExecutionRequest(
                merchantId, campaignId, UUID.randomUUID(), "pay_1004", null, ActionType.RETRY_PAYMENT, 2, key2
        );

        PaymentExecutionResult result1 = client.executeAction(request1);
        PaymentExecutionResult result2 = client.executeAction(request2);

        assertEquals(key1, result1.getIdempotencyKey());
        assertEquals(key2, result2.getIdempotencyKey());
        assertNotEquals(result1.getExternalReference(), result2.getExternalReference());
        assertEquals(2, client.getInvocationCount());
    }

    @Test
    @DisplayName("Should produce deterministic result references for identical idempotency keys")
    void testDeterministicResultBehavior() {
        String key = "intent:camp-5:attempt_1:RETRY_PAYMENT";
        PaymentExecutionRequest req1 = new PaymentExecutionRequest(
                merchantId, campaignId, UUID.randomUUID(), "pay_1005", null, ActionType.RETRY_PAYMENT, 1, key
        );
        PaymentExecutionRequest req2 = new PaymentExecutionRequest(
                merchantId, campaignId, UUID.randomUUID(), "pay_1005", null, ActionType.RETRY_PAYMENT, 1, key
        );

        PaymentExecutionResult res1 = client.executeAction(req1);

        MockPaymentProviderClient freshClient = new MockPaymentProviderClient();
        PaymentExecutionResult res2 = freshClient.executeAction(req2);

        assertEquals(res1.getExternalReference(), res2.getExternalReference());
    }
}

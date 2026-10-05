package com.mend.scheduler;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.service.ActionExecutionService;
import com.mend.service.ActionIntentClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Action Scheduler Unit Tests")
public class ActionSchedulerTest {

    private ActionIntentClaimService claimService;
    private ActionExecutionService actionExecutionService;
    private ActionScheduler scheduler;

    @BeforeEach
    void setUp() {
        claimService = Mockito.mock(ActionIntentClaimService.class);
        actionExecutionService = Mockito.mock(ActionExecutionService.class);
        scheduler = new ActionScheduler(claimService, actionExecutionService, "test-worker", 50, true, true);
    }

    @Test
    @DisplayName("pollAndExecuteDueActions claims and executes due intents")
    void testPollAndExecuteDueActionsSuccess() {
        UUID intentId = UUID.randomUUID();
        ActionIntent intent = new ActionIntent(
                intentId, UUID.randomUUID(), UUID.randomUUID(), 1,
                ActionType.RETRY_PAYMENT.name(), "RETRY_IMMEDIATELY", UUID.randomUUID(),
                ActionIntentStatus.READY, "key-4", Instant.now().minusSeconds(10)
        );

        when(claimService.claimDueIntents(eq("test-worker"), eq(50)))
                .thenReturn(List.of(intent));

        PaymentExecutionResult expectedResult = PaymentExecutionResult.success("ref-123", "Success message", "key-4");
        // M2 behaviour change: the scheduler passes the claim token from its atomic claim to the executor, see ADR-002
        when(actionExecutionService.executeActionIntent(eq(intentId), anyString(), any()))
                .thenReturn(expectedResult);

        List<PaymentExecutionResult> results = scheduler.pollAndExecuteDueActions();

        assertEquals(1, results.size());
        assertEquals("ref-123", results.get(0).getExternalReference());
        verify(actionExecutionService, times(1)).executeActionIntent(eq(intentId), anyString(), any());
        verify(claimService, times(1)).releaseExpiredClaims();
        verify(claimService, times(1)).processExpiredIntents();
    }

    @Test
    @DisplayName("pollAndExecuteDueActions returns empty when no intents claimed")
    void testPollAndExecuteDueActionsNoIntents() {
        when(claimService.claimDueIntents(eq("test-worker"), eq(50)))
                .thenReturn(Collections.emptyList());

        List<PaymentExecutionResult> results = scheduler.pollAndExecuteDueActions();

        assertTrue(results.isEmpty());
        verify(actionExecutionService, never()).executeActionIntent(any(), anyString(), any());
        verify(claimService, times(1)).releaseExpiredClaims();
        verify(claimService, times(1)).processExpiredIntents();
    }

    @Test
    @DisplayName("pollAndExecuteDueActions returns empty when disabled")
    void testPollAndExecuteDisabled() {
        ActionScheduler disabledScheduler = new ActionScheduler(claimService, actionExecutionService, "test-worker", 50, false, true);

        List<PaymentExecutionResult> results = disabledScheduler.pollAndExecuteDueActions();

        assertTrue(results.isEmpty());
        verifyNoInteractions(claimService);
        verifyNoInteractions(actionExecutionService);
    }

    @Test
    @DisplayName("scheduledPollAndExecuteDueActions returns empty when scheduled disabled")
    void testScheduledPollDisabled() {
        ActionScheduler disabledScheduler = new ActionScheduler(claimService, actionExecutionService, "test-worker", 50, true, false);

        List<PaymentExecutionResult> results = disabledScheduler.scheduledPollAndExecuteDueActions();

        assertTrue(results.isEmpty());
        verifyNoInteractions(claimService);
        verifyNoInteractions(actionExecutionService);
    }

    @Test
    @DisplayName("pollAndExecuteDueActions handles execution exception gracefully")
    void testPollAndExecuteHandlesException() {
        UUID intentId = UUID.randomUUID();
        ActionIntent intent = new ActionIntent(
                intentId, UUID.randomUUID(), UUID.randomUUID(), 1,
                ActionType.RETRY_PAYMENT.name(), "RETRY_IMMEDIATELY", UUID.randomUUID(),
                ActionIntentStatus.READY, "key-4", Instant.now().minusSeconds(10)
        );

        when(claimService.claimDueIntents(eq("test-worker"), eq(50)))
                .thenReturn(List.of(intent));

        // M2 behaviour change: the scheduler passes the claim token from its atomic claim to the executor, see ADR-002
        when(actionExecutionService.executeActionIntent(eq(intentId), anyString(), any()))
                .thenThrow(new RuntimeException("Provider error"));

        List<PaymentExecutionResult> results = scheduler.pollAndExecuteDueActions();

        assertTrue(results.isEmpty());
        verify(actionExecutionService, times(1)).executeActionIntent(eq(intentId), anyString(), any());
    }
}
package com.mend.scheduler;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.service.ActionExecutionService;
import com.mend.service.AuditService;
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

    private ActionIntentRepository repository;
    private ActionExecutionService actionExecutionService;
    private AuditService auditService;
    private ActionScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActionIntentRepository.class);
        actionExecutionService = Mockito.mock(ActionExecutionService.class);
        auditService = Mockito.mock(AuditService.class);
        scheduler = new ActionScheduler(repository, actionExecutionService, auditService, "test-worker", 50, 5, true);
    }

    @Test
    @DisplayName("Promote due SCHEDULED intents to READY")
    void testPromoteScheduledIntents() {
        UUID campaignId = UUID.randomUUID();
        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), UUID.randomUUID(), campaignId, 1,
                ActionType.RETRY_PAYMENT.name(), "RETRY_LATER", UUID.randomUUID(),
                ActionIntentStatus.SCHEDULED, "key-1", Instant.now().minusSeconds(60)
        );

        when(repository.findDueIntents(eq(ActionIntentStatus.SCHEDULED), any(Instant.class), any()))
                .thenReturn(List.of(intent));

        List<ActionIntent> promoted = scheduler.promoteScheduledIntents();

        assertEquals(1, promoted.size());
        assertEquals(ActionIntentStatus.READY, promoted.get(0).getStatus());
    }

    @Test
    @DisplayName("Worker atomic claim succeeds when claimIntentAtomic returns 1")
    void testClaimDueIntentsSuccess() {
        UUID campaignId = UUID.randomUUID();
        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), UUID.randomUUID(), campaignId, 1,
                ActionType.RETRY_PAYMENT.name(), "RETRY_IMMEDIATELY", UUID.randomUUID(),
                ActionIntentStatus.READY, "key-2", Instant.now().minusSeconds(10)
        );

        when(repository.findDueIntents(eq(ActionIntentStatus.SCHEDULED), any(Instant.class), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findDueIntents(eq(ActionIntentStatus.READY), any(Instant.class), any()))
                .thenReturn(List.of(intent));
        when(repository.claimIntentAtomic(any(), eq(ActionIntentStatus.READY), eq(ActionIntentStatus.CLAIMED), any(), any(), eq("worker-1")))
                .thenReturn(1);

        List<ActionIntent> claimed = scheduler.claimDueIntents("worker-1", 10);

        assertEquals(1, claimed.size());
        assertEquals(ActionIntentStatus.CLAIMED, claimed.get(0).getStatus());
        assertEquals("worker-1", claimed.get(0).getWorkerId());
        assertNotNull(claimed.get(0).getClaimToken());
    }

    @Test
    @DisplayName("Worker atomic claim fails when another worker wins race (returns 0)")
    void testClaimDueIntentsRaceCondition() {
        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                ActionType.RETRY_PAYMENT.name(), "RETRY_IMMEDIATELY", UUID.randomUUID(),
                ActionIntentStatus.READY, "key-3", Instant.now().minusSeconds(10)
        );

        when(repository.findDueIntents(eq(ActionIntentStatus.SCHEDULED), any(Instant.class), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findDueIntents(eq(ActionIntentStatus.READY), any(Instant.class), any()))
                .thenReturn(List.of(intent));
        when(repository.claimIntentAtomic(any(), any(), any(), any(), any(), any()))
                .thenReturn(0); // Lost race condition

        List<ActionIntent> claimed = scheduler.claimDueIntents("worker-2", 10);

        assertTrue(claimed.isEmpty());
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

        when(repository.findDueIntents(eq(ActionIntentStatus.SCHEDULED), any(Instant.class), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findDueIntents(eq(ActionIntentStatus.READY), any(Instant.class), any()))
                .thenReturn(List.of(intent));
        when(repository.claimIntentAtomic(any(), eq(ActionIntentStatus.READY), eq(ActionIntentStatus.CLAIMED), any(), any(), any()))
                .thenReturn(1);

        PaymentExecutionResult expectedResult = PaymentExecutionResult.success("ref-123", "Success message", "key-4");
        when(actionExecutionService.executeActionIntent(eq(intentId), anyString()))
                .thenReturn(expectedResult);

        List<PaymentExecutionResult> results = scheduler.pollAndExecuteDueActions();

        assertEquals(1, results.size());
        assertEquals("ref-123", results.get(0).getExternalReference());
        verify(actionExecutionService, times(1)).executeActionIntent(eq(intentId), anyString());
    }

    @Test
    @DisplayName("pollAndExecuteDueActions returns empty when disabled")
    void testPollAndExecuteDisabled() {
        ActionScheduler disabledScheduler = new ActionScheduler(repository, actionExecutionService, auditService, "test-worker", 50, 5, false);

        List<PaymentExecutionResult> results = disabledScheduler.pollAndExecuteDueActions();

        assertTrue(results.isEmpty());
        verifyNoInteractions(repository);
    }
}

package com.mend.service;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Action Intent Claim Service Unit Tests")
public class ActionIntentClaimServiceTest {

    private ActionIntentRepository repository;
    private AuditService auditService;
    private ActionIntentClaimService claimService;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActionIntentRepository.class);
        auditService = Mockito.mock(AuditService.class);
        claimService = new ActionIntentClaimService(repository, new ActionIntentTransitions(repository), auditService, 50, 5);
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

        when(repository.findDueClaimableIntents(eq(ActionIntentStatus.SCHEDULED),
                eq(CampaignStatus.ACTION_PENDING), any(Instant.class), any()))
                .thenReturn(List.of(intent));
        when(repository.promoteScheduled(intent.getId())).thenReturn(1);

        List<ActionIntent> promoted = claimService.promoteScheduledIntents();

        assertEquals(1, promoted.size());
        assertEquals(ActionIntentStatus.READY, promoted.get(0).getStatus());
        // M2 behaviour change: status changes go through a conditional UPDATE (ActionIntentTransitions), not entity save, see ADR-002
        verify(repository, times(1)).promoteScheduled(intent.getId());
        verify(repository, never()).save(any());
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

        when(repository.findDueClaimableIntents(eq(ActionIntentStatus.SCHEDULED),
                eq(CampaignStatus.ACTION_PENDING), any(Instant.class), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findDueClaimableIntents(eq(ActionIntentStatus.READY),
                eq(CampaignStatus.ACTION_PENDING), any(Instant.class), any()))
                .thenReturn(List.of(intent));
        when(repository.claimIntentAtomic(any(), eq(ActionIntentStatus.READY), eq(ActionIntentStatus.CLAIMED),
                any(), any(), eq("worker-1"), eq(CampaignStatus.ACTION_PENDING)))
                .thenReturn(1);

        List<ActionIntent> claimed = claimService.claimDueIntents("worker-1", 10);

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

        when(repository.findDueClaimableIntents(eq(ActionIntentStatus.SCHEDULED),
                eq(CampaignStatus.ACTION_PENDING), any(Instant.class), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findDueClaimableIntents(eq(ActionIntentStatus.READY),
                eq(CampaignStatus.ACTION_PENDING), any(Instant.class), any()))
                .thenReturn(List.of(intent));
        when(repository.claimIntentAtomic(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        List<ActionIntent> claimed = claimService.claimDueIntents("worker-2", 10);

        assertTrue(claimed.isEmpty());
    }

    @Test
    @DisplayName("Release expired claims back to READY")
    void testReleaseExpiredClaims() {
        UUID campaignId = UUID.randomUUID();
        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), UUID.randomUUID(), campaignId, 1,
                ActionType.RETRY_PAYMENT.name(), "RETRY_IMMEDIATELY", UUID.randomUUID(),
                ActionIntentStatus.CLAIMED, "key-4", Instant.now().minusSeconds(600)
        );
        intent.setClaimedAt(Instant.now().minus(Duration.ofMinutes(10)));
        intent.setClaimToken("token-123");
        intent.setWorkerId("worker-1");

        when(repository.findExpiredClaims(any(Instant.class)))
                .thenReturn(List.of(intent));
        when(repository.releaseIfUndispatched(eq(intent.getId()), any(Instant.class))).thenReturn(1);

        List<ActionIntent> released = claimService.releaseExpiredClaims();

        assertEquals(1, released.size());
        assertEquals(ActionIntentStatus.READY, released.get(0).getStatus());
        assertNull(released.get(0).getClaimedAt());
        assertNull(released.get(0).getClaimToken());
        assertNull(released.get(0).getWorkerId());
        // M2 behaviour change: release is a conditional UPDATE that only applies to undispatched claims, see ADR-002
        verify(repository, times(1)).releaseIfUndispatched(eq(intent.getId()), any(Instant.class));
        verify(repository, never()).save(any());
        verify(auditService, times(1)).logEvent(any(), any(), eq("STATE_TRANSITION"), eq("SYSTEM"), isNull(), anyString());
    }

    @Test
    @DisplayName("Process expired intents to EXPIRED status")
    void testProcessExpiredIntents() {
        UUID campaignId = UUID.randomUUID();
        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), UUID.randomUUID(), campaignId, 1,
                ActionType.RETRY_PAYMENT.name(), "RETRY_IMMEDIATELY", UUID.randomUUID(),
                ActionIntentStatus.READY, "key-5", Instant.now().minusSeconds(3600)
        );
        intent.setExpiresAt(Instant.now().minusSeconds(60));

        when(repository.findExpiredIntents(any(Instant.class)))
                .thenReturn(List.of(intent));
        when(repository.expireUnclaimed(eq(intent.getId()), any(Instant.class))).thenReturn(1);

        List<ActionIntent> processed = claimService.processExpiredIntents();

        assertEquals(1, processed.size());
        assertEquals(ActionIntentStatus.EXPIRED, processed.get(0).getStatus());
        assertNotNull(processed.get(0).getCompletedAt());
        // M2 behaviour change: expiry is a conditional UPDATE (ActionIntentTransitions), not entity save, see ADR-002
        verify(repository, times(1)).expireUnclaimed(eq(intent.getId()), any(Instant.class));
        verify(repository, never()).save(any());
        verify(auditService, times(1)).logEvent(any(), any(), eq("STATE_TRANSITION"), eq("SYSTEM"), isNull(), anyString());
    }

    @Test
    @DisplayName("M2: a dispatched (or concurrently moved) intent is never released back to READY")
    void testReleaseSkipsWhenConditionalUpdateDoesNotApply() {
        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                ActionType.RETRY_PAYMENT.name(), "RETRY_IMMEDIATELY", UUID.randomUUID(),
                ActionIntentStatus.CLAIMED, "key-6", Instant.now().minusSeconds(600)
        );
        intent.setClaimedAt(Instant.now().minus(Duration.ofMinutes(10)));
        intent.setClaimToken("token-456");
        when(repository.findExpiredClaims(any(Instant.class))).thenReturn(List.of(intent));
        when(repository.releaseIfUndispatched(eq(intent.getId()), any(Instant.class))).thenReturn(0);

        List<ActionIntent> released = claimService.releaseExpiredClaims();

        assertTrue(released.isEmpty());
        assertEquals("token-456", intent.getClaimToken());
        verify(auditService, never()).logEvent(any(), any(), anyString(), anyString(), any(), anyString());
    }
}

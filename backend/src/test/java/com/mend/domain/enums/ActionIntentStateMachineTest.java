package com.mend.domain.enums;

import com.mend.domain.entity.ActionIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Action Intent State Machine Unit Tests")
public class ActionIntentStateMachineTest {

    @Test
    @DisplayName("ActionIntentStatus terminal and executable predicate checks")
    void testActionIntentStatusPredicates() {
        assertTrue(ActionIntentStatus.SUCCEEDED.isTerminal());
        assertTrue(ActionIntentStatus.FAILED.isTerminal());
        assertTrue(ActionIntentStatus.CANCELLED.isTerminal());
        assertTrue(ActionIntentStatus.EXPIRED.isTerminal());

        assertFalse(ActionIntentStatus.PENDING.isTerminal());
        assertFalse(ActionIntentStatus.SCHEDULED.isTerminal());
        assertFalse(ActionIntentStatus.READY.isTerminal());
        assertFalse(ActionIntentStatus.CLAIMED.isTerminal());
        assertFalse(ActionIntentStatus.PROCESSING.isTerminal());

        assertTrue(ActionIntentStatus.PENDING.isExecutable());
        assertTrue(ActionIntentStatus.SCHEDULED.isExecutable());
        assertTrue(ActionIntentStatus.READY.isExecutable());

        assertFalse(ActionIntentStatus.SUCCEEDED.isExecutable());
        assertFalse(ActionIntentStatus.FAILED.isExecutable());
        assertFalse(ActionIntentStatus.CANCELLED.isExecutable());
    }

    @Test
    @DisplayName("ActionType mapping from RecoveryStrategy")
    void testActionTypeFromRecoveryStrategy() {
        assertEquals(ActionType.RETRY_PAYMENT, ActionType.fromRecoveryStrategy(RecoveryStrategy.RETRY_IMMEDIATELY));
        assertEquals(ActionType.RETRY_PAYMENT, ActionType.fromRecoveryStrategy(RecoveryStrategy.RETRY_LATER));
        assertEquals(ActionType.REQUEST_CUSTOMER_ACTION, ActionType.fromRecoveryStrategy(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED));
        assertEquals(ActionType.MANUAL_REVIEW, ActionType.fromRecoveryStrategy(RecoveryStrategy.MANUAL_REVIEW));
        assertNull(ActionType.fromRecoveryStrategy(RecoveryStrategy.NO_ACTION));
        assertNull(ActionType.fromRecoveryStrategy(null));
    }

    @Test
    @DisplayName("M2 states NEEDS_RECONCILIATION and AWAITING_PAYMENT are non-terminal, non-executable and in flight")
    void testM2StatusPredicates() {
        for (ActionIntentStatus status : new ActionIntentStatus[]{ActionIntentStatus.NEEDS_RECONCILIATION, ActionIntentStatus.AWAITING_PAYMENT}) {
            assertFalse(status.isTerminal(), status.name());
            assertFalse(status.isExecutable(), status.name());
            assertTrue(status.isInFlight(), status.name());
        }
        assertTrue(ActionIntentStatus.CLAIMED.isInFlight());
        assertTrue(ActionIntentStatus.EXECUTING.isInFlight());
        for (ActionIntentStatus status : new ActionIntentStatus[]{ActionIntentStatus.PENDING, ActionIntentStatus.SCHEDULED,
                ActionIntentStatus.READY, ActionIntentStatus.PROCESSING, ActionIntentStatus.SUCCEEDED, ActionIntentStatus.FAILED,
                ActionIntentStatus.CANCELLED, ActionIntentStatus.EXPIRED}) {
            assertFalse(status.isInFlight(), status.name());
        }
        // Fits the existing VARCHAR(20) status column.
        assertTrue(ActionIntentStatus.NEEDS_RECONCILIATION.name().length() <= 20);
    }
}

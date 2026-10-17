package com.mend.statemachine;

import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryAttribution;
import com.mend.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/** Bug sweep item 2: pre-action → RECOVERED exists only for ORIGINAL_PAYMENT_LATE_CAPTURE. */
class LateCaptureRecoveryEdgeTest {

    private final CampaignStateMachine stateMachine = new CampaignStateMachine();

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"CREATED", "CLASSIFIED", "ELIGIBLE", "SCHEDULED"})
    @DisplayName("Pre-action → RECOVERED: allowed for a late capture only, never as a plain transition or a MEND recovery")
    void preActionRecoveryNeedsLateCaptureAttribution(CampaignStatus preAction) {
        assertTrue(stateMachine.isValidRecovery(preAction, RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE));
        assertDoesNotThrow(() -> stateMachine.validateRecovery(preAction, RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE));

        assertFalse(stateMachine.isValidTransition(preAction, CampaignStatus.RECOVERED), "generic transition stays invalid");
        assertThrows(InvalidStateTransitionException.class, () -> stateMachine.validateTransition(preAction, CampaignStatus.RECOVERED));
        assertFalse(stateMachine.isValidRecovery(preAction, RecoveryAttribution.MEND));
        assertFalse(stateMachine.isValidRecovery(preAction, null));
        assertThrows(InvalidStateTransitionException.class, () -> stateMachine.validateRecovery(preAction, RecoveryAttribution.MEND));
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"ACTION_PENDING", "EXECUTING", "FAILED"})
    @DisplayName("States that already had a RECOVERED edge keep it for every attribution")
    void existingRecoveryEdgesUnchanged(CampaignStatus state) {
        assertTrue(stateMachine.isValidTransition(state, CampaignStatus.RECOVERED));
        assertTrue(stateMachine.isValidRecovery(state, RecoveryAttribution.MEND));
        assertTrue(stateMachine.isValidRecovery(state, RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE));
        assertFalse(stateMachine.isValidRecovery(state, null), "a recovery always records who recovered");
    }

    @Test
    @DisplayName("Terminal states never recover, whatever the attribution")
    void terminalStatesNeverRecover() {
        for (CampaignStatus terminal : new CampaignStatus[]{CampaignStatus.RECOVERED, CampaignStatus.EXHAUSTED, CampaignStatus.CANCELLED}) {
            for (RecoveryAttribution attribution : RecoveryAttribution.values()) {
                assertFalse(stateMachine.isValidRecovery(terminal, attribution), terminal + " / " + attribution);
            }
        }
    }
}

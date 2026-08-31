package com.mend.statemachine;

import com.mend.domain.enums.CampaignStatus;
import com.mend.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Campaign State Machine Unit Tests")
public class CampaignStateMachineTest {

    private CampaignStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new CampaignStateMachine();
    }

    // ============================================================
    // 1. VALID STATE TRANSITIONS
    // ============================================================

    @Test
    @DisplayName("Should allow CREATED -> CLASSIFIED transition")
    void testCreatedToClassified() {
        assertTrue(stateMachine.isValidTransition(CampaignStatus.CREATED, CampaignStatus.CLASSIFIED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(CampaignStatus.CREATED, CampaignStatus.CLASSIFIED));
    }

    @Test
    @DisplayName("Should allow CLASSIFIED -> ELIGIBLE transition")
    void testClassifiedToEligible() {
        assertTrue(stateMachine.isValidTransition(CampaignStatus.CLASSIFIED, CampaignStatus.ELIGIBLE));
        assertDoesNotThrow(() -> stateMachine.validateTransition(CampaignStatus.CLASSIFIED, CampaignStatus.ELIGIBLE));
    }

    @Test
    @DisplayName("Should allow CLASSIFIED -> EXHAUSTED transition for non-recoverable failures")
    void testClassifiedToExhausted() {
        assertTrue(stateMachine.isValidTransition(CampaignStatus.CLASSIFIED, CampaignStatus.EXHAUSTED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(CampaignStatus.CLASSIFIED, CampaignStatus.EXHAUSTED));
    }

    @Test
    @DisplayName("Should allow ELIGIBLE -> SCHEDULED transition")
    void testEligibleToScheduled() {
        assertTrue(stateMachine.isValidTransition(CampaignStatus.ELIGIBLE, CampaignStatus.SCHEDULED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(CampaignStatus.ELIGIBLE, CampaignStatus.SCHEDULED));
    }

    @Test
    @DisplayName("Should allow SCHEDULED -> ACTION_PENDING transition")
    void testScheduledToActionPending() {
        assertTrue(stateMachine.isValidTransition(CampaignStatus.SCHEDULED, CampaignStatus.ACTION_PENDING));
        assertDoesNotThrow(() -> stateMachine.validateTransition(CampaignStatus.SCHEDULED, CampaignStatus.ACTION_PENDING));
    }

    @Test
    @DisplayName("Should allow ACTION_PENDING -> EXECUTING transition")
    void testActionPendingToExecuting() {
        assertTrue(stateMachine.isValidTransition(CampaignStatus.ACTION_PENDING, CampaignStatus.EXECUTING));
        assertDoesNotThrow(() -> stateMachine.validateTransition(CampaignStatus.ACTION_PENDING, CampaignStatus.EXECUTING));
    }

    @Test
    @DisplayName("Should allow EXECUTING -> RECOVERED transition")
    void testExecutingToRecovered() {
        assertTrue(stateMachine.isValidTransition(CampaignStatus.EXECUTING, CampaignStatus.RECOVERED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(CampaignStatus.EXECUTING, CampaignStatus.RECOVERED));
    }

    @Test
    @DisplayName("Should allow EXECUTING -> FAILED transition")
    void testExecutingToFailed() {
        assertTrue(stateMachine.isValidTransition(CampaignStatus.EXECUTING, CampaignStatus.FAILED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(CampaignStatus.EXECUTING, CampaignStatus.FAILED));
    }

    @Test
    @DisplayName("Should allow FAILED -> SCHEDULED transition for retries")
    void testFailedToScheduled() {
        assertTrue(stateMachine.isValidTransition(CampaignStatus.FAILED, CampaignStatus.SCHEDULED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(CampaignStatus.FAILED, CampaignStatus.SCHEDULED));
    }

    @Test
    @DisplayName("Should allow FAILED -> EXHAUSTED transition when max attempts reached")
    void testFailedToExhausted() {
        assertTrue(stateMachine.isValidTransition(CampaignStatus.FAILED, CampaignStatus.EXHAUSTED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(CampaignStatus.FAILED, CampaignStatus.EXHAUSTED));
    }

    @Test
    @DisplayName("Should allow CANCELLED from any non-terminal state")
    void testCancellationFromNonTerminalStates() {
        assertTrue(stateMachine.isValidTransition(CampaignStatus.CREATED, CampaignStatus.CANCELLED));
        assertTrue(stateMachine.isValidTransition(CampaignStatus.CLASSIFIED, CampaignStatus.CANCELLED));
        assertTrue(stateMachine.isValidTransition(CampaignStatus.ELIGIBLE, CampaignStatus.CANCELLED));
        assertTrue(stateMachine.isValidTransition(CampaignStatus.SCHEDULED, CampaignStatus.CANCELLED));
        assertTrue(stateMachine.isValidTransition(CampaignStatus.ACTION_PENDING, CampaignStatus.CANCELLED));
        assertTrue(stateMachine.isValidTransition(CampaignStatus.EXECUTING, CampaignStatus.CANCELLED));
        assertTrue(stateMachine.isValidTransition(CampaignStatus.FAILED, CampaignStatus.CANCELLED));
    }

    // ============================================================
    // 2. INVALID / FORBIDDEN TRANSITIONS
    // ============================================================

    @Test
    @DisplayName("Should forbid transitions out of RECOVERED terminal state")
    void testForbiddenTransitionsFromRecovered() {
        assertFalse(stateMachine.isValidTransition(CampaignStatus.RECOVERED, CampaignStatus.EXECUTING));
        assertFalse(stateMachine.isValidTransition(CampaignStatus.RECOVERED, CampaignStatus.SCHEDULED));
        assertFalse(stateMachine.isValidTransition(CampaignStatus.RECOVERED, CampaignStatus.CREATED));

        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachine.validateTransition(CampaignStatus.RECOVERED, CampaignStatus.EXECUTING));
    }

    @Test
    @DisplayName("Should forbid transitions out of CANCELLED terminal state")
    void testForbiddenTransitionsFromCancelled() {
        assertFalse(stateMachine.isValidTransition(CampaignStatus.CANCELLED, CampaignStatus.SCHEDULED));
        assertFalse(stateMachine.isValidTransition(CampaignStatus.CANCELLED, CampaignStatus.EXECUTING));

        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachine.validateTransition(CampaignStatus.CANCELLED, CampaignStatus.SCHEDULED));
    }

    @Test
    @DisplayName("Should forbid transitions out of EXHAUSTED terminal state")
    void testForbiddenTransitionsFromExhausted() {
        assertFalse(stateMachine.isValidTransition(CampaignStatus.EXHAUSTED, CampaignStatus.EXECUTING));
        assertFalse(stateMachine.isValidTransition(CampaignStatus.EXHAUSTED, CampaignStatus.SCHEDULED));

        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachine.validateTransition(CampaignStatus.EXHAUSTED, CampaignStatus.EXECUTING));
    }

    @Test
    @DisplayName("Should forbid backward transitions")
    void testForbiddenBackwardTransitions() {
        assertFalse(stateMachine.isValidTransition(CampaignStatus.ELIGIBLE, CampaignStatus.CREATED));
        assertFalse(stateMachine.isValidTransition(CampaignStatus.EXECUTING, CampaignStatus.CLASSIFIED));
        assertFalse(stateMachine.isValidTransition(CampaignStatus.FAILED, CampaignStatus.CREATED));

        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachine.validateTransition(CampaignStatus.ELIGIBLE, CampaignStatus.CREATED));
    }

    // ============================================================
    // 3. TERMINAL STATE DETERMINATION
    // ============================================================

    @Test
    @DisplayName("Should correctly identify terminal states")
    void testIsTerminal() {
        assertTrue(stateMachine.isTerminal(CampaignStatus.RECOVERED));
        assertTrue(stateMachine.isTerminal(CampaignStatus.EXHAUSTED));
        assertTrue(stateMachine.isTerminal(CampaignStatus.CANCELLED));

        assertFalse(stateMachine.isTerminal(CampaignStatus.CREATED));
        assertFalse(stateMachine.isTerminal(CampaignStatus.CLASSIFIED));
        assertFalse(stateMachine.isTerminal(CampaignStatus.ELIGIBLE));
        assertFalse(stateMachine.isTerminal(CampaignStatus.SCHEDULED));
        assertFalse(stateMachine.isTerminal(CampaignStatus.ACTION_PENDING));
        assertFalse(stateMachine.isTerminal(CampaignStatus.EXECUTING));
        assertFalse(stateMachine.isTerminal(CampaignStatus.FAILED));
    }
}

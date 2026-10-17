package com.mend.statemachine;

import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryAttribution;
import com.mend.exception.InvalidStateTransitionException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class CampaignStateMachine {

    private final Map<CampaignStatus, Set<CampaignStatus>> allowedTransitions = new EnumMap<>(CampaignStatus.class);

    /**
     * Bug sweep item 2: pre-action states that may go to RECOVERED, but only when the original payment was captured late
     * ({@link RecoveryAttribution#ORIGINAL_PAYMENT_LATE_CAPTURE}). Not part of {@link #isValidTransition}: a generic
     * transition, or a MEND recovery, from these states is still invalid.
     */
    private static final Set<CampaignStatus> LATE_CAPTURE_ONLY_RECOVERED_FROM = EnumSet.of(
            CampaignStatus.CREATED, CampaignStatus.CLASSIFIED, CampaignStatus.ELIGIBLE, CampaignStatus.SCHEDULED);

    public CampaignStateMachine() {
        // Define explicit state transition matrix
        allowedTransitions.put(CampaignStatus.CREATED, EnumSet.of(CampaignStatus.CLASSIFIED, CampaignStatus.CANCELLED));
        allowedTransitions.put(CampaignStatus.CLASSIFIED, EnumSet.of(CampaignStatus.ELIGIBLE, CampaignStatus.EXHAUSTED, CampaignStatus.CANCELLED));
        allowedTransitions.put(CampaignStatus.ELIGIBLE, EnumSet.of(CampaignStatus.SCHEDULED, CampaignStatus.ACTION_PENDING, CampaignStatus.CANCELLED));
        allowedTransitions.put(CampaignStatus.SCHEDULED, EnumSet.of(CampaignStatus.ACTION_PENDING, CampaignStatus.CANCELLED));
        allowedTransitions.put(CampaignStatus.ACTION_PENDING, EnumSet.of(CampaignStatus.EXECUTING, CampaignStatus.ELIGIBLE, CampaignStatus.RECOVERED, CampaignStatus.CANCELLED));
        // M2: EXECUTING -> ACTION_PENDING is the reconciler's SAFE_RETRY (reason code RETRY_DEFERRED).
        allowedTransitions.put(CampaignStatus.EXECUTING, EnumSet.of(CampaignStatus.RECOVERED, CampaignStatus.FAILED, CampaignStatus.CANCELLED,
                CampaignStatus.ACTION_PENDING));
        allowedTransitions.put(CampaignStatus.FAILED, EnumSet.of(CampaignStatus.SCHEDULED, CampaignStatus.ELIGIBLE, CampaignStatus.ACTION_PENDING, CampaignStatus.EXHAUSTED, CampaignStatus.CANCELLED, CampaignStatus.RECOVERED));

        // Terminal states explicitly have empty transition sets
        allowedTransitions.put(CampaignStatus.RECOVERED, EnumSet.noneOf(CampaignStatus.class));
        allowedTransitions.put(CampaignStatus.EXHAUSTED, EnumSet.noneOf(CampaignStatus.class));
        allowedTransitions.put(CampaignStatus.CANCELLED, EnumSet.noneOf(CampaignStatus.class));
    }

    public boolean isValidTransition(CampaignStatus currentState, CampaignStatus targetState) {
        if (currentState == null || targetState == null) {
            return false;
        }
        Set<CampaignStatus> validTargets = allowedTransitions.get(currentState);
        return validTargets != null && validTargets.contains(targetState);
    }

    public void validateTransition(CampaignStatus currentState, CampaignStatus targetState) {
        if (!isValidTransition(currentState, targetState)) {
            throw new InvalidStateTransitionException(
                    String.format("Invalid state transition from %s to %s", currentState, targetState)
            );
        }
    }

    /** May {@code currentState} go to RECOVERED with this attribution? A recovery always needs an attribution. */
    public boolean isValidRecovery(CampaignStatus currentState, RecoveryAttribution attribution) {
        if (attribution == null) {
            return false;
        }
        return isValidTransition(currentState, CampaignStatus.RECOVERED)
                || (attribution == RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE && LATE_CAPTURE_ONLY_RECOVERED_FROM.contains(currentState));
    }

    public void validateRecovery(CampaignStatus currentState, RecoveryAttribution attribution) {
        if (!isValidRecovery(currentState, attribution)) {
            throw new InvalidStateTransitionException(
                    String.format("Invalid recovery from %s with attribution %s", currentState, attribution)
            );
        }
    }

    public boolean isTerminal(CampaignStatus status) {
        return status == CampaignStatus.RECOVERED ||
               status == CampaignStatus.EXHAUSTED ||
               status == CampaignStatus.CANCELLED;
    }
}

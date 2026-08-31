package com.mend.statemachine;

import com.mend.domain.enums.CampaignStatus;
import com.mend.exception.InvalidStateTransitionException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class CampaignStateMachine {

    private final Map<CampaignStatus, Set<CampaignStatus>> allowedTransitions = new EnumMap<>(CampaignStatus.class);

    public CampaignStateMachine() {
        // Define explicit state transition matrix
        allowedTransitions.put(CampaignStatus.CREATED, EnumSet.of(CampaignStatus.CLASSIFIED, CampaignStatus.CANCELLED));
        allowedTransitions.put(CampaignStatus.CLASSIFIED, EnumSet.of(CampaignStatus.ELIGIBLE, CampaignStatus.EXHAUSTED, CampaignStatus.CANCELLED));
        allowedTransitions.put(CampaignStatus.ELIGIBLE, EnumSet.of(CampaignStatus.SCHEDULED, CampaignStatus.CANCELLED));
        allowedTransitions.put(CampaignStatus.SCHEDULED, EnumSet.of(CampaignStatus.ACTION_PENDING, CampaignStatus.CANCELLED));
        allowedTransitions.put(CampaignStatus.ACTION_PENDING, EnumSet.of(CampaignStatus.EXECUTING, CampaignStatus.CANCELLED));
        allowedTransitions.put(CampaignStatus.EXECUTING, EnumSet.of(CampaignStatus.RECOVERED, CampaignStatus.FAILED, CampaignStatus.CANCELLED));
        allowedTransitions.put(CampaignStatus.FAILED, EnumSet.of(CampaignStatus.SCHEDULED, CampaignStatus.EXHAUSTED, CampaignStatus.CANCELLED));

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

    public boolean isTerminal(CampaignStatus status) {
        return status == CampaignStatus.RECOVERED ||
               status == CampaignStatus.EXHAUSTED ||
               status == CampaignStatus.CANCELLED;
    }
}

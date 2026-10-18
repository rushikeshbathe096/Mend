package com.mend.exception;

import java.util.UUID;

/**
 * M4 U4.1 (AGENTS.md invariant 22): a recovery action was requested for a campaign in the holdout CONTROL arm. Not a
 * compliance rule; it extends {@link ComplianceBlockedException} so every caller's "no action allowed" handling applies.
 */
public class HoldoutControlException extends ComplianceBlockedException {

    public static final String CODE = "HOLDOUT_CONTROL";

    public HoldoutControlException(UUID campaignId) {
        super(CODE + ": campaign " + campaignId + " is in the holdout CONTROL arm; MEND takes no recovery action");
    }
}

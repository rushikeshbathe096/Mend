package com.mend.domain.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The one attempt-number rule shared by intent creation and the compliance duplicate check. */
class CampaignAttemptNumberTest {

    private static int attemptNumber(Integer attemptCount) {
        Campaign campaign = new Campaign();
        campaign.setAttemptCount(attemptCount);
        return campaign.currentAttemptNumber();
    }

    @Test
    void attemptCountBelowOneOrNullIsAttemptOne() {
        assertEquals(1, attemptNumber(null));
        assertEquals(1, attemptNumber(0));
        assertEquals(1, attemptNumber(-1));
    }

    @Test
    void attemptCountOfOneOrMoreIsUsedAsIs() {
        assertEquals(1, attemptNumber(1));
        assertEquals(3, attemptNumber(3));
    }
}

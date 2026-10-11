package com.mend.service;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** M2 U3: every fenced transition, applied and not applied, on real PostgreSQL. */
@DisplayName("ActionIntentTransitions (M2 fenced conditional updates)")
class ActionIntentTransitionsTest extends AbstractIntegrationTest {

    @Autowired private ActionIntentTransitions transitions;
    @Autowired private ActionIntentRepository intents;
    @Autowired private CampaignRepository campaigns;
    @Autowired private MerchantRepository merchants;
    @Autowired private JdbcTemplate jdbc;

    private UUID merchantId;
    private Campaign campaign;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "transitions merchant"));
        campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_m2t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaigns.save(campaign);
    }

    /**
     * M2 U6 fixture correction: these tests exercise single-intent transitions and used to put several in-flight intents on
     * one campaign. That shape is now impossible by design (one in flight per invoice, key = campaign key), so every
     * intent gets its own ACTION_PENDING campaign; {@code campaign} is the most recent one.
     */
    private ActionIntent intent(ActionIntentStatus status) {
        campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_m2t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaigns.save(campaign);
        return intents.save(new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, "REQUEST_CUSTOMER_ACTION",
                "CUSTOMER_ACTION_REQUIRED", null, status, "intent:" + UUID.randomUUID(), Instant.now().minusSeconds(5)));
    }

    private ActionIntent claimed(String token) {
        ActionIntent intent = intent(ActionIntentStatus.READY);
        assertTrue(transitions.claim(intent.getId(), "worker-t", token, Instant.now()));
        return intent;
    }

    private ActionIntent reload(ActionIntent intent) {
        return intents.findById(intent.getId()).orElseThrow();
    }

    @Test
    void claimAppliesOnceAndOnlyWhileCampaignIsActionPending() {
        ActionIntent intent = intent(ActionIntentStatus.READY);
        assertTrue(transitions.claim(intent.getId(), "w1", "t1", Instant.now()));
        assertFalse(transitions.claim(intent.getId(), "w2", "t2", Instant.now()), "second claim must not apply");
        assertEquals("t1", reload(intent).getClaimToken());

        ActionIntent other = intent(ActionIntentStatus.READY);
        jdbc.update("UPDATE campaigns SET current_state = 'EXECUTING' WHERE id = ?", campaign.getId());
        assertFalse(transitions.claim(other.getId(), "w3", "t3", Instant.now()), "campaign not ACTION_PENDING");
    }

    @Test
    void dispatchRequiresClaimedStatusAndTheHoldersToken() {
        ActionIntent intent = claimed("tok");
        assertFalse(transitions.dispatch(intent.getId(), "wrong", Instant.now()));
        assertFalse(transitions.dispatch(intent.getId(), null, Instant.now()));
        assertTrue(transitions.dispatch(intent.getId(), "tok", Instant.now()));
        ActionIntent after = reload(intent);
        assertEquals(ActionIntentStatus.EXECUTING, after.getStatus());
        assertNotNull(after.getDispatchedAt());
        assertEquals(1, after.getExecutionAttempts());
        assertFalse(transitions.dispatch(intent.getId(), "tok", Instant.now()), "already EXECUTING");
    }

    @Test
    void releaseOnlyAppliesToUndispatchedExpiredClaims() {
        Instant threshold = Instant.now().plus(Duration.ofMinutes(1));
        ActionIntent undispatched = claimed("a");
        assertTrue(transitions.releaseIfUndispatched(undispatched.getId(), threshold));
        ActionIntent released = reload(undispatched);
        assertEquals(ActionIntentStatus.READY, released.getStatus());
        assertNull(released.getClaimToken());

        ActionIntent dispatched = claimed("b");
        assertTrue(transitions.dispatch(dispatched.getId(), "b", Instant.now()));
        assertFalse(transitions.releaseIfUndispatched(dispatched.getId(), threshold), "EXECUTING is never released");
        assertEquals(ActionIntentStatus.EXECUTING, reload(dispatched).getStatus());

        ActionIntent fresh = claimed("c");
        assertFalse(transitions.releaseIfUndispatched(fresh.getId(), Instant.now().minus(Duration.ofMinutes(5))), "lease not expired");
    }

    @Test
    void finalizeRequiresExecutingAndTheHoldersToken() {
        ActionIntent intent = claimed("tok");
        Instant later = Instant.now().plusSeconds(30);
        assertFalse(transitions.finalizeFromExecuting(intent.getId(), "tok", ActionIntentStatus.AWAITING_PAYMENT, "plink_x", null, later),
                "still CLAIMED");
        assertTrue(transitions.dispatch(intent.getId(), "tok", Instant.now()));
        assertFalse(transitions.finalizeFromExecuting(intent.getId(), "stale", ActionIntentStatus.AWAITING_PAYMENT, "plink_x", null, later));
        assertTrue(transitions.finalizeFromExecuting(intent.getId(), "tok", ActionIntentStatus.AWAITING_PAYMENT, "plink_x", null, later));
        ActionIntent after = reload(intent);
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, after.getStatus());
        assertEquals("plink_x", after.getResponseReference());
        assertNotNull(after.getReconcileAfter());
        assertThrows(IllegalArgumentException.class,
                () -> transitions.finalizeFromExecuting(intent.getId(), "tok", ActionIntentStatus.READY, null, null, null));
    }

    @Test
    void abandonClaimOnlyBeforeDispatchByTheHolder() {
        ActionIntent intent = claimed("tok");
        assertFalse(transitions.abandonClaim(intent.getId(), "wrong", ActionIntentStatus.FAILED, Instant.now()));
        assertTrue(transitions.abandonClaim(intent.getId(), "tok", ActionIntentStatus.FAILED, Instant.now()));
        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertThrows(IllegalArgumentException.class,
                () -> transitions.abandonClaim(intent.getId(), "tok", ActionIntentStatus.READY, Instant.now()));

        ActionIntent dispatched = claimed("d");
        assertTrue(transitions.dispatch(dispatched.getId(), "d", Instant.now()));
        assertFalse(transitions.abandonClaim(dispatched.getId(), "d", ActionIntentStatus.FAILED, Instant.now()), "dispatched");
    }

    @Test
    void promoteExpireAndCancelAreStatusFenced() {
        ActionIntent scheduled = intent(ActionIntentStatus.SCHEDULED);
        assertTrue(transitions.promote(scheduled.getId()));
        assertFalse(transitions.promote(scheduled.getId()));

        ActionIntent expiring = intent(ActionIntentStatus.READY);
        jdbc.update("UPDATE action_intents SET expires_at = now() - interval '1 minute' WHERE id = ?", expiring.getId());
        assertTrue(transitions.expire(expiring.getId(), Instant.now()));
        ActionIntent claimedExpired = claimed("e");
        jdbc.update("UPDATE action_intents SET expires_at = now() - interval '1 minute' WHERE id = ?", claimedExpired.getId());
        assertFalse(transitions.expire(claimedExpired.getId(), Instant.now()), "claimed work is not window-expired");

        ActionIntent ready = intent(ActionIntentStatus.READY);
        assertTrue(transitions.cancelNonTerminal(ready.getId(), Instant.now()));
        assertFalse(transitions.cancelNonTerminal(ready.getId(), Instant.now()), "already terminal");
    }

    @Test
    void recordEvidenceAppliesOnceDuplicateIsNoOp() {
        ActionIntent intent = intent(ActionIntentStatus.AWAITING_PAYMENT);
        java.util.Set<ActionIntentStatus> inFlight = java.util.EnumSet.of(ActionIntentStatus.EXECUTING,
                ActionIntentStatus.AWAITING_PAYMENT, ActionIntentStatus.NEEDS_RECONCILIATION);
        Instant first = Instant.now().minusSeconds(60);

        assertTrue(transitions.recordEvidence(intent.getId(), inFlight, ActionIntentStatus.SUCCEEDED, "pay_first", first));
        assertFalse(transitions.recordEvidence(intent.getId(), inFlight, ActionIntentStatus.SUCCEEDED, "pay_second", Instant.now()));

        ActionIntent after = reload(intent);
        assertEquals(ActionIntentStatus.SUCCEEDED, after.getStatus());
        assertEquals("pay_first", after.getResponseReference());
        assertEquals(first.toEpochMilli(), after.getCompletedAt().toEpochMilli());
    }

    @Test
    void recordEvidenceRejectsDisallowedSourcesAndTargets() {
        ActionIntent intent = intent(ActionIntentStatus.CLAIMED);
        assertThrows(IllegalArgumentException.class, () -> transitions.recordEvidence(intent.getId(),
                java.util.EnumSet.of(ActionIntentStatus.CLAIMED), ActionIntentStatus.SUCCEEDED, null, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> transitions.recordEvidence(intent.getId(),
                java.util.EnumSet.of(ActionIntentStatus.EXECUTING), ActionIntentStatus.READY, null, Instant.now()));
        assertEquals(ActionIntentStatus.CLAIMED, reload(intent).getStatus());
    }
}

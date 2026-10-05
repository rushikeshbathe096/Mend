package com.mend.integration.m2;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.AuditLog;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ComplianceDecisionEntity;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceReason;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.AuditLogRepository;
import com.mend.domain.repository.CampaignAttemptRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.ComplianceDecisionRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.provider.ProviderAttemptKey;
import com.mend.service.ActionExecutionService;
import com.mend.service.ActionIntentClaimService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** M2 U3: the executor's dispatch and finalize fences on real PostgreSQL with a provider held mid-call. */
@Import(ExecutionSafetyScenarioTest.BlockingProviderConfig.class)
class ExecutionFenceTest extends AbstractIntegrationTest {

    @Autowired private ActionExecutionService executor;
    @Autowired private ActionIntentClaimService claimService;
    @Autowired private ActionIntentRepository intents;
    @Autowired private CampaignRepository campaigns;
    @Autowired private CampaignAttemptRepository attempts;
    @Autowired private MerchantRepository merchants;
    @Autowired private ComplianceDecisionRepository compliance;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private AuditLogRepository audit;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BlockingPaymentProvider provider;

    private ExecutorService threads;
    private UUID merchantId;

    @BeforeEach
    void setUp() {
        threads = Executors.newSingleThreadExecutor();
        provider.reset();
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "fence merchant"));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        provider.reset();
        threads.shutdownNow();
        threads.awaitTermination(10, TimeUnit.SECONDS);
    }

    private ActionIntent claimedLinkIntent(Campaign[] campaignOut) {
        String paymentId = "pay_m2f" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId(paymentId);
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaigns.save(campaign);
        campaignOut[0] = campaign;
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                "{\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"amount\":49900,\"currency\":\"INR\"}}}}");
        event.setMerchantId(merchantId);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhooks.save(event);
        ComplianceDecisionEntity decision = compliance.save(new ComplianceDecisionEntity(UUID.randomUUID(), campaign.getId(), merchantId,
                null, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, ComplianceStatus.COMPLIANCE_ALLOWED, ComplianceReason.ALLOWED, "ok", "v1"));
        ActionIntent intent = intents.save(new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1,
                ActionType.REQUEST_CUSTOMER_ACTION.name(), RecoveryStrategy.CUSTOMER_ACTION_REQUIRED.name(), decision.getId(),
                ActionIntentStatus.READY, "intent:" + campaign.getId() + ":attempt_1:REQUEST_CUSTOMER_ACTION", Instant.now().minusSeconds(5)));
        return claimService.claimSpecificIntent(intent.getId(), "fence-worker").orElseThrow();
    }

    private boolean audited(UUID campaignId, String type) {
        return audit.findByCampaignIdOrderByCreatedAtAsc(campaignId).stream().map(AuditLog::getEventType).anyMatch(type::equals);
    }

    @Test
    @DisplayName("dispatch fence lost (claim token changed) -> no provider call, prepare rolled back, audited")
    void lostDispatchMakesNoProviderCall() {
        Campaign[] c = new Campaign[1];
        ActionIntent intent = claimedLinkIntent(c);
        jdbc.update("UPDATE action_intents SET claim_token = 'taken-over' WHERE id = ?", intent.getId());

        // The worker presents the token it claimed with; the stored token now belongs to someone else.
        PaymentExecutionResult report = executor.executeActionIntent(intent.getId(), "fence-worker", intent.getClaimToken());

        assertEquals("CLAIM_LOST_BEFORE_DISPATCH", report.getResponseCode());
        assertEquals(0, provider.createCalls(ProviderAttemptKey.forIntent(intent.getId())));
        assertEquals(ActionIntentStatus.CLAIMED, intents.findById(intent.getId()).orElseThrow().getStatus());
        assertEquals(CampaignStatus.ACTION_PENDING, campaigns.findById(c[0].getId()).orElseThrow().getCurrentState(),
                "the campaign transition must roll back with the lost dispatch");
        assertTrue(audited(c[0].getId(), "CLAIM_LOST_BEFORE_DISPATCH"));
    }

    @Test
    @DisplayName("finalize fence lost (another owner mid-call) -> result discarded, audited, campaign and attempts untouched")
    void lostFinalizeDiscardsResult() throws Exception {
        Campaign[] c = new Campaign[1];
        ActionIntent intent = claimedLinkIntent(c);
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());
        provider.blockFirstCreate(key);
        Future<PaymentExecutionResult> worker = threads.submit(() -> executor.executeActionIntent(intent.getId(), "fence-worker", intent.getClaimToken()));
        assertTrue(provider.awaitEntered(key));

        // Another actor now owns the intent (simulated: the token no longer matches the worker's).
        jdbc.update("UPDATE action_intents SET claim_token = 'other-owner' WHERE id = ?", intent.getId());
        provider.release(key);
        worker.get(10, TimeUnit.SECONDS);

        ActionIntent after = intents.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.EXECUTING, after.getStatus(), "the stale worker must not finalize");
        assertNull(after.getResponseReference());
        assertTrue(audited(c[0].getId(), "STALE_WORKER_RESULT_DISCARDED"));
        assertTrue(attempts.findByCampaignId(c[0].getId()).isEmpty(), "no campaign_attempts row from a discarded result");
        assertEquals(CampaignStatus.EXECUTING, campaigns.findById(c[0].getId()).orElseThrow().getCurrentState());
    }
}

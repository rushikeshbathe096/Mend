package com.mend.compliance;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceReason;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.RecoveryStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compliance Engine Unit Tests")
public class ComplianceEngineTest {

    private ComplianceEngine engine;
    private UUID merchantId;
    private UUID campaignId;

    @BeforeEach
    void setUp() {
        engine = new ComplianceEngine(new BigDecimal("0.80"));
        merchantId = UUID.randomUUID();
        campaignId = UUID.randomUUID();
    }

    private Campaign createCampaign(CampaignStatus state, int attemptCount, String failureClass, BigDecimal confidence) {
        Campaign campaign = new Campaign(campaignId, merchantId);
        campaign.setCurrentState(state);
        campaign.setAttemptCount(attemptCount);
        campaign.setFailureClass(failureClass);
        campaign.setConfidence(confidence);
        return campaign;
    }

    private ClassificationResult createClassification(String failureClass, BigDecimal confidence) {
        return new ClassificationResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                campaignId,
                failureClass,
                confidence,
                "RECOMMENDATION",
                "Reasoning",
                "v1.0.0"
        );
    }

    @Test
    @DisplayName("1. Valid RETRY_LATER -> COMPLIANCE_ALLOWED")
    void testValidRetryLaterAllowed() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ClassificationResult classification = createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ComplianceContext context = new ComplianceContext(merchantId, campaign, RecoveryStrategy.RETRY_LATER, classification, null, UUID.randomUUID(), false);

        ComplianceDecision decision = engine.evaluate(context);

        assertTrue(decision.isAllowed());
        assertEquals(ComplianceStatus.COMPLIANCE_ALLOWED, decision.getStatus());
        assertEquals(ComplianceReason.ALLOWED, decision.getReason());
        assertEquals(ComplianceEngine.POLICY_VERSION, decision.getPolicyVersion());
    }

    @Test
    @DisplayName("2. Valid RETRY_IMMEDIATELY -> COMPLIANCE_ALLOWED")
    void testValidRetryImmediatelyAllowed() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "NETWORK_FAILURE", new BigDecimal("0.95"));
        ClassificationResult classification = createClassification("NETWORK_FAILURE", new BigDecimal("0.95"));
        ComplianceContext context = new ComplianceContext(merchantId, campaign, RecoveryStrategy.RETRY_IMMEDIATELY, classification, null, UUID.randomUUID(), false);

        ComplianceDecision decision = engine.evaluate(context);

        assertTrue(decision.isAllowed());
        assertEquals(ComplianceReason.ALLOWED, decision.getReason());
    }

    @Test
    @DisplayName("3. Maximum attempts reached -> COMPLIANCE_BLOCKED (MAX_ATTEMPTS_EXCEEDED)")
    void testMaxAttemptsReachedBlocked() {
        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setMaxAttempts(3);

        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 3, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ComplianceContext context = new ComplianceContext(merchantId, campaign, RecoveryStrategy.RETRY_LATER, createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90")), config, UUID.randomUUID(), false);

        ComplianceDecision decision = engine.evaluate(context);

        assertTrue(decision.isBlocked());
        assertEquals(ComplianceReason.MAX_ATTEMPTS_EXCEEDED, decision.getReason());
    }

    @Test
    @DisplayName("4. Low confidence -> COMPLIANCE_BLOCKED (LOW_CONFIDENCE)")
    void testLowConfidenceBlocked() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.70"));
        ClassificationResult classification = createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.70"));
        ComplianceContext context = new ComplianceContext(merchantId, campaign, RecoveryStrategy.RETRY_LATER, classification, null, UUID.randomUUID(), false);

        ComplianceDecision decision = engine.evaluate(context);

        assertTrue(decision.isBlocked());
        assertEquals(ComplianceReason.LOW_CONFIDENCE, decision.getReason());
    }

    @Test
    @DisplayName("5. Unknown classification -> COMPLIANCE_BLOCKED (UNKNOWN_CLASSIFICATION)")
    void testUnknownClassificationBlocked() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "UNKNOWN", new BigDecimal("0.90"));
        ComplianceContext context = new ComplianceContext(merchantId, campaign, RecoveryStrategy.RETRY_LATER, createClassification("UNKNOWN", new BigDecimal("0.90")), null, UUID.randomUUID(), false);

        ComplianceDecision decision = engine.evaluate(context);

        assertTrue(decision.isBlocked());
        assertEquals(ComplianceReason.UNKNOWN_CLASSIFICATION, decision.getReason());
    }

    @Test
    @DisplayName("6. Unsupported strategy -> COMPLIANCE_BLOCKED")
    void testUnsupportedStrategyBlocked() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ComplianceContext context = new ComplianceContext(merchantId, campaign, null, createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90")), null, UUID.randomUUID(), false);

        ComplianceDecision decision = engine.evaluate(context);

        assertTrue(decision.isBlocked());
        assertEquals(ComplianceReason.MISSING_CONTEXT, decision.getReason());
    }

    @Test
    @DisplayName("7 & 8. Terminal / Invalid campaign state -> COMPLIANCE_BLOCKED (INVALID_CAMPAIGN_STATE)")
    void testInvalidCampaignStateBlocked() {
        // Terminal state EXHAUSTED
        Campaign campaignExhausted = createCampaign(CampaignStatus.EXHAUSTED, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ComplianceDecision decisionExhausted = engine.evaluate(new ComplianceContext(merchantId, campaignExhausted, RecoveryStrategy.RETRY_LATER, null, null, UUID.randomUUID(), false));
        assertTrue(decisionExhausted.isBlocked());
        assertEquals(ComplianceReason.INVALID_CAMPAIGN_STATE, decisionExhausted.getReason());

        // Non-ready state CREATED
        Campaign campaignCreated = createCampaign(CampaignStatus.CREATED, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ComplianceDecision decisionCreated = engine.evaluate(new ComplianceContext(merchantId, campaignCreated, RecoveryStrategy.RETRY_LATER, null, null, UUID.randomUUID(), false));
        assertTrue(decisionCreated.isBlocked());
        assertEquals(ComplianceReason.INVALID_CAMPAIGN_STATE, decisionCreated.getReason());
    }

    @Test
    @DisplayName("9. Duplicate action -> COMPLIANCE_BLOCKED (DUPLICATE_ACTION)")
    void testDuplicateActionBlocked() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ComplianceContext context = new ComplianceContext(merchantId, campaign, RecoveryStrategy.RETRY_LATER, createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90")), null, UUID.randomUUID(), true);

        ComplianceDecision decision = engine.evaluate(context);

        assertTrue(decision.isBlocked());
        assertEquals(ComplianceReason.DUPLICATE_ACTION, decision.getReason());
    }

    @Test
    @DisplayName("10. Merchant policy disables recovery -> COMPLIANCE_BLOCKED (MERCHANT_POLICY_BLOCKED)")
    void testMerchantPolicyDisablesRecoveryBlocked() {
        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setEnabledRecoveryActions("CUSTOMER_ACTION_REQUIRED"); // Disables RETRY_LATER

        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ComplianceContext context = new ComplianceContext(merchantId, campaign, RecoveryStrategy.RETRY_LATER, createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90")), config, UUID.randomUUID(), false);

        ComplianceDecision decision = engine.evaluate(context);

        assertTrue(decision.isBlocked());
        assertEquals(ComplianceReason.MERCHANT_POLICY_BLOCKED, decision.getReason());
    }

    @Test
    @DisplayName("11. Tenant Mismatch -> COMPLIANCE_BLOCKED (TENANT_MISMATCH)")
    void testTenantMismatchBlocked() {
        UUID otherMerchantId = UUID.randomUUID();
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ComplianceContext context = new ComplianceContext(otherMerchantId, campaign, RecoveryStrategy.RETRY_LATER, createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90")), null, UUID.randomUUID(), false);

        ComplianceDecision decision = engine.evaluate(context);

        assertTrue(decision.isBlocked());
        assertEquals(ComplianceReason.TENANT_MISMATCH, decision.getReason());
    }

    @Test
    @DisplayName("15 & 16. NO_ACTION and MANUAL_REVIEW -> COMPLIANCE_BLOCKED (STRATEGY_NOT_SUPPORTED)")
    void testNoActionAndManualReviewBlocked() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ClassificationResult classification = createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90"));

        // NO_ACTION
        ComplianceDecision decisionNoAction = engine.evaluate(new ComplianceContext(merchantId, campaign, RecoveryStrategy.NO_ACTION, classification, null, UUID.randomUUID(), false));
        assertTrue(decisionNoAction.isBlocked());
        assertEquals(ComplianceReason.STRATEGY_NOT_SUPPORTED, decisionNoAction.getReason());

        // MANUAL_REVIEW
        ComplianceDecision decisionManual = engine.evaluate(new ComplianceContext(merchantId, campaign, RecoveryStrategy.MANUAL_REVIEW, classification, null, UUID.randomUUID(), false));
        assertTrue(decisionManual.isBlocked());
        assertEquals(ComplianceReason.STRATEGY_NOT_SUPPORTED, decisionManual.getReason());
    }
}

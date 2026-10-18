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
        engine = new ComplianceEngine();
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

    /**
     * A deterministic (taxonomy) classification row. The {@code confidence} argument is kept for the pre-M3 call sites; a
     * deterministic row ignores it (M3 behaviour change, ADR-003 §4).
     */
    private ClassificationResult createClassification(String failureClass, BigDecimal confidence) {
        ClassificationResult result = new ClassificationResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                campaignId,
                failureClass,
                confidence,
                null,
                "Reasoning",
                null
        );
        result.setTaxonomyVersion(com.mend.taxonomy.FailureTaxonomy.TABLE_VERSION);
        return result;
    }

    /** A pre-M3, AI-classified row: no taxonomy version, an AI confidence and model version. */
    private ClassificationResult legacyClassification(String failureClass, BigDecimal confidence) {
        return new ClassificationResult(UUID.randomUUID(), UUID.randomUUID(), campaignId, failureClass, confidence,
                "RECOMMENDATION", "Reasoning", "v1.0.0");
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
        // M3 behaviour change (ADR-003 §4): NETWORK_FAILURE is a pre-M3 (AI-era) class and now fails closed; its taxonomy
        // equivalent GATEWAY_ERROR is the documented transient class (was NETWORK_FAILURE / 0.95).
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "GATEWAY_ERROR", null);
        ClassificationResult classification = createClassification("GATEWAY_ERROR", null);
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
    @DisplayName("4. Confidence is never consulted: a documented class with a low (AI-era) or no confidence -> ALLOWED")
    void testConfidenceNotConsulted() {
        // M3 behaviour change (ADR-003 §4): the 0.80 LOW_CONFIDENCE gate is removed with the fabricated confidence; the
        // block on undocumented classes (test 5, 5b) replaces it. Was: 0.70 -> BLOCKED (LOW_CONFIDENCE).
        for (BigDecimal confidence : new BigDecimal[]{new BigDecimal("0.70"), null}) {
            Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", confidence);
            ClassificationResult classification = createClassification("INSUFFICIENT_FUNDS", confidence);
            ComplianceContext context = new ComplianceContext(merchantId, campaign, RecoveryStrategy.RETRY_LATER, classification, null, UUID.randomUUID(), false);

            ComplianceDecision decision = engine.evaluate(context);

            assertTrue(decision.isAllowed(), "confidence " + confidence);
            assertNotEquals(ComplianceReason.LOW_CONFIDENCE, decision.getReason());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    // M3 behaviour change (owner decision B, ADR-003 §4): UNCLASSIFIED is no longer blocked as UNKNOWN_CLASSIFICATION; it
    // may get a payment link and is blocked only for a retry (testUnclassifiedLinkOnly). Pre-M3 classes stay blocked.
    @org.junit.jupiter.params.provider.ValueSource(strings = {"BANK_DECLINED", "NETWORK_FAILURE", "CARD_EXPIRED", "AUTHENTICATION_FAILED"})
    @DisplayName("5b. Every pre-M3 class -> COMPLIANCE_BLOCKED (UNKNOWN_CLASSIFICATION), whatever the confidence")
    void testUndocumentedClassBlocked(String failureClass) {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, failureClass, new BigDecimal("0.99"));
        ComplianceContext context = new ComplianceContext(merchantId, campaign, RecoveryStrategy.RETRY_LATER,
                createClassification(failureClass, new BigDecimal("0.99")), null, UUID.randomUUID(), false);

        ComplianceDecision decision = engine.evaluate(context);

        assertTrue(decision.isBlocked());
        assertEquals(ComplianceReason.UNKNOWN_CLASSIFICATION, decision.getReason());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"INSUFFICIENT_FUNDS", "LIMIT_EXCEEDED"})
    @DisplayName("U2.2 review fix: a pre-M3 AI row whose class name matches a taxonomy class is still blocked (fail closed)")
    void testLegacyRowWithTaxonomyNameBlocked(String failureClass) {
        for (BigDecimal confidence : new BigDecimal[]{new BigDecimal("0.55"), new BigDecimal("0.95")}) {
            Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, failureClass, confidence);
            ComplianceContext context = new ComplianceContext(merchantId, campaign, RecoveryStrategy.RETRY_LATER,
                    legacyClassification(failureClass, confidence), null, UUID.randomUUID(), false);

            ComplianceDecision decision = engine.evaluate(context);

            assertTrue(decision.isBlocked(), failureClass + " @ " + confidence);
            assertEquals(ComplianceReason.UNKNOWN_CLASSIFICATION, decision.getReason());
        }
        // and with no classification row at all, the campaign's own class never authorizes
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, failureClass, null);
        assertEquals(ComplianceReason.UNKNOWN_CLASSIFICATION, engine.evaluate(new ComplianceContext(merchantId, campaign,
                RecoveryStrategy.RETRY_LATER, null, null, UUID.randomUUID(), false)).getReason());
    }

    @Test
    @DisplayName("U2.2 review fix: a legacy class is blocked before the strategy checks (no unapprovable MANUAL_REVIEW review)")
    void testLegacyClassBlockedBeforeStrategyRules() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "BANK_DECLINED", new BigDecimal("0.95"));
        ComplianceDecision decision = engine.evaluate(new ComplianceContext(merchantId, campaign, RecoveryStrategy.MANUAL_REVIEW,
                legacyClassification("BANK_DECLINED", new BigDecimal("0.95")), null, UUID.randomUUID(), false));
        assertEquals(ComplianceReason.UNKNOWN_CLASSIFICATION, decision.getReason());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = RecoveryStrategy.class, names = {"RETRY_IMMEDIATELY", "RETRY_LATER"})
    @DisplayName("Decision B: a retry for UNCLASSIFIED is blocked by compliance, whatever the strategy table says")
    void testUnclassifiedRetryBlocked(RecoveryStrategy retry) {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "UNCLASSIFIED", null);
        ComplianceDecision decision = engine.evaluate(new ComplianceContext(merchantId, campaign, retry,
                createClassification("UNCLASSIFIED", null), null, UUID.randomUUID(), false));
        assertTrue(decision.isBlocked());
        assertEquals(ComplianceReason.RETRY_NOT_ALLOWED_UNCLASSIFIED, decision.getReason());
    }

    @Test
    @DisplayName("Decision B: UNCLASSIFIED with a payment link is allowed; a legacy UNCLASSIFIED row stays blocked")
    void testUnclassifiedLinkOnly() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "UNCLASSIFIED", null);
        ComplianceDecision link = engine.evaluate(new ComplianceContext(merchantId, campaign, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED,
                createClassification("UNCLASSIFIED", null), null, UUID.randomUUID(), false));
        assertFalse(link.isBlocked(), String.valueOf(link.getReason()));

        ComplianceDecision legacy = engine.evaluate(new ComplianceContext(merchantId, campaign, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED,
                legacyClassification("UNCLASSIFIED", new BigDecimal("0.99")), null, UUID.randomUUID(), false));
        assertEquals(ComplianceReason.UNKNOWN_CLASSIFICATION, legacy.getReason(), "a row without a taxonomy version fails closed");
    }
}


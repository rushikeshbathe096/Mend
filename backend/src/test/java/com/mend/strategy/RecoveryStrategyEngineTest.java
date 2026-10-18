package com.mend.strategy;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Recovery Strategy Engine Unit Tests")
public class RecoveryStrategyEngineTest {

    private RecoveryStrategyEngine engine;
    private UUID merchantId;
    private UUID campaignId;

    @BeforeEach
    void setUp() {
        engine = new RecoveryStrategyEngine();
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
    @DisplayName("1. INSUFFICIENT_FUNDS -> RETRY_LATER on initial attempts")
    void testInsufficientFundsRule() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ClassificationResult classification = createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        RecoveryContext context = new RecoveryContext(campaign, classification, null);

        RecoveryDecision decision = engine.evaluate(context);

        assertEquals(RecoveryStrategy.RETRY_LATER, decision.getStrategy());
        assertEquals("NORMAL", decision.getPriority());
        assertEquals(RecoveryStrategyEngine.POLICY_VERSION, decision.getPolicyVersion());
        assertTrue(decision.getReason().contains("insufficient funds"));
    }

    // M3 behaviour change (ADR-003 §4): tests 2-6 were written against AI-era classes and the 0.80 confidence rule. Each is
    // kept and now asserts the deterministic taxonomy class that replaced it; the AI-era class itself fails closed.

    @Test
    @DisplayName("2. BANK_TECHNICAL / GATEWAY_ERROR -> RETRY_IMMEDIATELY at attempt 0, RETRY_LATER at 1+ (was BANK_DECLINED; now MANUAL_REVIEW)")
    void testBankDeclinedRule() {
        for (String transientClass : new String[]{"BANK_TECHNICAL", "GATEWAY_ERROR"}) {
            Campaign campaign0 = createCampaign(CampaignStatus.ELIGIBLE, 0, transientClass, null);
            assertEquals(RecoveryStrategy.RETRY_IMMEDIATELY,
                    engine.evaluate(new RecoveryContext(campaign0, createClassification(transientClass, null), null)).getStrategy());
            Campaign campaign1 = createCampaign(CampaignStatus.ELIGIBLE, 1, transientClass, null);
            assertEquals(RecoveryStrategy.RETRY_LATER,
                    engine.evaluate(new RecoveryContext(campaign1, createClassification(transientClass, null), null)).getStrategy());
        }
        Campaign legacy = createCampaign(CampaignStatus.ELIGIBLE, 0, "BANK_DECLINED", new BigDecimal("0.85"));
        assertEquals(RecoveryStrategy.MANUAL_REVIEW,
                engine.evaluate(new RecoveryContext(legacy, createClassification("BANK_DECLINED", new BigDecimal("0.85")), null)).getStrategy());
    }

    @Test
    @DisplayName("3. INSTRUMENT_INVALID -> CUSTOMER_ACTION_REQUIRED, HIGH (was CARD_EXPIRED)")
    void testCardExpiredRule() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSTRUMENT_INVALID", null);
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification("INSTRUMENT_INVALID", null), null));

        assertEquals(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, decision.getStrategy());
        assertEquals("HIGH", decision.getPriority());
    }

    @Test
    @DisplayName("4. AUTHENTICATION_FAILURE -> CUSTOMER_ACTION_REQUIRED (was AUTHENTICATION_FAILED)")
    void testAuthenticationFailedRule() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "AUTHENTICATION_FAILURE", null);
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification("AUTHENTICATION_FAILURE", null), null));

        assertEquals(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, decision.getStrategy());
    }

    @Test
    @DisplayName("5. UNCLASSIFIED and legacy UNKNOWN -> MANUAL_REVIEW")
    void testUnknownRule() {
        for (String c : new String[]{"UNCLASSIFIED", "UNKNOWN"}) {
            Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, c, new BigDecimal("0.85"));
            RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification(c, new BigDecimal("0.85")), null));
            assertEquals(RecoveryStrategy.MANUAL_REVIEW, decision.getStrategy(), c);
        }
    }

    @Test
    @DisplayName("6. Confidence is never consulted (was: low AI confidence -> MANUAL_REVIEW); the decision records no confidence")
    void testLowConfidenceRule() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.60"));
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.60")), null));

        assertEquals(RecoveryStrategy.RETRY_LATER, decision.getStrategy());
        assertNull(decision.getConfidence(), "no confidence is copied into the strategy decision");
        assertFalse(decision.getReason().contains("threshold"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} @ attempt {1} -> {2}")
    @org.junit.jupiter.params.provider.CsvSource({
            "INSUFFICIENT_FUNDS, 0, RETRY_LATER",
            "INSUFFICIENT_FUNDS, 1, RETRY_LATER",
            "INSUFFICIENT_FUNDS, 2, CUSTOMER_ACTION_REQUIRED",
            "LIMIT_EXCEEDED, 0, CUSTOMER_ACTION_REQUIRED",
            "BANK_TECHNICAL, 0, RETRY_IMMEDIATELY",
            "BANK_TECHNICAL, 1, RETRY_LATER",
            "GATEWAY_ERROR, 0, RETRY_IMMEDIATELY",
            "GATEWAY_ERROR, 1, RETRY_LATER",
            "AUTHENTICATION_FAILURE, 0, CUSTOMER_ACTION_REQUIRED",
            "CUSTOMER_DECLINED, 0, CUSTOMER_ACTION_REQUIRED",
            "INSTRUMENT_INVALID, 0, CUSTOMER_ACTION_REQUIRED",
            "MANDATE_INVALID, 0, CUSTOMER_ACTION_REQUIRED",
            "UNCLASSIFIED, 0, MANUAL_REVIEW",
    })
    @DisplayName("ADR-003 §4 strategy table, one row per taxonomy class")
    void strategyTablePerTaxonomyClass(String failureClass, int attempts, RecoveryStrategy expected) {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, attempts, failureClass, null);
        assertEquals(expected, engine.evaluate(new RecoveryContext(campaign, createClassification(failureClass, null), null)).getStrategy());
    }

    @Test
    @DisplayName("7. Exhausted / terminal campaign -> NO_ACTION")
    void testTerminalCampaignRule() {
        Campaign campaign = createCampaign(CampaignStatus.EXHAUSTED, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90")), null));

        assertEquals(RecoveryStrategy.NO_ACTION, decision.getStrategy());
        assertTrue(decision.getReason().contains("terminal state"));
    }

    @Test
    @DisplayName("8. Invalid campaign state (CREATED) -> MANUAL_REVIEW safe default")
    void testInvalidCampaignStateRule() {
        Campaign campaign = createCampaign(CampaignStatus.CREATED, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90")), null));

        assertEquals(RecoveryStrategy.MANUAL_REVIEW, decision.getStrategy());
        assertTrue(decision.getReason().contains("not ready"));
    }

    @Test
    @DisplayName("9. Maximum attempts reached -> NO_ACTION")
    void testMaxAttemptsReachedRule() {
        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setMaxAttempts(3);

        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 3, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90")), config));

        assertEquals(RecoveryStrategy.NO_ACTION, decision.getStrategy());
        assertTrue(decision.getReason().contains("Maximum recovery attempts reached"));
    }

    @Test
    @DisplayName("10. Merchant configuration restriction modifies strategy")
    void testMerchantConfigurationRestriction() {
        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setEnabledRecoveryActions("CUSTOMER_ACTION_REQUIRED"); // Disables RETRY_LATER

        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.90")), config));

        // Since RETRY_LATER is disabled by merchant config, it falls back safely to MANUAL_REVIEW
        assertEquals(RecoveryStrategy.MANUAL_REVIEW, decision.getStrategy());
        assertTrue(decision.getReason().contains("disabled by merchant configuration"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"INSUFFICIENT_FUNDS", "LIMIT_EXCEEDED"})
    @DisplayName("U2.2 review fix: a pre-M3 AI row whose class name matches a taxonomy class -> MANUAL_REVIEW (fail closed)")
    void legacyRowWithTaxonomyNameIsUnclassified(String failureClass) {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, failureClass, new BigDecimal("0.95"));
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, legacyClassification(failureClass, new BigDecimal("0.95")), null));
        assertEquals(RecoveryStrategy.MANUAL_REVIEW, decision.getStrategy());
        assertEquals(RecoveryStrategy.MANUAL_REVIEW, engine.evaluate(new RecoveryContext(campaign, null, null)).getStrategy(),
                "no classification row: the campaign's own class never decides");
    }
}

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
        engine = new RecoveryStrategyEngine(new BigDecimal("0.80"));
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

    @Test
    @DisplayName("2. BANK_DECLINED -> RETRY_LATER for attempt 0, CUSTOMER_ACTION_REQUIRED for attempt 1+")
    void testBankDeclinedRule() {
        // Attempt 0
        Campaign campaign0 = createCampaign(CampaignStatus.ELIGIBLE, 0, "BANK_DECLINED", new BigDecimal("0.85"));
        RecoveryDecision decision0 = engine.evaluate(new RecoveryContext(campaign0, createClassification("BANK_DECLINED", new BigDecimal("0.85")), null));
        assertEquals(RecoveryStrategy.RETRY_LATER, decision0.getStrategy());

        // Attempt 1
        Campaign campaign1 = createCampaign(CampaignStatus.ELIGIBLE, 1, "BANK_DECLINED", new BigDecimal("0.85"));
        RecoveryDecision decision1 = engine.evaluate(new RecoveryContext(campaign1, createClassification("BANK_DECLINED", new BigDecimal("0.85")), null));
        assertEquals(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, decision1.getStrategy());
    }

    @Test
    @DisplayName("3. CARD_EXPIRED -> CUSTOMER_ACTION_REQUIRED")
    void testCardExpiredRule() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "CARD_EXPIRED", new BigDecimal("0.95"));
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification("CARD_EXPIRED", new BigDecimal("0.95")), null));

        assertEquals(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, decision.getStrategy());
        assertEquals("HIGH", decision.getPriority());
    }

    @Test
    @DisplayName("4. AUTHENTICATION_FAILED -> CUSTOMER_ACTION_REQUIRED")
    void testAuthenticationFailedRule() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "AUTHENTICATION_FAILED", new BigDecimal("0.90"));
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification("AUTHENTICATION_FAILED", new BigDecimal("0.90")), null));

        assertEquals(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, decision.getStrategy());
    }

    @Test
    @DisplayName("5. UNKNOWN -> MANUAL_REVIEW")
    void testUnknownRule() {
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "UNKNOWN", new BigDecimal("0.85"));
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification("UNKNOWN", new BigDecimal("0.85")), null));

        assertEquals(RecoveryStrategy.MANUAL_REVIEW, decision.getStrategy());
    }

    @Test
    @DisplayName("6. LOW AI confidence -> MANUAL_REVIEW")
    void testLowConfidenceRule() {
        // Confidence 0.60 is below min-confidence threshold 0.80
        Campaign campaign = createCampaign(CampaignStatus.ELIGIBLE, 0, "INSUFFICIENT_FUNDS", new BigDecimal("0.60"));
        RecoveryDecision decision = engine.evaluate(new RecoveryContext(campaign, createClassification("INSUFFICIENT_FUNDS", new BigDecimal("0.60")), null));

        assertEquals(RecoveryStrategy.MANUAL_REVIEW, decision.getStrategy());
        assertTrue(decision.getReason().contains("below minimum threshold"));
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
}

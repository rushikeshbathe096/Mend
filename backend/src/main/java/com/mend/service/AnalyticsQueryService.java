package com.mend.service;

import com.mend.domain.entity.*;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.RecoveryAttribution;
import com.mend.domain.repository.*;
import com.mend.dto.AnalyticsOverviewDto;
import com.mend.dto.AnalyticsRecoveryDto;
import com.mend.security.AuthenticatedUser;
import com.mend.security.TenantGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

import java.util.*;

/**
 * Merchant analytics. Amounts come only from the strict minor-unit parser ({@link WebhookAmountResolver#extractMoneyMinor})
 * and are summed exactly in minor units; a campaign without a resolvable amount is counted as "amount unknown" and left
 * out of every sum (invariants 1 and 3). "Recovered" counts only {@code recovery_attribution = MEND}; late captures of
 * the original payment and RECOVERED rows with no attribution are reported separately.
 */
@Service
public class AnalyticsQueryService {

    private final CampaignRepository campaignRepository;
    private final ActionIntentRepository actionIntentRepository;
    private final CampaignAttemptRepository campaignAttemptRepository;
    private final RecoveryDecisionRepository recoveryDecisionRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final ComplianceDecisionRepository complianceDecisionRepository;
    private final ClassificationResultRepository classificationResultRepository;
    private final WebhookAmountResolver amountResolver;

    public AnalyticsQueryService(
            CampaignRepository campaignRepository,
            ActionIntentRepository actionIntentRepository,
            CampaignAttemptRepository campaignAttemptRepository,
            RecoveryDecisionRepository recoveryDecisionRepository,
            @Autowired(required = false) WebhookEventRepository webhookEventRepository,
            @Autowired(required = false) ComplianceDecisionRepository complianceDecisionRepository,
            @Autowired(required = false) ClassificationResultRepository classificationResultRepository,
            WebhookAmountResolver amountResolver) {
        this.campaignRepository = campaignRepository;
        this.actionIntentRepository = actionIntentRepository;
        this.campaignAttemptRepository = campaignAttemptRepository;
        this.recoveryDecisionRepository = recoveryDecisionRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.complianceDecisionRepository = complianceDecisionRepository;
        this.classificationResultRepository = classificationResultRepository;
        this.amountResolver = amountResolver;
    }

    @Transactional(readOnly = true)
    public AnalyticsOverviewDto getAnalyticsOverview(UUID merchantId, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);

        List<Campaign> campaigns = campaignRepository.findByMerchantId(merchantId);
        List<WebhookEvent> webhooks = webhookEventRepository != null ? webhookEventRepository.findByMerchantId(merchantId) : List.of();
        Map<String, Long> amounts = amountsByPaymentId(webhooks);
        Tally all = new Tally();
        for (Campaign campaign : campaigns) {
            all.add(campaign, amounts.get(campaign.getPaymentId()));
        }

        long totalCampaigns = campaignRepository.countByMerchantId(merchantId);
        long recoveredCampaigns = all.recoveredByMend;
        long activeCampaigns = campaignRepository.countByMerchantIdAndCurrentStateIn(
                merchantId,
                List.of(CampaignStatus.CREATED, CampaignStatus.CLASSIFIED, CampaignStatus.ELIGIBLE, CampaignStatus.ACTION_PENDING, CampaignStatus.EXECUTING)
        );
        long failedCampaigns = campaignRepository.countByMerchantIdAndCurrentStateIn(
                merchantId,
                List.of(CampaignStatus.EXHAUSTED, CampaignStatus.FAILED, CampaignStatus.CANCELLED)
        );

        double recoveryRate = totalCampaigns > 0
                ? BigDecimal.valueOf((double) recoveredCampaigns / totalCampaigns * 100.0)
                .setScale(2, RoundingMode.HALF_UP).doubleValue()
                : 0.0;

        long totalAttempts = campaignAttemptRepository.countByMerchantId(merchantId);
        long totalActionIntents = actionIntentRepository.countByMerchantId(merchantId);
        long successfulIntents = actionIntentRepository.countByMerchantIdAndStatus(merchantId, ActionIntentStatus.SUCCEEDED);

        AnalyticsOverviewDto dto = new AnalyticsOverviewDto(
                totalCampaigns,
                recoveredCampaigns,
                activeCampaigns,
                failedCampaigns,
                recoveryRate,
                totalAttempts,
                totalActionIntents,
                successfulIntents
        );

        // Compute advanced overview metrics
        long totalPaymentFailures = webhookEventRepository != null ? webhookEventRepository.countByMerchantId(merchantId) : totalCampaigns;
        dto.setTotalPaymentFailures(totalPaymentFailures);

        long eligibleCount = campaigns.stream()
                .filter(c -> c.getCurrentState() != CampaignStatus.CREATED && c.getCurrentState() != CampaignStatus.CANCELLED)
                .count();

        dto.setRevenueAtRisk(WebhookAmountResolver.toDisplay(all.atRiskMinor));
        dto.setAmountRecovered(WebhookAmountResolver.toDisplay(all.recoveredByMendMinor));
        dto.setAmountRemainingAtRisk(WebhookAmountResolver.toDisplay(all.notRecoveredMinor));
        dto.setAmountUnknownCount(all.amountUnknown);
        dto.setRecoveredLateCaptureCount(all.lateCapture);
        dto.setAmountRecoveredLateCapture(WebhookAmountResolver.toDisplay(all.lateCaptureMinor));
        dto.setRecoveredAttributionUnknownCount(all.attributionUnknown);
        dto.setAmountRecoveredAttributionUnknown(WebhookAmountResolver.toDisplay(all.attributionUnknownMinor));
        dto.setRecoveredSyntheticCount(all.synthetic);
        dto.setAmountRecoveredSynthetic(WebhookAmountResolver.toDisplay(all.syntheticMinor));
        dto.setCampaignsEligible(eligibleCount);
        dto.setActionsAttempted(totalAttempts);
        dto.setSuccessfulRecoveries(recoveredCampaigns);

        long failedRecoveryAttempts = actionIntentRepository.countByMerchantIdAndStatus(merchantId, ActionIntentStatus.FAILED);
        dto.setFailedRecoveryAttempts(failedRecoveryAttempts);

        long complianceBlocks = 0;
        if (complianceDecisionRepository != null) {
            List<ComplianceDecisionEntity> compDecs = complianceDecisionRepository.findByMerchantId(merchantId);
            complianceBlocks = compDecs.stream().filter(d -> d.getStatus() == ComplianceStatus.COMPLIANCE_BLOCKED).count();
        }
        dto.setComplianceBlocks(complianceBlocks);

        // Processing Latency calculations
        long avgIngestionToCampaignMs = 0;
        long totalIngestionLatencySum = 0;
        long ingestionCount = 0;
        for (WebhookEvent ev : webhooks) {
            if (ev.getReceivedAt() != null && ev.getProcessedAt() != null) {
                long diff = Math.max(0, Duration.between(ev.getReceivedAt(), ev.getProcessedAt()).toMillis());
                totalIngestionLatencySum += diff;
                ingestionCount++;
            }
        }
        if (ingestionCount > 0) {
            avgIngestionToCampaignMs = totalIngestionLatencySum / ingestionCount;
        }
        dto.setAverageIngestionToCampaignLatencyMs(avgIngestionToCampaignMs);

        long avgExecutionLatencyMs = 0;
        List<ActionIntent> intents = actionIntentRepository.findByMerchantId(merchantId);
        long totalExecutionLatencySum = 0;
        long executionCount = 0;
        for (ActionIntent intent : intents) {
            if (intent.getScheduledAt() != null && intent.getCompletedAt() != null) {
                long diff = Math.max(0, Duration.between(intent.getScheduledAt(), intent.getCompletedAt()).toMillis());
                totalExecutionLatencySum += diff;
                executionCount++;
            }
        }
        if (executionCount > 0) {
            avgExecutionLatencyMs = totalExecutionLatencySum / executionCount;
        }
        dto.setAverageExecutionLatencyMs(avgExecutionLatencyMs);

        return dto;
    }

    @Transactional(readOnly = true)
    public AnalyticsRecoveryDto getAnalyticsRecovery(UUID merchantId, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);

        List<Campaign> campaigns = campaignRepository.findByMerchantId(merchantId);

        Map<String, Long> statusBreakdown = new HashMap<>();
        Map<String, Long> failureClassBreakdown = new HashMap<>();
        Map<String, Long> strategyBreakdown = new HashMap<>();
        Map<String, Long> strategyTotalMap = new HashMap<>();
        Map<String, Long> strategyRecoveredMap = new HashMap<>();

        Map<String, Long> failureClassTotalMap = new HashMap<>();
        Map<String, Long> failureClassRecoveredMap = new HashMap<>();

        for (Campaign campaign : campaigns) {
            String status = campaign.getCurrentState() != null ? campaign.getCurrentState().name() : "UNKNOWN";
            statusBreakdown.put(status, statusBreakdown.getOrDefault(status, 0L) + 1);

            if (campaign.getFailureClass() != null && !campaign.getFailureClass().isBlank()) {
                String fc = campaign.getFailureClass();
                failureClassBreakdown.put(fc, failureClassBreakdown.getOrDefault(fc, 0L) + 1);
                failureClassTotalMap.put(fc, failureClassTotalMap.getOrDefault(fc, 0L) + 1);
                if (campaign.recoveredByMend()) {
                    failureClassRecoveredMap.put(fc, failureClassRecoveredMap.getOrDefault(fc, 0L) + 1);
                }
            }

            if (campaign.getStrategy() != null && !campaign.getStrategy().isBlank()) {
                String strategy = campaign.getStrategy();
                strategyBreakdown.put(strategy, strategyBreakdown.getOrDefault(strategy, 0L) + 1);
                strategyTotalMap.put(strategy, strategyTotalMap.getOrDefault(strategy, 0L) + 1);
                if (campaign.recoveredByMend()) {
                    strategyRecoveredMap.put(strategy, strategyRecoveredMap.getOrDefault(strategy, 0L) + 1);
                }
            }
        }

        // Fill strategy breakdown from recovery decisions if campaign strategy wasn't explicitly saved
        List<RecoveryDecisionEntity> recDecs = recoveryDecisionRepository.findByMerchantId(merchantId);
        for (RecoveryDecisionEntity dec : recDecs) {
            if (dec.getStrategy() != null) {
                String stratName = dec.getStrategy().name();
                if (!strategyBreakdown.containsKey(stratName)) {
                    strategyBreakdown.put(stratName, 1L);
                }
            }
        }

        Map<String, Double> recoveryRateByStrategy = new HashMap<>();
        for (Map.Entry<String, Long> entry : strategyTotalMap.entrySet()) {
            String strat = entry.getKey();
            long total = entry.getValue();
            long recovered = strategyRecoveredMap.getOrDefault(strat, 0L);
            double rate = total > 0
                    ? BigDecimal.valueOf((double) recovered / total * 100.0).setScale(2, RoundingMode.HALF_UP).doubleValue()
                    : 0.0;
            recoveryRateByStrategy.put(strat, rate);
        }

        Map<String, Double> recoveryRateByFailureClass = new HashMap<>();
        for (Map.Entry<String, Long> entry : failureClassTotalMap.entrySet()) {
            String fc = entry.getKey();
            long total = entry.getValue();
            long recovered = failureClassRecoveredMap.getOrDefault(fc, 0L);
            double rate = total > 0
                    ? BigDecimal.valueOf((double) recovered / total * 100.0).setScale(2, RoundingMode.HALF_UP).doubleValue()
                    : 0.0;
            recoveryRateByFailureClass.put(fc, rate);
        }

        AnalyticsRecoveryDto recoveryDto = new AnalyticsRecoveryDto(
                strategyBreakdown,
                failureClassBreakdown,
                statusBreakdown,
                recoveryRateByStrategy
        );
        recoveryDto.setRecoveryRateByFailureClass(recoveryRateByFailureClass);

        // Action Type Breakdown & Provider Outcomes
        List<ActionIntent> intents = actionIntentRepository.findByMerchantId(merchantId);
        Map<String, Long> actionTypeBreakdown = new HashMap<>();
        Map<String, Long> providerOutcomes = new HashMap<>();

        for (ActionIntent intent : intents) {
            if (intent.getActionType() != null) {
                actionTypeBreakdown.put(intent.getActionType(), actionTypeBreakdown.getOrDefault(intent.getActionType(), 0L) + 1);
            }
            if (intent.getStatus() != null) {
                providerOutcomes.put(intent.getStatus().name(), providerOutcomes.getOrDefault(intent.getStatus().name(), 0L) + 1);
            }
        }
        recoveryDto.setActionTypeBreakdown(actionTypeBreakdown);
        recoveryDto.setProviderOutcomes(providerOutcomes);

        // AI Confidence Metrics
        Map<String, Object> aiMetrics = new HashMap<>();
        double totalConfidenceSum = 0.0;
        long confidenceCount = 0;
        long highCount = 0;
        long medCount = 0;
        long lowCount = 0;

        for (Campaign campaign : campaigns) {
            if (campaign.getConfidence() != null) {
                double conf = campaign.getConfidence().doubleValue();
                totalConfidenceSum += conf;
                confidenceCount++;
                if (conf >= 0.80) {
                    highCount++;
                } else if (conf >= 0.50) {
                    medCount++;
                } else {
                    lowCount++;
                }
            }
        }
        double avgConfidence = confidenceCount > 0 ? totalConfidenceSum / confidenceCount : 0.0;
        aiMetrics.put("averageConfidence", roundTwoDecimals(avgConfidence));
        aiMetrics.put("highConfidenceCount", highCount);
        aiMetrics.put("mediumConfidenceCount", medCount);
        aiMetrics.put("lowConfidenceCount", lowCount);
        recoveryDto.setAiConfidenceMetrics(aiMetrics);

        // Compliance Metrics
        Map<String, Long> complianceMetrics = new HashMap<>();
        if (complianceDecisionRepository != null) {
            List<ComplianceDecisionEntity> compDecs = complianceDecisionRepository.findByMerchantId(merchantId);
            long allowed = compDecs.stream().filter(d -> d.getStatus() == ComplianceStatus.COMPLIANCE_ALLOWED).count();
            long blocked = compDecs.stream().filter(d -> d.getStatus() == ComplianceStatus.COMPLIANCE_BLOCKED).count();
            complianceMetrics.put("totalEvaluated", (long) compDecs.size());
            complianceMetrics.put("allowedCount", allowed);
            complianceMetrics.put("blockedCount", blocked);
        }
        recoveryDto.setComplianceMetrics(complianceMetrics);

        // Retry Metrics
        Map<String, Object> retryMetrics = new HashMap<>();
        long totalAttempts = campaignAttemptRepository.countByMerchantId(merchantId);
        double avgAttempts = campaigns.size() > 0 ? (double) totalAttempts / campaigns.size() : 0.0;
        retryMetrics.put("totalAttempts", totalAttempts);
        retryMetrics.put("averageAttemptsPerCampaign", roundTwoDecimals(avgAttempts));
        recoveryDto.setRetryMetrics(retryMetrics);

        return recoveryDto;
    }

    @Transactional(readOnly = true)
    public com.mend.dto.AnalyticsFunnelDto getAnalyticsFunnel(UUID merchantId, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);

        long totalFailures = webhookEventRepository != null ? webhookEventRepository.countByMerchantId(merchantId) : 0;
        List<Campaign> campaigns = campaignRepository.findByMerchantId(merchantId);

        long totalCampaigns = campaigns.size();
        long classified = campaigns.stream().filter(c -> c.getCurrentState() != CampaignStatus.CREATED).count();
        long eligible = campaigns.stream().filter(c -> c.getCurrentState() != CampaignStatus.CREATED && c.getCurrentState() != CampaignStatus.CANCELLED).count();
        long strategySelected = campaigns.stream().filter(c -> c.getStrategy() != null && !c.getStrategy().isBlank()).count();

        List<ActionIntent> intents = actionIntentRepository.findByMerchantId(merchantId);
        long actionScheduled = intents.size();
        long actionExecuted = intents.stream().filter(i -> i.getStatus() == ActionIntentStatus.SUCCEEDED || i.getStatus() == ActionIntentStatus.FAILED).count();
        long recovered = campaigns.stream().filter(Campaign::recoveredByMend).count();

        List<com.mend.dto.AnalyticsFunnelDto.FunnelStageDto> stages = new ArrayList<>();
        long baseCount = Math.max(totalFailures, totalCampaigns);

        stages.add(createFunnelStage("FAILED_PAYMENTS", baseCount, baseCount, baseCount - totalCampaigns));
        stages.add(createFunnelStage("CAMPAIGN_CREATED", totalCampaigns, baseCount, totalCampaigns - classified));
        stages.add(createFunnelStage("CLASSIFIED", classified, baseCount, classified - eligible));
        stages.add(createFunnelStage("ELIGIBLE", eligible, baseCount, eligible - strategySelected));
        stages.add(createFunnelStage("STRATEGY_SELECTED", strategySelected, baseCount, strategySelected - actionScheduled));
        stages.add(createFunnelStage("ACTION_SCHEDULED", actionScheduled, baseCount, actionScheduled - actionExecuted));
        stages.add(createFunnelStage("ACTION_EXECUTED", actionExecuted, baseCount, actionExecuted - recovered));
        stages.add(createFunnelStage("RECOVERED", recovered, baseCount, 0));

        return new com.mend.dto.AnalyticsFunnelDto(baseCount, stages);
    }

    @Transactional(readOnly = true)
    public com.mend.dto.AnalyticsFailureBreakdownDto getAnalyticsFailureBreakdown(UUID merchantId, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);

        List<Campaign> campaigns = campaignRepository.findByMerchantId(merchantId);
        Map<String, Long> amounts = amountsByPaymentId(merchantId);

        Map<String, Tally> byClass = new HashMap<>();
        for (Campaign campaign : campaigns) {
            String fc = campaign.getFailureClass() != null && !campaign.getFailureClass().isBlank()
                    ? campaign.getFailureClass()
                    : "UNKNOWN";
            byClass.computeIfAbsent(fc, k -> new Tally()).add(campaign, amounts.get(campaign.getPaymentId()));
        }

        List<com.mend.dto.AnalyticsFailureBreakdownDto.FailureClassMetricDto> list = new ArrayList<>();
        for (Map.Entry<String, Tally> entry : byClass.entrySet()) {
            Tally t = entry.getValue();
            list.add(new com.mend.dto.AnalyticsFailureBreakdownDto.FailureClassMetricDto(
                    entry.getKey(), t.count, t.recoveredByMend, percent(t.recoveredByMend, t.count),
                    WebhookAmountResolver.toDisplay(t.atRiskMinor), WebhookAmountResolver.toDisplay(t.recoveredByMendMinor), t.amountUnknown
            ));
        }

        return new com.mend.dto.AnalyticsFailureBreakdownDto(list);
    }

    @Transactional(readOnly = true)
    public com.mend.dto.AnalyticsStrategyPerformanceDto getAnalyticsStrategyPerformance(UUID merchantId, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);

        List<Campaign> campaigns = campaignRepository.findByMerchantId(merchantId);
        Map<String, Long> amounts = amountsByPaymentId(merchantId);

        Map<String, Tally> byStrategy = new HashMap<>();
        for (Campaign campaign : campaigns) {
            String strat = campaign.getStrategy() != null && !campaign.getStrategy().isBlank()
                    ? campaign.getStrategy()
                    : "UNASSIGNED";
            byStrategy.computeIfAbsent(strat, k -> new Tally()).add(campaign, amounts.get(campaign.getPaymentId()));
        }

        List<com.mend.dto.AnalyticsStrategyPerformanceDto.StrategyMetricDto> list = new ArrayList<>();
        for (Map.Entry<String, Tally> entry : byStrategy.entrySet()) {
            Tally t = entry.getValue();
            list.add(new com.mend.dto.AnalyticsStrategyPerformanceDto.StrategyMetricDto(
                    entry.getKey(), t.count, t.recoveredByMend, percent(t.recoveredByMend, t.count),
                    WebhookAmountResolver.toDisplay(t.recoveredByMendMinor), t.amountUnknown
            ));
        }

        return new com.mend.dto.AnalyticsStrategyPerformanceDto(list);
    }

    private com.mend.dto.AnalyticsFunnelDto.FunnelStageDto createFunnelStage(String name, long count, long totalBase, long dropOff) {
        double pct = totalBase > 0 ? roundTwoDecimals((double) count / totalBase * 100.0) : 0.0;
        return new com.mend.dto.AnalyticsFunnelDto.FunnelStageDto(name, count, pct, Math.max(0, dropOff));
    }


    /** Original amount in minor units per payment id, strict parser only; a payment id without one is absent (unknown). */
    private Map<String, Long> amountsByPaymentId(UUID merchantId) {
        return amountsByPaymentId(webhookEventRepository != null ? webhookEventRepository.findByMerchantId(merchantId) : List.of());
    }

    private Map<String, Long> amountsByPaymentId(List<WebhookEvent> webhooks) {
        Map<String, Long> amounts = new HashMap<>();
        for (WebhookEvent ev : webhooks) {
            if (ev.getExternalEventId() != null) {
                amountResolver.extractMoneyMinor(ev).ifPresent(amount -> amounts.put(ev.getExternalEventId(), amount.amountMinor()));
            }
        }
        return amounts;
    }

    /** Percentage (not money), two decimals. */
    private double percent(long part, long total) {
        return total > 0 ? roundTwoDecimals((double) part / total * 100.0) : 0.0;
    }

    /**
     * Exact minor-unit sums for a group of campaigns. A campaign with no resolvable amount adds 0 to every sum and 1 to
     * {@code amountUnknown}; RECOVERED campaigns are split by attribution (NULL = recovered before V15, attribution unknown).
     */
    private static final class Tally {
        long count;
        long amountUnknown;
        long atRiskMinor;
        long notRecoveredMinor;
        long recoveredByMend;
        long recoveredByMendMinor;
        long lateCapture;
        long lateCaptureMinor;
        long attributionUnknown;
        long attributionUnknownMinor;
        long synthetic;
        long syntheticMinor;

        void add(Campaign campaign, Long amountMinorOrNull) {
            count++;
            long amount = amountMinorOrNull == null ? 0 : amountMinorOrNull;
            if (amountMinorOrNull == null) {
                amountUnknown++;
            }
            atRiskMinor = Math.addExact(atRiskMinor, amount);
            if (campaign.getCurrentState() != CampaignStatus.RECOVERED) {
                notRecoveredMinor = Math.addExact(notRecoveredMinor, amount);
                return;
            }
            switch (campaign.getRecoveryAttribution()) {
                case MEND -> {
                    recoveredByMend++;
                    recoveredByMendMinor = Math.addExact(recoveredByMendMinor, amount);
                }
                case ORIGINAL_PAYMENT_LATE_CAPTURE -> {
                    lateCapture++;
                    lateCaptureMinor = Math.addExact(lateCaptureMinor, amount);
                }
                case SYNTHETIC -> {
                    synthetic++;
                    syntheticMinor = Math.addExact(syntheticMinor, amount);
                }
                case null -> {
                    attributionUnknown++;
                    attributionUnknownMinor = Math.addExact(attributionUnknownMinor, amount);
                }
            }
        }
    }

    private double roundTwoDecimals(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}

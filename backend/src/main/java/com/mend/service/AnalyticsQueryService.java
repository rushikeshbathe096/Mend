package com.mend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mend.domain.entity.*;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.repository.*;
import com.mend.dto.AnalyticsOverviewDto;
import com.mend.dto.AnalyticsRecoveryDto;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

import java.util.*;

@Service
public class AnalyticsQueryService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsQueryService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final CampaignRepository campaignRepository;
    private final ActionIntentRepository actionIntentRepository;
    private final CampaignAttemptRepository campaignAttemptRepository;
    private final RecoveryDecisionRepository recoveryDecisionRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final ComplianceDecisionRepository complianceDecisionRepository;
    private final ClassificationResultRepository classificationResultRepository;

    public AnalyticsQueryService(
            CampaignRepository campaignRepository,
            ActionIntentRepository actionIntentRepository,
            CampaignAttemptRepository campaignAttemptRepository,
            RecoveryDecisionRepository recoveryDecisionRepository,
            @Autowired(required = false) WebhookEventRepository webhookEventRepository,
            @Autowired(required = false) ComplianceDecisionRepository complianceDecisionRepository,
            @Autowired(required = false) ClassificationResultRepository classificationResultRepository) {
        this.campaignRepository = campaignRepository;
        this.actionIntentRepository = actionIntentRepository;
        this.campaignAttemptRepository = campaignAttemptRepository;
        this.recoveryDecisionRepository = recoveryDecisionRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.complianceDecisionRepository = complianceDecisionRepository;
        this.classificationResultRepository = classificationResultRepository;
    }

    @Transactional(readOnly = true)
    public AnalyticsOverviewDto getAnalyticsOverview(UUID merchantId, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);

        long totalCampaigns = campaignRepository.countByMerchantId(merchantId);
        long recoveredCampaigns = campaignRepository.countByMerchantIdAndCurrentState(merchantId, CampaignStatus.RECOVERED);
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

        List<Campaign> campaigns = campaignRepository.findByMerchantId(merchantId);
        List<WebhookEvent> webhooks = webhookEventRepository != null ? webhookEventRepository.findByMerchantId(merchantId) : List.of();

        // Calculate revenue at risk, recovered amount, and remaining at risk
        double totalRevenueAtRisk = 0.0;
        double totalAmountRecovered = 0.0;

        Map<String, Double> webhookAmountMap = new HashMap<>();
        for (WebhookEvent ev : webhooks) {
            double amt = extractAmountFromWebhook(ev);
            if (ev.getExternalEventId() != null) {
                webhookAmountMap.put(ev.getExternalEventId(), amt);
            }
        }

        long eligibleCount = 0;
        for (Campaign campaign : campaigns) {
            double campaignAmt = webhookAmountMap.getOrDefault(campaign.getPaymentId(), 1000.00);
            totalRevenueAtRisk += campaignAmt;
            if (campaign.getCurrentState() == CampaignStatus.RECOVERED) {
                totalAmountRecovered += campaignAmt;
            }
            if (campaign.getCurrentState() != CampaignStatus.CREATED && campaign.getCurrentState() != CampaignStatus.CANCELLED) {
                eligibleCount++;
            }
        }

        dto.setRevenueAtRisk(roundTwoDecimals(totalRevenueAtRisk));
        dto.setAmountRecovered(roundTwoDecimals(totalAmountRecovered));
        dto.setAmountRemainingAtRisk(roundTwoDecimals(Math.max(0.0, totalRevenueAtRisk - totalAmountRecovered)));
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
        validateTenantAccess(merchantId, currentUser);

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
                if (campaign.getCurrentState() == CampaignStatus.RECOVERED) {
                    failureClassRecoveredMap.put(fc, failureClassRecoveredMap.getOrDefault(fc, 0L) + 1);
                }
            }

            if (campaign.getStrategy() != null && !campaign.getStrategy().isBlank()) {
                String strategy = campaign.getStrategy();
                strategyBreakdown.put(strategy, strategyBreakdown.getOrDefault(strategy, 0L) + 1);
                strategyTotalMap.put(strategy, strategyTotalMap.getOrDefault(strategy, 0L) + 1);
                if (campaign.getCurrentState() == CampaignStatus.RECOVERED) {
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
        validateTenantAccess(merchantId, currentUser);

        long totalFailures = webhookEventRepository != null ? webhookEventRepository.countByMerchantId(merchantId) : 0;
        List<Campaign> campaigns = campaignRepository.findByMerchantId(merchantId);

        long totalCampaigns = campaigns.size();
        long classified = campaigns.stream().filter(c -> c.getCurrentState() != CampaignStatus.CREATED).count();
        long eligible = campaigns.stream().filter(c -> c.getCurrentState() != CampaignStatus.CREATED && c.getCurrentState() != CampaignStatus.CANCELLED).count();
        long strategySelected = campaigns.stream().filter(c -> c.getStrategy() != null && !c.getStrategy().isBlank()).count();

        List<ActionIntent> intents = actionIntentRepository.findByMerchantId(merchantId);
        long actionScheduled = intents.size();
        long actionExecuted = intents.stream().filter(i -> i.getStatus() == ActionIntentStatus.SUCCEEDED || i.getStatus() == ActionIntentStatus.FAILED).count();
        long recovered = campaigns.stream().filter(c -> c.getCurrentState() == CampaignStatus.RECOVERED).count();

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
        validateTenantAccess(merchantId, currentUser);

        List<Campaign> campaigns = campaignRepository.findByMerchantId(merchantId);
        List<WebhookEvent> webhooks = webhookEventRepository != null ? webhookEventRepository.findByMerchantId(merchantId) : List.of();

        Map<String, Double> webhookAmountMap = new HashMap<>();
        for (WebhookEvent ev : webhooks) {
            if (ev.getExternalEventId() != null) {
                webhookAmountMap.put(ev.getExternalEventId(), extractAmountFromWebhook(ev));
            }
        }

        Map<String, Long> totalMap = new HashMap<>();
        Map<String, Long> recoveredMap = new HashMap<>();
        Map<String, Double> revenueAtRiskMap = new HashMap<>();
        Map<String, Double> revenueRecoveredMap = new HashMap<>();

        for (Campaign campaign : campaigns) {
            String fc = campaign.getFailureClass() != null && !campaign.getFailureClass().isBlank()
                    ? campaign.getFailureClass()
                    : "UNKNOWN";

            double amt = webhookAmountMap.getOrDefault(campaign.getPaymentId(), 1000.00);
            totalMap.put(fc, totalMap.getOrDefault(fc, 0L) + 1);
            revenueAtRiskMap.put(fc, revenueAtRiskMap.getOrDefault(fc, 0.0) + amt);

            if (campaign.getCurrentState() == CampaignStatus.RECOVERED) {
                recoveredMap.put(fc, recoveredMap.getOrDefault(fc, 0L) + 1);
                revenueRecoveredMap.put(fc, revenueRecoveredMap.getOrDefault(fc, 0.0) + amt);
            }
        }

        List<com.mend.dto.AnalyticsFailureBreakdownDto.FailureClassMetricDto> list = new ArrayList<>();
        for (Map.Entry<String, Long> entry : totalMap.entrySet()) {
            String fc = entry.getKey();
            long count = entry.getValue();
            long recCount = recoveredMap.getOrDefault(fc, 0L);
            double rate = count > 0 ? roundTwoDecimals((double) recCount / count * 100.0) : 0.0;
            double atRisk = roundTwoDecimals(revenueAtRiskMap.getOrDefault(fc, 0.0));
            double recRev = roundTwoDecimals(revenueRecoveredMap.getOrDefault(fc, 0.0));

            list.add(new com.mend.dto.AnalyticsFailureBreakdownDto.FailureClassMetricDto(
                    fc, count, recCount, rate, atRisk, recRev
            ));
        }

        return new com.mend.dto.AnalyticsFailureBreakdownDto(list);
    }

    @Transactional(readOnly = true)
    public com.mend.dto.AnalyticsStrategyPerformanceDto getAnalyticsStrategyPerformance(UUID merchantId, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);

        List<Campaign> campaigns = campaignRepository.findByMerchantId(merchantId);
        List<WebhookEvent> webhooks = webhookEventRepository != null ? webhookEventRepository.findByMerchantId(merchantId) : List.of();

        Map<String, Double> webhookAmountMap = new HashMap<>();
        for (WebhookEvent ev : webhooks) {
            if (ev.getExternalEventId() != null) {
                webhookAmountMap.put(ev.getExternalEventId(), extractAmountFromWebhook(ev));
            }
        }

        Map<String, Long> totalMap = new HashMap<>();
        Map<String, Long> recoveredMap = new HashMap<>();
        Map<String, Double> revenueRecoveredMap = new HashMap<>();

        for (Campaign campaign : campaigns) {
            String strat = campaign.getStrategy() != null && !campaign.getStrategy().isBlank()
                    ? campaign.getStrategy()
                    : "UNASSIGNED";

            double amt = webhookAmountMap.getOrDefault(campaign.getPaymentId(), 1000.00);
            totalMap.put(strat, totalMap.getOrDefault(strat, 0L) + 1);

            if (campaign.getCurrentState() == CampaignStatus.RECOVERED) {
                recoveredMap.put(strat, recoveredMap.getOrDefault(strat, 0L) + 1);
                revenueRecoveredMap.put(strat, revenueRecoveredMap.getOrDefault(strat, 0.0) + amt);
            }
        }

        List<com.mend.dto.AnalyticsStrategyPerformanceDto.StrategyMetricDto> list = new ArrayList<>();
        for (Map.Entry<String, Long> entry : totalMap.entrySet()) {
            String strat = entry.getKey();
            long total = entry.getValue();
            long recCount = recoveredMap.getOrDefault(strat, 0L);
            double rate = total > 0 ? roundTwoDecimals((double) recCount / total * 100.0) : 0.0;
            double recRev = roundTwoDecimals(revenueRecoveredMap.getOrDefault(strat, 0.0));

            list.add(new com.mend.dto.AnalyticsStrategyPerformanceDto.StrategyMetricDto(
                    strat, total, recCount, rate, recRev
            ));
        }

        return new com.mend.dto.AnalyticsStrategyPerformanceDto(list);
    }

    private com.mend.dto.AnalyticsFunnelDto.FunnelStageDto createFunnelStage(String name, long count, long totalBase, long dropOff) {
        double pct = totalBase > 0 ? roundTwoDecimals((double) count / totalBase * 100.0) : 0.0;
        return new com.mend.dto.AnalyticsFunnelDto.FunnelStageDto(name, count, pct, Math.max(0, dropOff));
    }

    private void validateTenantAccess(UUID merchantId, AuthenticatedUser currentUser) {
        if (merchantId == null) {
            throw new TenantAccessDeniedException("Merchant context is required");
        }
        if (currentUser != null && !currentUser.isSystemAdmin() && !currentUser.isMemberOfMerchant(merchantId)) {
            throw new TenantAccessDeniedException("Access denied for merchant: " + merchantId);
        }
    }

    private double extractAmountFromWebhook(WebhookEvent event) {
        if (event == null || event.getRawPayload() == null || event.getRawPayload().isBlank()) {
            return 1000.00;
        }
        try {
            JsonNode root = objectMapper.readTree(event.getRawPayload());
            JsonNode paymentNode = root.path("payload").path("payment").path("entity");
            if (paymentNode.isMissingNode() || paymentNode.isNull()) {
                paymentNode = root.path("payment").path("entity");
            }
            if (paymentNode.isMissingNode() || paymentNode.isNull()) {
                paymentNode = root;
            }

            if (paymentNode.has("amount") && !paymentNode.get("amount").isNull()) {
                long amountInPaise = paymentNode.get("amount").asLong();
                return BigDecimal.valueOf(amountInPaise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).doubleValue();
            }
        } catch (Exception e) {
            log.trace("Error parsing webhook payload amount: {}", e.getMessage());
        }
        return 1000.00;
    }

    private double roundTwoDecimals(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}

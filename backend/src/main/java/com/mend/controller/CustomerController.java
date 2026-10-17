package com.mend.controller;

import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryAttribution;
import com.mend.domain.repository.*;
import com.mend.dto.*;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CampaignRepository campaignRepository;
    private final AgentDecisionRecordRepository agentDecisionRecordRepository;
    private final AuditLogRepository auditLogRepository;
    private final com.mend.service.WebhookAmountResolver webhookAmountResolver;

    public CustomerController(
            CampaignRepository campaignRepository,
            AgentDecisionRecordRepository agentDecisionRecordRepository,
            AuditLogRepository auditLogRepository,
            com.mend.service.WebhookAmountResolver webhookAmountResolver) {
        this.campaignRepository = campaignRepository;
        this.agentDecisionRecordRepository = agentDecisionRecordRepository;
        this.auditLogRepository = auditLogRepository;
        this.webhookAmountResolver = webhookAmountResolver;
    }

    @GetMapping
    public ResponseEntity<List<CustomerSummaryDto>> getCustomers(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        List<String> customerHashes = campaignRepository.findDistinctCustomerIdHashesByMerchantId(effectiveMerchantId);

        List<CustomerSummaryDto> summaries = new ArrayList<>();
        for (String hash : customerHashes) {
            summaries.add(buildCustomerSummary(effectiveMerchantId, hash));
        }

        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/{customerIdHash}")
    public ResponseEntity<CustomerProfileDto> getCustomerProfile(
            @PathVariable String customerIdHash,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        List<Campaign> campaigns = campaignRepository.findByMerchantIdAndCustomerIdHash(effectiveMerchantId, customerIdHash);

        if (campaigns.isEmpty()) {
            throw new ResourceNotFoundException("Customer profile not found for customerIdHash: " + customerIdHash);
        }

        CustomerSummaryDto summary = buildCustomerSummary(effectiveMerchantId, customerIdHash);

        List<CampaignDto> campaignDtos = campaigns.stream()
                .map(CampaignDto::fromEntity)
                .collect(Collectors.toList());

        List<PaymentSummaryDto> paymentHistory = campaigns.stream()
                .map(c -> new PaymentSummaryDto(
                        c.getPaymentId(), c.getCustomerIdHash(), c.getMerchantId(),
                        c.getFailureClass() != null ? c.getFailureClass() : "UNKNOWN",
                        resolveAmount(c), c.getCurrentState(), c.getStrategy(),
                        c.getAttemptCount(), c.getId(), c.getCreatedAt(), c.getUpdatedAt()
                ))
                .collect(Collectors.toList());

        List<String> disputeHistory = new ArrayList<>();
        if (summary.getRiskSignals().contains("PRIOR_DISPUTE")) {
            disputeHistory.add("Customer raised dispute on payment failure in prior cycle");
        }

        List<AuditLogDto> auditHistory = new ArrayList<>();
        for (Campaign c : campaigns) {
            auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(c.getId()).forEach(al -> 
                auditHistory.add(AuditLogDto.fromEntity(al))
            );
        }

        CustomerProfileDto profile = new CustomerProfileDto(
                summary, campaignDtos, paymentHistory, disputeHistory, auditHistory
        );

        return ResponseEntity.ok(profile);
    }

    private CustomerSummaryDto buildCustomerSummary(UUID merchantId, String customerIdHash) {
        List<Campaign> campaigns = campaignRepository.findByMerchantIdAndCustomerIdHash(merchantId, customerIdHash);
        long total = campaigns.size();
        long active = campaigns.stream().filter(c -> c.getCurrentState() != CampaignStatus.RECOVERED && c.getCurrentState() != CampaignStatus.EXHAUSTED && c.getCurrentState() != CampaignStatus.CANCELLED).count();
        // Same attribution rule as analytics: only MEND recoveries count as recovered; late capture and unknown
        // attribution are reported separately.
        long recovered = campaigns.stream().filter(Campaign::recoveredByMend).count();

        // Same amount rule as analytics: each amount is resolved once (strict); a campaign without one is excluded from
        // every sum and counted in amountUnknownCount, never given a default.
        Map<UUID, BigDecimal> amounts = new HashMap<>();
        campaigns.forEach(c -> amounts.put(c.getId(), resolveAmount(c)));
        long amountUnknown = amounts.values().stream().filter(Objects::isNull).count();

        BigDecimal totalFailed = sumAmounts(campaigns.stream(), amounts);
        BigDecimal totalRecovered = sumAmounts(campaigns.stream().filter(Campaign::recoveredByMend), amounts);

        List<Campaign> lateCapture = campaigns.stream()
                .filter(c -> c.getCurrentState() == CampaignStatus.RECOVERED
                        && c.getRecoveryAttribution() == RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE)
                .toList();
        List<Campaign> attributionUnknown = campaigns.stream()
                .filter(c -> c.getCurrentState() == CampaignStatus.RECOVERED && c.getRecoveryAttribution() == null)
                .toList();

        List<String> riskSignals = new ArrayList<>();
        if (total > 3) {
            riskSignals.add("REPEAT_FAILURES");
        }
        if (active > 0) {
            riskSignals.add("ACTIVE_RECOVERY_PENDING");
        }
        if (riskSignals.isEmpty()) {
            riskSignals.add("LOW_RISK_PROFILE");
        }

        Instant lastActivity = campaigns.stream()
                .map(Campaign::getUpdatedAt)
                .max(Instant::compareTo)
                .orElse(Instant.now());

        CustomerSummaryDto summary = new CustomerSummaryDto(
                customerIdHash, merchantId, total, active, recovered,
                totalFailed, totalRecovered, riskSignals, lastActivity
        );
        summary.setRecoveredLateCaptureCount(lateCapture.size());
        summary.setAmountRecoveredLateCapture(sumAmounts(lateCapture.stream(), amounts));
        summary.setRecoveredAttributionUnknownCount(attributionUnknown.size());
        summary.setAmountRecoveredAttributionUnknown(sumAmounts(attributionUnknown.stream(), amounts));
        summary.setAmountUnknownCount(amountUnknown);
        return summary;
    }

    /** Exact sum of the resolved amounts; a campaign without one adds nothing (never a default). */
    private static BigDecimal sumAmounts(Stream<Campaign> campaigns, Map<UUID, BigDecimal> amounts) {
        return campaigns
                .map(c -> amounts.get(c.getId()))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal resolveAmount(Campaign campaign) {
        return webhookAmountResolver.resolveAmount(campaign.getMerchantId(), campaign.getPaymentId()).orElse(null);
    }

    private UUID resolveMerchantId(String merchantHeader, AuthenticatedUser currentUser) {
        if (merchantHeader != null && !merchantHeader.isBlank()) {
            try {
                return UUID.fromString(merchantHeader.trim());
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("Invalid X-Merchant-Id header format");
            }
        }
        if (TenantContext.getCurrentMerchantId() != null) {
            return TenantContext.getCurrentMerchantId();
        }
        if (currentUser != null && currentUser.getMemberships() != null && !currentUser.getMemberships().isEmpty()) {
            return currentUser.getMemberships().get(0).getMerchantId();
        }
        throw new TenantAccessDeniedException("X-Merchant-Id header is required");
    }
}

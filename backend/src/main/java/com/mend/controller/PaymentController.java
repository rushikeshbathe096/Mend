package com.mend.controller;

import com.mend.domain.entity.*;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.*;
import com.mend.dto.*;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final CampaignRepository campaignRepository;
    private final CampaignAttemptRepository campaignAttemptRepository;
    private final ActionIntentRepository actionIntentRepository;
    private final AgentDecisionRecordRepository agentDecisionRecordRepository;
    private final ComplianceDecisionRepository complianceDecisionRepository;
    private final AuditLogRepository auditLogRepository;
    private final com.mend.service.WebhookAmountResolver webhookAmountResolver;

    public PaymentController(
            CampaignRepository campaignRepository,
            CampaignAttemptRepository campaignAttemptRepository,
            ActionIntentRepository actionIntentRepository,
            AgentDecisionRecordRepository agentDecisionRecordRepository,
            ComplianceDecisionRepository complianceDecisionRepository,
            AuditLogRepository auditLogRepository,
            com.mend.service.WebhookAmountResolver webhookAmountResolver) {
        this.campaignRepository = campaignRepository;
        this.campaignAttemptRepository = campaignAttemptRepository;
        this.actionIntentRepository = actionIntentRepository;
        this.agentDecisionRecordRepository = agentDecisionRecordRepository;
        this.complianceDecisionRepository = complianceDecisionRepository;
        this.auditLogRepository = auditLogRepository;
        this.webhookAmountResolver = webhookAmountResolver;
    }

    @GetMapping
    public ResponseEntity<PageResponse<PaymentSummaryDto>> getPayments(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(required = false) String failureClass,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Filters are applied server-side so pagination totals always reflect the filtered result set
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        String normalizedFailureClass = (failureClass == null || failureClass.isBlank()) ? null : failureClass.trim();

        Page<Campaign> campaignsPage = campaignRepository.searchMerchantCampaigns(
                effectiveMerchantId, status, normalizedFailureClass, normalizedSearch, pageable);

        List<PaymentSummaryDto> summaries = campaignsPage.getContent().stream()
                .map(this::toPaymentSummary)
                .collect(Collectors.toList());

        PageResponse<PaymentSummaryDto> response = new PageResponse<>(
                summaries,
                campaignsPage.getNumber(),
                campaignsPage.getSize(),
                campaignsPage.getTotalElements(),
                campaignsPage.getTotalPages(),
                campaignsPage.isLast()
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentDetailDto> getPaymentDetail(
            @PathVariable String paymentId,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(effectiveMerchantId, paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment/Campaign not found for paymentId: " + paymentId));

        PaymentSummaryDto summary = toPaymentSummary(campaign);
        CampaignDto campaignDto = CampaignDto.fromEntity(campaign);

        List<CampaignAttemptDto> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId())
                .stream()
                .map(CampaignAttemptDto::fromEntity)
                .collect(Collectors.toList());

        List<ActionIntentDto> actionIntents = actionIntentRepository.findByCampaignId(campaign.getId())
                .stream()
                .map(ActionIntentDto::fromEntity)
                .collect(Collectors.toList());

        List<AgentDecisionRecordDto> agentDecisions = agentDecisionRecordRepository.findByCampaignIdOrderByCreatedAtDesc(campaign.getId())
                .stream()
                .map(AgentDecisionRecordDto::fromEntity)
                .collect(Collectors.toList());

        List<ComplianceDecisionDto> complianceDecisions = complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId())
                .stream()
                .map(ComplianceDecisionDto::fromEntity)
                .collect(Collectors.toList());

        List<AuditLogDto> auditLogs = auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(campaign.getId())
                .stream()
                .map(AuditLogDto::fromEntity)
                .collect(Collectors.toList());

        PaymentDetailDto detail = new PaymentDetailDto(
                summary, campaignDto, attempts, actionIntents, agentDecisions, complianceDecisions, auditLogs
        );

        return ResponseEntity.ok(detail);
    }

    private PaymentSummaryDto toPaymentSummary(Campaign c) {
        BigDecimal amount = webhookAmountResolver.resolveAmount(c.getMerchantId(), c.getPaymentId()).orElse(null);
        return new PaymentSummaryDto(
                c.getPaymentId(), c.getCustomerIdHash(), c.getMerchantId(),
                c.getFailureClass() != null ? c.getFailureClass() : "UNKNOWN",
                amount, c.getCurrentState(), c.getStrategy(), c.getAttemptCount(),
                c.getId(), c.getCreatedAt(), c.getUpdatedAt()
        );
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

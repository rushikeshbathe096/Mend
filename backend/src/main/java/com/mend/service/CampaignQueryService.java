package com.mend.service;

import com.mend.domain.entity.*;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.*;
import com.mend.dto.*;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CampaignQueryService {

    private final CampaignRepository campaignRepository;
    private final ClassificationResultRepository classificationResultRepository;
    private final RecoveryDecisionRepository recoveryDecisionRepository;
    private final ComplianceDecisionRepository complianceDecisionRepository;
    private final ActionIntentRepository actionIntentRepository;
    private final CampaignAttemptRepository campaignAttemptRepository;
    private final AuditLogRepository auditLogRepository;

    public CampaignQueryService(
            CampaignRepository campaignRepository,
            ClassificationResultRepository classificationResultRepository,
            RecoveryDecisionRepository recoveryDecisionRepository,
            ComplianceDecisionRepository complianceDecisionRepository,
            ActionIntentRepository actionIntentRepository,
            CampaignAttemptRepository campaignAttemptRepository,
            AuditLogRepository auditLogRepository) {
        this.campaignRepository = campaignRepository;
        this.classificationResultRepository = classificationResultRepository;
        this.recoveryDecisionRepository = recoveryDecisionRepository;
        this.complianceDecisionRepository = complianceDecisionRepository;
        this.actionIntentRepository = actionIntentRepository;
        this.campaignAttemptRepository = campaignAttemptRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<CampaignDto> getCampaigns(
            UUID merchantId,
            CampaignStatus status,
            int page,
            int size,
            String sortBy,
            String sortOrder,
            AuthenticatedUser currentUser) {

        validateTenantAccess(merchantId, currentUser);

        String effectiveSortBy = (sortBy != null && !sortBy.isBlank()) ? sortBy : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size > 0 ? size : 20, Sort.by(direction, effectiveSortBy));

        Page<Campaign> campaignPage;
        if (status != null) {
            campaignPage = campaignRepository.findByMerchantIdAndCurrentState(merchantId, status, pageable);
        } else {
            campaignPage = campaignRepository.findByMerchantId(merchantId, pageable);
        }

        List<CampaignDto> dtoList = campaignPage.getContent().stream()
                .map(CampaignDto::fromEntity)
                .collect(Collectors.toList());

        return PageResponse.of(dtoList, campaignPage.getNumber(), campaignPage.getSize(), campaignPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public CampaignDto getCampaign(UUID merchantId, UUID campaignId, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);

        Campaign campaign = campaignRepository.findByMerchantIdAndId(merchantId, campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found for merchant " + merchantId + " with ID: " + campaignId));

        return CampaignDto.fromEntity(campaign);
    }

    @Transactional(readOnly = true)
    public CampaignTimelineDto getCampaignTimeline(UUID merchantId, UUID campaignId, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);

        Campaign campaign = campaignRepository.findByMerchantIdAndId(merchantId, campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found for merchant " + merchantId + " with ID: " + campaignId));

        CampaignTimelineDto timeline = new CampaignTimelineDto();
        timeline.setCampaignId(campaign.getId());
        timeline.setCurrentState(campaign.getCurrentState());
        timeline.setCampaign(CampaignDto.fromEntity(campaign));

        // Classification
        classificationResultRepository.findLatestByCampaignId(campaignId)
                .ifPresent(cr -> timeline.setClassification(ClassificationResultDto.fromEntity(cr)));

        // Recovery decisions
        List<RecoveryDecisionEntity> recDecs = recoveryDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaignId);
        timeline.setRecoveryDecisions(recDecs.stream().map(RecoveryDecisionDto::fromEntity).collect(Collectors.toList()));

        // Compliance decisions
        List<ComplianceDecisionEntity> compDecs = complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaignId);
        timeline.setComplianceDecisions(compDecs.stream().map(ComplianceDecisionDto::fromEntity).collect(Collectors.toList()));

        // Action intents
        List<ActionIntent> intents = actionIntentRepository.findByCampaignId(campaignId);
        timeline.setActionIntents(intents.stream().map(ActionIntentDto::fromEntity).collect(Collectors.toList()));

        // Attempts
        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaignId);
        timeline.setAttempts(attempts.stream().map(CampaignAttemptDto::fromEntity).collect(Collectors.toList()));

        // Audit logs
        List<AuditLog> auditLogs = auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId);
        timeline.setAuditLogs(auditLogs.stream().map(AuditLogDto::fromEntity).collect(Collectors.toList()));

        return timeline;
    }

    private void validateTenantAccess(UUID merchantId, AuthenticatedUser currentUser) {
        if (merchantId == null) {
            throw new TenantAccessDeniedException("Merchant context is required");
        }
        if (currentUser != null && !currentUser.isSystemAdmin() && !currentUser.isMemberOfMerchant(merchantId)) {
            throw new TenantAccessDeniedException("Access denied for merchant: " + merchantId);
        }
    }
}

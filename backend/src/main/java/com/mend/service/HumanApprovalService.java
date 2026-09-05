package com.mend.service;

import com.mend.compliance.ComplianceDecision;
import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.AgentDecisionRecord;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ReviewQueue;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.ReviewQueueStatus;
import com.mend.domain.repository.AgentDecisionRecordRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.ReviewQueueRepository;
import com.mend.dto.ActionIntentDto;
import com.mend.dto.AgentDecisionRecordDto;
import com.mend.dto.CampaignDto;
import com.mend.dto.ReviewItemDto;
import com.mend.dto.ReviewQueueSummaryDto;
import com.mend.dto.ReviewDecisionResponse;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.statemachine.CampaignStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merchant human-approval workflow.
 *
 * Reviews are created whenever the recovery pipeline needs a human decision
 * before an otherwise-compliant action may proceed (supervisor consensus
 * REVIEW_REQUIRED, high-risk strategies, or deterministic fallbacks).
 *
 * Approvals NEVER grant the frontend direct provider access. The frontend only
 * submits a decision; the backend revalidates tenant ownership, RBAC role,
 * campaign state, ActionIntent state, duplicate actions, and re-runs the
 * compliance engine before an ActionIntent is created through the standard
 * ActionIntentService boundary. Rejection cancels the campaign through the
 * authoritative state machine.
 */
@Service
public class HumanApprovalService {

    private static final Logger log = LoggerFactory.getLogger(HumanApprovalService.class);

    private static final Duration DEFAULT_REVIEW_EXPIRY = Duration.ofHours(72);

    private final ReviewQueueRepository reviewQueueRepository;
    private final CampaignRepository campaignRepository;
    private final AgentDecisionRecordRepository agentDecisionRecordRepository;
    private final CampaignLifecycleService campaignLifecycleService;
    private final CampaignStateMachine campaignStateMachine;
    private final ActionIntentService actionIntentService;
    private final ComplianceService complianceService;
    private final AuditService auditService;
    private final WebhookAmountResolver webhookAmountResolver;

    public HumanApprovalService(
            ReviewQueueRepository reviewQueueRepository,
            CampaignRepository campaignRepository,
            AgentDecisionRecordRepository agentDecisionRecordRepository,
            CampaignLifecycleService campaignLifecycleService,
            CampaignStateMachine campaignStateMachine,
            ActionIntentService actionIntentService,
            ComplianceService complianceService,
            AuditService auditService,
            WebhookAmountResolver webhookAmountResolver) {
        this.reviewQueueRepository = reviewQueueRepository;
        this.campaignRepository = campaignRepository;
        this.agentDecisionRecordRepository = agentDecisionRecordRepository;
        this.campaignLifecycleService = campaignLifecycleService;
        this.campaignStateMachine = campaignStateMachine;
        this.actionIntentService = actionIntentService;
        this.complianceService = complianceService;
        this.auditService = auditService;
        this.webhookAmountResolver = webhookAmountResolver;
    }

    /**
     * Creates a human-approval review for a campaign. Idempotent: an existing
     * PENDING review for the campaign is returned instead of duplicating.
     */
    @Transactional
    public ReviewQueue createReview(UUID merchantId, UUID campaignId, String reason) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        if (!campaign.getMerchantId().equals(merchantId)) {
            throw new TenantAccessDeniedException("Campaign " + campaignId + " does not belong to merchant " + merchantId);
        }

        return reviewQueueRepository.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(campaignId, ReviewQueueStatus.PENDING)
                .orElseGet(() -> {
                    ReviewQueue review = new ReviewQueue(UUID.randomUUID(), campaignId, merchantId);
                    review.setReason(reason != null ? reason : "Recovery requires merchant human review before execution");
                    review.setStatus(ReviewQueueStatus.PENDING);
                    review.setExpiresAt(Instant.now().plus(DEFAULT_REVIEW_EXPIRY));
                    reviewQueueRepository.saveAndFlush(review);

                    auditService.logEvent(
                            merchantId, campaignId, "REVIEW_CREATED", "SYSTEM", null,
                            "Human approval review created for campaign " + campaignId + ": " + review.getReason()
                    );
                    log.info("Created human approval review '{}' for campaign '{}' (merchant '{}')", review.getId(), campaignId, merchantId);
                    return review;
                });
    }

    @Transactional(readOnly = true)
    public Page<ReviewItemDto> listReviews(UUID merchantId, ReviewQueueStatus status, int page, int size, AuthenticatedUser currentUser) {
        validateMembership(merchantId, currentUser);

        Pageable pageable = PageRequest.of(page, Math.min(Math.max(size, 1), 100));
        Page<ReviewQueue> reviews = (status != null)
                ? reviewQueueRepository.findByMerchantIdAndStatus(merchantId, status, pageable)
                : reviewQueueRepository.findByMerchantId(merchantId, pageable);

        List<ReviewItemDto> items = reviews.getContent().stream()
                .map(r -> toReviewItem(merchantId, r))
                .toList();

        return new PageImpl<>(items, pageable, reviews.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ReviewItemDto getReview(UUID merchantId, UUID reviewId, AuthenticatedUser currentUser) {
        validateMembership(merchantId, currentUser);
        ReviewQueue review = loadReview(merchantId, reviewId);
        return toReviewItem(merchantId, review);
    }

    @Transactional(readOnly = true)
    public ReviewQueueSummaryDto getReviewSummary(UUID merchantId, AuthenticatedUser currentUser) {
        validateMembership(merchantId, currentUser);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : reviewQueueRepository.countByMerchantGroupedByStatus(merchantId)) {
            ReviewQueueStatus status = (ReviewQueueStatus) row[0];
            byStatus.put(status.name(), ((Number) row[1]).longValue());
        }
        long pending = byStatus.getOrDefault(ReviewQueueStatus.PENDING.name(), 0L);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new ReviewQueueSummaryDto(pending, total, byStatus);
    }

    @Transactional
    public ReviewDecisionResponse approveReview(UUID merchantId, UUID reviewId, String comment, AuthenticatedUser currentUser) {
        requireReviewer(merchantId, currentUser);
        ReviewQueue review = loadReviewForUpdate(merchantId, reviewId);
        Campaign campaign = campaignRepository.findById(review.getCampaignId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found for review: " + reviewId));

        ensureDecisionPossible(review, campaign, merchantId, currentUser);

        List<String> validation = new ArrayList<>();
        validation.add("Tenant ownership verified for merchant " + merchantId);
        validation.add("RBAC role " + currentUser.getRoleForMerchant(merchantId) + " authorized to approve");
        validation.add("Review status PENDING; campaign state " + campaign.getCurrentState() + " actionable");

        // Compliance revalidation (fresh engine evaluation, never trusts cached state)
        CampaignStatus stateBefore = campaign.getCurrentState();
        if (stateBefore != CampaignStatus.ELIGIBLE) {
            throw new InvalidRequestException(
                    "Campaign must be ELIGIBLE for compliance revalidation before approval (current state: " + stateBefore + ")");
        }

        ComplianceDecision compliance = complianceService.evaluateAndPersistCompliance(merchantId, campaign.getId());
        if (compliance.getStatus() != ComplianceStatus.COMPLIANCE_ALLOWED) {
            log.info("Approval of review '{}' blocked by compliance revalidation: {} - {}",
                    reviewId, compliance.getReason(), compliance.getDetailMessage());
            auditService.logEvent(
                    merchantId, campaign.getId(), "REVIEW_APPROVAL_BLOCKED", "USER", currentUser.getUserId(),
                    "Approval blocked by compliance revalidation: " + compliance.getReason() + " - " + compliance.getDetailMessage()
            );
            throw new InvalidRequestException(
                    "Approval blocked by compliance revalidation: " + compliance.getReason() + " - " + compliance.getDetailMessage());
        }
        validation.add("Compliance engine re-evaluated and ALLOWED (policy " + compliance.getPolicyVersion() + ")");
        validation.add("No duplicate/in-flight ActionIntent exists for the current attempt");

        // Create the ActionIntent through the authoritative boundary
        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());
        if (intent == null) {
            throw new InvalidRequestException("No executable action is available for the approved recovery (strategy maps to NO_ACTION)");
        }

        // Transition ELIGIBLE -> ACTION_PENDING only if the campaign hasn't moved already
        Campaign freshCampaign = campaignRepository.findById(campaign.getId()).orElse(campaign);
        if (freshCampaign.getCurrentState() == CampaignStatus.ELIGIBLE) {
            campaignLifecycleService.transitionState(
                    merchantId, campaign.getId(), CampaignStatus.ACTION_PENDING,
                    "Recovery authorized by merchant human approval (review " + reviewId + ")",
                    "USER", currentUser.getUserId()
            );
        }

        Instant now = Instant.now();
        review.setStatus(ReviewQueueStatus.APPROVED);
        review.setAssignedUserId(currentUser.getUserId());
        review.setReviewerComment(comment != null ? comment : "Approved by merchant");
        review.setReviewedAt(now);
        reviewQueueRepository.saveAndFlush(review);

        auditService.logEvent(
                merchantId, campaign.getId(), "REVIEW_APPROVED", "USER", currentUser.getUserId(),
                "Merchant approved review " + reviewId + " for campaign " + campaign.getId()
                        + ". ActionIntent " + intent.getId() + " authorized for execution."
        );

        ReviewDecisionResponse response = new ReviewDecisionResponse();
        response.setReviewId(reviewId);
        response.setCampaignId(campaign.getId());
        response.setDecision("APPROVED");
        response.setDecidedAt(now);
        response.setMessage("Recovery approved. ActionIntent " + intent.getId() + " scheduled for execution.");
        response.setReview(toReviewItem(merchantId, review));
        response.setActionIntent(ActionIntentDto.fromEntity(intent));
        response.setCampaign(CampaignDto.fromEntity(campaignRepository.findById(campaign.getId()).orElse(campaign)));
        response.setValidationSummary(validation);

        log.info("Merchant user '{}' APPROVED review '{}' for campaign '{}'", currentUser.getUserId(), reviewId, campaign.getId());
        return response;
    }

    @Transactional
    public ReviewDecisionResponse rejectReview(UUID merchantId, UUID reviewId, String comment, AuthenticatedUser currentUser) {
        requireReviewer(merchantId, currentUser);
        ReviewQueue review = loadReviewForUpdate(merchantId, reviewId);
        Campaign campaign = campaignRepository.findById(review.getCampaignId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found for review: " + reviewId));

        ensureDecisionPossible(review, campaign, merchantId, currentUser);

        List<String> validation = new ArrayList<>();
        validation.add("Tenant ownership verified for merchant " + merchantId);
        validation.add("RBAC role " + currentUser.getRoleForMerchant(merchantId) + " authorized to reject");
        validation.add("Review status PENDING; campaign state " + campaign.getCurrentState() + " actionable");

        // Cancel any pending intents, then terminate the campaign through the state machine
        actionIntentService.cancelPendingIntentsForCampaign(merchantId, campaign.getId(), "Rejected by merchant human review " + reviewId);

        Campaign fresh = campaignRepository.findById(campaign.getId()).orElse(campaign);
        if (!campaignStateMachine.isTerminal(fresh.getCurrentState())) {
            campaignLifecycleService.transitionState(
                    merchantId, campaign.getId(), CampaignStatus.CANCELLED,
                    "Recovery rejected by merchant human review (review " + reviewId + ")",
                    "USER", currentUser.getUserId()
            );
        }

        Instant now = Instant.now();
        review.setStatus(ReviewQueueStatus.REJECTED);
        review.setAssignedUserId(currentUser.getUserId());
        review.setReviewerComment(comment != null ? comment : "Rejected by merchant");
        review.setReviewedAt(now);
        reviewQueueRepository.saveAndFlush(review);

        auditService.logEvent(
                merchantId, campaign.getId(), "REVIEW_REJECTED", "USER", currentUser.getUserId(),
                "Merchant rejected review " + reviewId + " for campaign " + campaign.getId()
                        + ". Pending intents cancelled and campaign terminated."
        );

        ReviewDecisionResponse response = new ReviewDecisionResponse();
        response.setReviewId(reviewId);
        response.setCampaignId(campaign.getId());
        response.setDecision("REJECTED");
        response.setDecidedAt(now);
        response.setMessage("Recovery rejected. Campaign " + campaign.getId() + " terminated safely.");
        response.setReview(toReviewItem(merchantId, review));
        response.setCampaign(CampaignDto.fromEntity(campaignRepository.findById(campaign.getId()).orElse(campaign)));
        response.setValidationSummary(validation);

        log.info("Merchant user '{}' REJECTED review '{}' for campaign '{}'", currentUser.getUserId(), reviewId, campaign.getId());
        return response;
    }

    private void ensureDecisionPossible(ReviewQueue review, Campaign campaign, UUID merchantId, AuthenticatedUser currentUser) {
        if (review.getStatus() != ReviewQueueStatus.PENDING) {
            throw new InvalidRequestException(
                    "Review " + review.getId() + " has already been resolved (status: " + review.getStatus() + ")");
        }
        if (review.getExpiresAt() != null && review.getExpiresAt().isBefore(Instant.now())) {
            review.setStatus(ReviewQueueStatus.ESCALATED);
            review.setReviewedAt(Instant.now());
            review.setReviewerComment("Review expired before a merchant decision was recorded");
            reviewQueueRepository.saveAndFlush(review);
            auditService.logEvent(
                    merchantId, campaign.getId(), "REVIEW_EXPIRED", "SYSTEM", null,
                    "Review " + review.getId() + " expired and was escalated automatically"
            );
            throw new InvalidRequestException("Review " + review.getId() + " has expired and requires re-submission");
        }
        if (campaignStateMachine.isTerminal(campaign.getCurrentState())) {
            review.setStatus(ReviewQueueStatus.CLOSED);
            review.setReviewedAt(Instant.now());
            review.setReviewerComment("Campaign reached terminal state " + campaign.getCurrentState() + " before a decision");
            reviewQueueRepository.saveAndFlush(review);
            auditService.logEvent(
                    merchantId, campaign.getId(), "REVIEW_CLOSED_STALE", "SYSTEM", null,
                    "Review " + review.getId() + " closed because campaign reached " + campaign.getCurrentState()
            );
            throw new InvalidRequestException(
                    "Campaign " + campaign.getId() + " is already in terminal state " + campaign.getCurrentState()
                            + "; this review is stale and was closed");
        }
    }

    private ReviewItemDto toReviewItem(UUID merchantId, ReviewQueue review) {
        Campaign campaign = campaignRepository.findById(review.getCampaignId()).orElse(null);
        AgentDecisionRecord agentRecord = agentDecisionRecordRepository
                .findFirstByCampaignIdOrderByCreatedAtDesc(review.getCampaignId()).orElse(null);

        AgentDecisionRecordDto agentDto = agentRecord != null ? AgentDecisionRecordDto.fromEntity(agentRecord) : null;

        BigDecimal amount = campaign != null
                ? webhookAmountResolver.resolveAmount(merchantId, campaign.getPaymentId()).orElse(null)
                : null;

        return ReviewItemDto.from(review, campaign, agentDto, amount);
    }

    private ReviewQueue loadReview(UUID merchantId, UUID reviewId) {
        ReviewQueue review = reviewQueueRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found with ID: " + reviewId));
        if (!review.getMerchantId().equals(merchantId)) {
            throw new ResourceNotFoundException("Review not found with ID: " + reviewId);
        }
        return review;
    }

    private ReviewQueue loadReviewForUpdate(UUID merchantId, UUID reviewId) {
        ReviewQueue review = reviewQueueRepository.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found with ID: " + reviewId));
        if (!review.getMerchantId().equals(merchantId)) {
            throw new ResourceNotFoundException("Review not found with ID: " + reviewId);
        }
        return review;
    }

    private void validateMembership(UUID merchantId, AuthenticatedUser currentUser) {
        if (merchantId == null) {
            throw new TenantAccessDeniedException("Merchant context is required");
        }
        if (currentUser == null || (!currentUser.isSystemAdmin() && !currentUser.isMemberOfMerchant(merchantId))) {
            throw new TenantAccessDeniedException("Access denied for merchant: " + merchantId);
        }
    }

    private void requireReviewer(UUID merchantId, AuthenticatedUser currentUser) {
        validateMembership(merchantId, currentUser);
        if (currentUser == null || !currentUser.hasMerchantRole(merchantId, "REVIEWER")) {
            throw new TenantAccessDeniedException(
                    "Requires REVIEWER or MERCHANT_ADMIN privilege for merchant " + merchantId);
        }
    }
}

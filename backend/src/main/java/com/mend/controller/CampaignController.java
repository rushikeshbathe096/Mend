package com.mend.controller;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.ComplianceDecisionRepository;
import com.mend.domain.repository.ReviewQueueRepository;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.dto.*;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import com.mend.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController {

    private static final Logger log = LoggerFactory.getLogger(CampaignController.class);

    private final CampaignQueryService campaignQueryService;
    private final CampaignLifecycleService campaignLifecycleService;
    private final RecoveryOrchestratorService recoveryOrchestratorService;
    private final ActionIntentService actionIntentService;
    private final ActionExecutionService actionExecutionService;
    private final ComplianceDecisionRepository complianceDecisionRepository;
    private final ReviewQueueRepository reviewQueueRepository;
    private final CampaignRepository campaignRepository;
    private final ActionIntentRepository actionIntentRepository;
    private final AuditService auditService;
    private final com.mend.service.ActionIntentClaimService actionIntentClaimService;

    public CampaignController(
            CampaignQueryService campaignQueryService,
            CampaignLifecycleService campaignLifecycleService,
            RecoveryOrchestratorService recoveryOrchestratorService,
            ActionIntentService actionIntentService,
            ActionExecutionService actionExecutionService,
            ComplianceDecisionRepository complianceDecisionRepository,
            ReviewQueueRepository reviewQueueRepository,
            CampaignRepository campaignRepository,
            ActionIntentRepository actionIntentRepository,
            AuditService auditService,
            com.mend.service.ActionIntentClaimService actionIntentClaimService) {
        this.campaignQueryService = campaignQueryService;
        this.campaignLifecycleService = campaignLifecycleService;
        this.recoveryOrchestratorService = recoveryOrchestratorService;
        this.actionIntentService = actionIntentService;
        this.actionExecutionService = actionExecutionService;
        this.complianceDecisionRepository = complianceDecisionRepository;
        this.reviewQueueRepository = reviewQueueRepository;
        this.campaignRepository = campaignRepository;
        this.actionIntentRepository = actionIntentRepository;
        this.auditService = auditService;
        this.actionIntentClaimService = actionIntentClaimService;
    }

    public static class CreateCampaignRequest {
        private String paymentId;
        private String customerIdHash;
        private String subscriptionId;

        public String getPaymentId() { return paymentId; }
        public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

        public String getCustomerIdHash() { return customerIdHash; }
        public void setCustomerIdHash(String customerIdHash) { this.customerIdHash = customerIdHash; }

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    }

    @GetMapping
    public ResponseEntity<PageResponse<CampaignDto>> getCampaigns(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        PageResponse<CampaignDto> response = campaignQueryService.getCampaigns(
                effectiveMerchantId, status, page, size, sortBy, sortOrder, currentUser
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CampaignDto> getCampaign(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        CampaignDto campaign = campaignQueryService.getCampaign(effectiveMerchantId, id, currentUser);
        return ResponseEntity.ok(campaign);
    }

    @GetMapping("/{id}/timeline")
    public ResponseEntity<CampaignTimelineDto> getCampaignTimeline(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        CampaignTimelineDto timeline = campaignQueryService.getCampaignTimeline(effectiveMerchantId, id, currentUser);
        return ResponseEntity.ok(timeline);
    }

    @PostMapping
    public ResponseEntity<CampaignDto> createCampaign(
            @RequestBody CreateCampaignRequest request,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        if (request == null || request.getPaymentId() == null || request.getPaymentId().isBlank()) {
            throw new InvalidRequestException("paymentId is required to create a campaign");
        }

        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(
                effectiveMerchantId, request.getPaymentId(), request.getCustomerIdHash(), request.getSubscriptionId()
        );

        return ResponseEntity.ok(CampaignDto.fromEntity(campaign));
    }

    @PostMapping("/start-recovery")
    public ResponseEntity<StartRecoveryResponseDto> startRecovery(
            @RequestBody StartRecoveryRequest request,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        if (request == null || request.getPaymentId() == null || request.getPaymentId().isBlank()) {
            throw new InvalidRequestException("paymentId is required to start recovery");
        }

        // 1. Get or create campaign in ELIGIBLE state for the authoritative merchant
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(
                effectiveMerchantId, request.getPaymentId(), request.getCustomerIdHash(), request.getSubscriptionId()
        );

        // Log RECOVERY_STARTED Audit Event
        auditService.logEvent(
                effectiveMerchantId,
                campaign.getId(),
                "RECOVERY_STARTED",
                "USER",
                currentUser != null ? currentUser.getUserId() : null,
                "Manual recovery initiated for payment: " + campaign.getPaymentId()
        );

        // 2. Deterministic orchestration: strategy, compliance, ActionIntent creation (no AI, ADR-003)
        ActionIntent actionIntent = recoveryOrchestratorService.orchestrateRecovery(effectiveMerchantId, campaign.getId());

        // 3. If an ActionIntent is created and claimable, claim it atomically (same primitive as the scheduler) and
        //    execute it. If another worker already owns it (or it is not due yet), the scheduler executes it instead.
        PaymentExecutionResult executionResult = null;
        if (actionIntent != null) {
            String workerId = "web-recovery-worker-" + UUID.randomUUID().toString().substring(0, 8);
            var claimed = actionIntentClaimService.claimSpecificIntent(actionIntent.getId(), workerId);
            if (claimed.isPresent()) {
                actionIntent = claimed.get();
                try {
                    executionResult = actionExecutionService.executeActionIntent(actionIntent.getId(), workerId, actionIntent.getClaimToken());
                } catch (Exception e) {
                    log.error("Execution failed for ActionIntent '{}': {}", actionIntent.getId(), e.getMessage());
                }
            } else {
                log.info("ActionIntent '{}' not claimed by the manual path; left to its current owner / the scheduler", actionIntent.getId());
            }
        }

        // 4. Fetch latest state and decision records for the response
        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElse(campaign);
        CampaignDto campaignDto = CampaignDto.fromEntity(updatedCampaign);
        ActionIntentDto actionIntentDto = actionIntent != null ? ActionIntentDto.fromEntity(actionIntent) : null;

        // M3: status from the stored compliance decision and the review queue (was: agent decision records)
        boolean requiresHumanApproval = reviewQueueRepository
                .findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(campaign.getId(), com.mend.domain.enums.ReviewQueueStatus.PENDING)
                .isPresent();
        String complianceStatus = complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId()).stream()
                .findFirst().map(d -> d.getStatus().name()).orElse(null);

        String message = "Recovery orchestration initiated successfully.";
        if (executionResult != null && executionResult.isSuccess()) {
            // only a mock-mode synthetic capture reports SUCCESS (ActionExecutionService.toReport); a link is PENDING
            message = "Mock provider simulated a capture (SYNTHETIC, no real money moved).";
        } else if (executionResult != null && executionResult.isFailure()) {
            message = "Recovery execution failed: " + executionResult.getMessage();
        } else if (requiresHumanApproval) {
            message = "Recovery requires merchant human approval. Routed to review queue.";
        } else if ("COMPLIANCE_BLOCKED".equalsIgnoreCase(complianceStatus)) {
            message = "Recovery blocked by compliance policy rules.";
        }

        StartRecoveryResponseDto response = new StartRecoveryResponseDto(
                campaignDto,
                actionIntentDto,
                executionResult,
                complianceStatus,
                requiresHumanApproval,
                message
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<CampaignDto> pauseCampaign(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        campaignLifecycleService.transitionState(
                effectiveMerchantId, id, CampaignStatus.ACTION_PENDING, "Campaign paused by merchant", "USER", currentUser != null ? currentUser.getUserId() : null
        );

        CampaignDto campaign = campaignQueryService.getCampaign(effectiveMerchantId, id, currentUser);
        return ResponseEntity.ok(campaign);
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<CampaignDto> resumeCampaign(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        campaignLifecycleService.transitionState(
                effectiveMerchantId, id, CampaignStatus.ELIGIBLE, "Campaign resumed by merchant", "USER", currentUser != null ? currentUser.getUserId() : null
        );

        CampaignDto campaign = campaignQueryService.getCampaign(effectiveMerchantId, id, currentUser);
        return ResponseEntity.ok(campaign);
    }

    @PostMapping("/{id}/terminate")
    public ResponseEntity<CampaignDto> terminateCampaign(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        campaignLifecycleService.transitionState(
                effectiveMerchantId, id, CampaignStatus.CANCELLED, "Campaign terminated by merchant", "USER", currentUser != null ? currentUser.getUserId() : null
        );

        CampaignDto campaign = campaignQueryService.getCampaign(effectiveMerchantId, id, currentUser);
        return ResponseEntity.ok(campaign);
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
        throw new TenantAccessDeniedException("X-Merchant-Id header is required or no merchant association found for user");
    }
}

package com.mend.service;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryAttribution;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.TenantGuard;
import com.mend.statemachine.CampaignStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CampaignLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(CampaignLifecycleService.class);

    private final CampaignRepository campaignRepository;
    private final ClassificationResultRepository classificationResultRepository;
    private final MerchantRepository merchantRepository;
    private final CampaignStateMachine campaignStateMachine;
    private final AuditService auditService;
    private final RecoveryOrchestratorService recoveryOrchestratorService;

    private final InvoiceKeyResolver invoiceKeyResolver;
    /** Bug sweep item 5: SYNTHETIC (simulated) recoveries are only possible with the mock provider. */
    private final boolean mockProvider;

    public CampaignLifecycleService(
            CampaignRepository campaignRepository,
            ClassificationResultRepository classificationResultRepository,
            MerchantRepository merchantRepository,
            CampaignStateMachine campaignStateMachine,
            AuditService auditService,
            @Lazy RecoveryOrchestratorService recoveryOrchestratorService,
            InvoiceKeyResolver invoiceKeyResolver,
            @org.springframework.beans.factory.annotation.Value("${mend.payment.provider:mock}") String paymentProvider) {
        this.invoiceKeyResolver = invoiceKeyResolver;
        this.mockProvider = "mock".equalsIgnoreCase(paymentProvider);
        this.campaignRepository = campaignRepository;
        this.classificationResultRepository = classificationResultRepository;
        this.merchantRepository = merchantRepository;
        this.campaignStateMachine = campaignStateMachine;
        this.auditService = auditService;
        this.recoveryOrchestratorService = recoveryOrchestratorService;
    }

    @Transactional
    public Campaign getOrCreateCampaign(UUID merchantId, String paymentId, String customerIdHash, String subscriptionId) {
        UUID effectiveMerchantId = merchantId;
        if (effectiveMerchantId == null) {
            effectiveMerchantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        }

        // Ensure merchant entity exists to satisfy foreign key constraint
        if (!merchantRepository.existsById(effectiveMerchantId)) {
            try {
                Merchant defaultMerchant = new Merchant(effectiveMerchantId, "Default Merchant");
                merchantRepository.saveAndFlush(defaultMerchant);
            } catch (Exception e) {
                log.debug("Merchant creation race condition handled for ID='{}'", effectiveMerchantId);
            }
        }

        String effectivePaymentId = (paymentId != null && !paymentId.isBlank()) 
                ? paymentId.trim() 
                : "UNKNOWN_PAYMENT_" + UUID.randomUUID();

        Optional<Campaign> existing = campaignRepository.findByMerchantIdAndPaymentId(effectiveMerchantId, effectivePaymentId);
        if (existing.isPresent()) {
            log.info("Campaign already exists for merchantId='{}', paymentId='{}': campaignId='{}'",
                    effectiveMerchantId, effectivePaymentId, existing.get().getId());
            return existing.get();
        }

        Campaign newCampaign = new Campaign(UUID.randomUUID(), effectiveMerchantId);
        newCampaign.setPaymentId(effectivePaymentId);
        newCampaign.setCustomerIdHash(customerIdHash);
        newCampaign.setSubscriptionId(subscriptionId);
        newCampaign.setCurrentState(CampaignStatus.CREATED);
        newCampaign.setInvoiceKey(invoiceKeyResolver.resolveForCampaign(effectiveMerchantId, effectivePaymentId, newCampaign.getId()));
        newCampaign.setProviderPaymentId(invoiceKeyResolver.resolveProviderPaymentId(effectiveMerchantId, effectivePaymentId));

        try {
            Campaign saved = campaignRepository.saveAndFlush(newCampaign);
            auditService.logStateTransition(
                    effectiveMerchantId,
                    saved.getId(),
                    null,
                    CampaignStatus.CREATED,
                    "SYSTEM",
                    null,
                    "Campaign created for payment: " + effectivePaymentId
            );
            log.info("Successfully created campaign campaignId='{}' for merchantId='{}', paymentId='{}'",
                    saved.getId(), effectiveMerchantId, effectivePaymentId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent creation detected for merchantId='{}', paymentId='{}'. Re-querying...",
                    effectiveMerchantId, effectivePaymentId);
            return campaignRepository.findByMerchantIdAndPaymentId(effectiveMerchantId, effectivePaymentId)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional
    public Campaign transitionState(
            UUID merchantId,
            UUID campaignId,
            CampaignStatus targetState,
            String reason,
            String actorType,
            UUID actorId) {

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        if (!campaign.getMerchantId().equals(merchantId)) {
            throw new TenantAccessDeniedException("Campaign " + campaignId + " does not belong to merchant " + merchantId);
        }

        CampaignStatus previousState = campaign.getCurrentState();

        // Validate state transition against state machine rules
        campaignStateMachine.validateTransition(previousState, targetState);

        campaign.setCurrentState(targetState);
        Campaign updated = campaignRepository.saveAndFlush(campaign);

        auditService.logStateTransition(
                merchantId,
                campaignId,
                previousState,
                targetState,
                actorType != null ? actorType : "SYSTEM",
                actorId,
                reason
        );

        log.info("Transitioned campaignId='{}' [{} -> {}], reason='{}'", campaignId, previousState, targetState, reason);

        return updated;
    }

    /**
     * Bug sweep item 2: the campaign goes to RECOVERED and records who recovered it, in one save. This is the only way to
     * use the pre-action → RECOVERED edges (CREATED / CLASSIFIED / ELIGIBLE / SCHEDULED), and those edges need
     * {@link RecoveryAttribution#ORIGINAL_PAYMENT_LATE_CAPTURE}; {@link #transitionState} still rejects them.
     * {@link RecoveryAttribution#SYNTHETIC} is refused unless the mock provider is active (bug sweep item 5).
     */
    @Transactional
    public Campaign recover(
            UUID merchantId,
            UUID campaignId,
            RecoveryAttribution attribution,
            String reason,
            String actorType,
            UUID actorId) {

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        if (!campaign.getMerchantId().equals(merchantId)) {
            throw new TenantAccessDeniedException("Campaign " + campaignId + " does not belong to merchant " + merchantId);
        }

        if (attribution == RecoveryAttribution.SYNTHETIC && !mockProvider) {
            throw new IllegalStateException("SYNTHETIC recovery is only possible with the mock payment provider");
        }

        CampaignStatus previousState = campaign.getCurrentState();
        campaignStateMachine.validateRecovery(previousState, attribution);

        campaign.setCurrentState(CampaignStatus.RECOVERED);
        campaign.setRecoveryAttribution(attribution);
        Campaign updated = campaignRepository.saveAndFlush(campaign);

        auditService.logStateTransition(
                merchantId,
                campaignId,
                previousState,
                CampaignStatus.RECOVERED,
                actorType != null ? actorType : "SYSTEM",
                actorId,
                reason
        );

        log.info("Recovered campaignId='{}' [{} -> RECOVERED], attribution={}, reason='{}'", campaignId, previousState, attribution, reason);

        return updated;
    }

    @Transactional
    public Campaign processClassificationResult(WebhookEvent event, ClassificationResult classificationResult) {
        if (event == null || classificationResult == null) {
            throw new IllegalArgumentException("WebhookEvent and ClassificationResult cannot be null");
        }

        UUID merchantId = event.getMerchantId();
        String paymentId = event.getExternalEventId();

        // Step 1: Idempotent Campaign Resolution / Creation
        Campaign campaign = getOrCreateCampaign(merchantId, paymentId, null, null);

        // Link classification result to campaign
        classificationResult.setCampaignId(campaign.getId());
        classificationResultRepository.save(classificationResult);

        // If campaign has already progressed past CREATED state, return existing campaign safely
        if (campaign.getCurrentState() != CampaignStatus.CREATED) {
            log.info("Campaign ID '{}' is already in state '{}'. Skipping duplicate classification state transition.",
                    campaign.getId(), campaign.getCurrentState());
            return campaign;
        }

        // Step 2: Transition CREATED -> CLASSIFIED
        campaign.setFailureClass(classificationResult.getFailureClass());
        campaignRepository.save(campaign);

        campaign = transitionState(
                campaign.getMerchantId(),
                campaign.getId(),
                CampaignStatus.CLASSIFIED,
                "Failure classified: " + classificationResult.getFailureClass(),
                "SYSTEM",
                null
        );

        // Step 3: Evaluate Recovery Eligibility & Transition to ELIGIBLE or EXHAUSTED
        boolean isEligible = evaluateEligibility(classificationResult);

        if (isEligible) {
            campaign = transitionState(
                    campaign.getMerchantId(),
                    campaign.getId(),
                    CampaignStatus.ELIGIBLE,
                    "Eligible for recovery strategy execution",
                    "SYSTEM",
                    null
            );

            if (recoveryOrchestratorService != null) {
                recoveryOrchestratorService.orchestrateRecovery(campaign.getMerchantId(), campaign.getId());
                campaign = campaignRepository.findById(campaign.getId()).orElse(campaign);
            }
        } else {
            String ineligibilityReason = "Ineligible for recovery: failure class '" + classificationResult.getFailureClass()
                    + "' has no documented recovery mapping";

            campaign = transitionState(
                    campaign.getMerchantId(),
                    campaign.getId(),
                    CampaignStatus.EXHAUSTED,
                    ineligibilityReason,
                    "SYSTEM",
                    null
            );
        }

        return campaign;
    }

    /**
     * M3 U2.2 (ADR-003 §4): eligible iff the class is a taxonomy class other than UNCLASSIFIED. Legacy (pre-M3, AI-era)
     * classes and UNCLASSIFIED fail closed. No confidence is involved.
     */
    public boolean evaluateEligibility(ClassificationResult classificationResult) {
        if (classificationResult == null) {
            return false;
        }
        return com.mend.taxonomy.PaymentFailureClass.isRecoverable(classificationResult.getFailureClass());
    }

    @Transactional(readOnly = true)
    public Campaign getCampaign(UUID merchantId, UUID campaignId, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);
        return campaignRepository.findByMerchantIdAndId(merchantId, campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found for merchant " + merchantId + " and campaign " + campaignId));
    }

    @Transactional(readOnly = true)
    public List<Campaign> getCampaignsForMerchant(UUID merchantId, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);
        return campaignRepository.findByMerchantId(merchantId);
    }

}

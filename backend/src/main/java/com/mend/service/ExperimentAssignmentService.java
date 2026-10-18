package com.mend.service;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.HoldoutAssignment;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.entity.MerchantExperimentSalt;
import com.mend.domain.repository.HoldoutAssignmentRepository;
import com.mend.domain.repository.MerchantConfigRepository;
import com.mend.domain.repository.MerchantExperimentSaltRepository;
import com.mend.experiment.Arm;
import com.mend.experiment.HoldoutAssigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * M4 U4.1 (ADR-004 §1-§3): the holdout assignment of a campaign, decided once at its first eligibility and stored
 * immutably. Re-entering ELIGIBLE never re-assigns. Campaigns that were eligible before M4 have no assignment
 * (UNASSIGNED: not in the experiment, treated as before).
 */
@Service
public class ExperimentAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentAssignmentService.class);
    private static final int FIRST_SALT_VERSION = 1;

    private final HoldoutAssignmentRepository assignmentRepository;
    private final MerchantExperimentSaltRepository saltRepository;
    private final MerchantConfigRepository merchantConfigRepository;
    private final AuditService auditService;
    private final DecisionLogService decisionLogService;
    private final SecureRandom random = new SecureRandom();

    public ExperimentAssignmentService(HoldoutAssignmentRepository assignmentRepository,
                                       MerchantExperimentSaltRepository saltRepository,
                                       MerchantConfigRepository merchantConfigRepository,
                                       AuditService auditService,
                                       DecisionLogService decisionLogService) {
        this.decisionLogService = decisionLogService;
        this.assignmentRepository = assignmentRepository;
        this.saltRepository = saltRepository;
        this.merchantConfigRepository = merchantConfigRepository;
        this.auditService = auditService;
    }

    /**
     * Assigns the campaign in the caller's transaction (the CLASSIFIED -> ELIGIBLE transition). An existing assignment is
     * returned unchanged.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public HoldoutAssignment assignAtFirstEligibility(Campaign campaign) {
        Objects.requireNonNull(campaign, "campaign");
        Optional<HoldoutAssignment> existing = assignmentRepository.findById(campaign.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        String invoiceKey = Objects.requireNonNull(campaign.getInvoiceKey(), "campaign invoice_key (NOT NULL since V13)");
        // U4.1 review: two campaigns of one invoice becoming eligible at once must not both hash (a rate change between
        // their reads would put the invoice in two arms). The lock lasts until this transaction ends.
        assignmentRepository.lockInvoice("holdout:" + campaign.getMerchantId() + ":" + invoiceKey);
        int currentRateBp = merchantConfigRepository.findByMerchantId(campaign.getMerchantId())
                .map(MerchantConfig::getHoldoutRateBp).orElse(0);
        Optional<HoldoutAssignment> sameInvoice = assignmentRepository.findFirstForInvoice(campaign.getMerchantId(), invoiceKey, campaign.getId());
        int written;
        if (sameInvoice.isPresent() && currentRateBp > 0) {
            // One arm per invoice while the experiment runs: copy the stored decision, never re-hash under a new rate.
            // DECISION U4.1-e: with the holdout off (rate 0) the merchant has ended the experiment, so a new campaign of a
            // former CONTROL invoice is hashed at rate 0 (TREATMENT) instead of being withheld indefinitely.
            HoldoutAssignment first = sameInvoice.get();
            written = assignmentRepository.insertIfAbsent(campaign.getId(), campaign.getMerchantId(), first.getArm().name(),
                    first.getBucket(), first.getHoldoutRateBp(), first.getSaltVersion(), first.getHashVersion(),
                    HoldoutAssignment.SOURCE_INVOICE_REUSE, Instant.now());
        } else {
            int rateBp = currentRateBp;
            MerchantExperimentSalt salt = salt(campaign.getMerchantId(), MerchantExperimentSalt.ASSIGNMENT);
            int bucket = HoldoutAssigner.bucket(salt.getSalt(), invoiceKey);
            Arm arm = HoldoutAssigner.arm(bucket, rateBp);
            written = assignmentRepository.insertIfAbsent(campaign.getId(), campaign.getMerchantId(), arm.name(), bucket, rateBp,
                    salt.getSaltVersion(), HoldoutAssigner.HASH_VERSION, HoldoutAssignment.SOURCE_HASH, Instant.now());
        }
        HoldoutAssignment stored = assignmentRepository.findById(campaign.getId())
                .orElseThrow(() -> new IllegalStateException("holdout assignment missing after insert for campaign " + campaign.getId()));
        if (written == 1) {
            // U4.2: the assignment and its propensity are logged in the assigning transaction (invariant 21).
            decisionLogService.assignment(stored);
            auditService.logStructuredEvent(campaign.getMerchantId(), campaign.getId(), "HOLDOUT_ASSIGNED", "SYSTEM", null,
                    "Holdout assignment " + stored.getArm() + " (rate " + stored.getHoldoutRateBp() + " bp, " + stored.getSource() + ")",
                    Map.of("arm", stored.getArm().name(), "holdoutRateBp", stored.getHoldoutRateBp(), "saltVersion", stored.getSaltVersion(),
                            "hashVersion", stored.getHashVersion(), "source", stored.getSource()),
                    null);
            log.info("Campaign '{}' assigned {} (rate {} bp, {})", campaign.getId(), stored.getArm(), stored.getHoldoutRateBp(), stored.getSource());
        }
        return stored;
    }

    /** The stored arm, or empty for a campaign that was never assigned (pre-M4). */
    @Transactional(readOnly = true)
    public Optional<Arm> armOf(UUID campaignId) {
        return assignmentRepository.findArmByCampaignId(campaignId);
    }

    public boolean isControl(UUID campaignId) {
        return armOf(campaignId).filter(a -> a == Arm.CONTROL).isPresent();
    }

    /** The merchant's current salt for a purpose, created (version 1, 32 random bytes) on first use. */
    @Transactional(propagation = Propagation.MANDATORY)
    public MerchantExperimentSalt salt(UUID merchantId, String purpose) {
        Optional<MerchantExperimentSalt> current = saltRepository.findFirstByMerchantIdAndPurposeOrderBySaltVersionDesc(merchantId, purpose);
        if (current.isPresent()) {
            return current.get();
        }
        byte[] fresh = new byte[HoldoutAssigner.SALT_BYTES];
        random.nextBytes(fresh);
        saltRepository.insertIfAbsent(merchantId, purpose, FIRST_SALT_VERSION, fresh);
        return saltRepository.findFirstByMerchantIdAndPurposeOrderBySaltVersionDesc(merchantId, purpose)
                .orElseThrow(() -> new IllegalStateException("salt missing after insert for merchant " + merchantId));
    }
}

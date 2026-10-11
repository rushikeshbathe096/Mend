package com.mend.service;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.ActionIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ActionIntentClaimService {

    private static final Logger log = LoggerFactory.getLogger(ActionIntentClaimService.class);

    private final ActionIntentRepository actionIntentRepository;
    private final ActionIntentTransitions transitions;
    private final AuditService auditService;
    private final int batchSize;
    private final Duration leaseDuration;

    public ActionIntentClaimService(
            ActionIntentRepository actionIntentRepository,
            ActionIntentTransitions transitions,
            AuditService auditService,
            @org.springframework.beans.factory.annotation.Value("${mend.scheduler.batch-size:50}") int batchSize,
            @org.springframework.beans.factory.annotation.Value("${mend.scheduler.lease-duration-minutes:5}") int leaseDurationMinutes) {
        this.actionIntentRepository = actionIntentRepository;
        this.transitions = transitions;
        this.auditService = auditService;
        this.batchSize = batchSize;
        this.leaseDuration = Duration.ofMinutes(leaseDurationMinutes);
    }

    @Transactional
    public List<ActionIntent> promoteScheduledIntents() {
        Instant now = Instant.now();
        Pageable pageable = PageRequest.of(0, batchSize);
        List<ActionIntent> scheduledIntents = actionIntentRepository.findDueClaimableIntents(
                ActionIntentStatus.SCHEDULED, CampaignStatus.ACTION_PENDING, now, pageable);

        List<ActionIntent> promotedIntents = new ArrayList<>();
        for (ActionIntent intent : scheduledIntents) {
            if (!transitions.promote(intent.getId())) {
                continue;
            }
            intent.setStatus(ActionIntentStatus.READY);
            promotedIntents.add(intent);

            log.info("Promoted Action Intent '{}' for campaign '{}' from SCHEDULED to READY", intent.getId(), intent.getCampaignId());
        }

        return promotedIntents;
    }

    /**
     * Not transactional on purpose (M2 U6): every promote and every claim commits in its own transaction, so a unique
     * violation of the one-in-flight-per-invoice index under a true race aborts only that claim.
     */
    public List<ActionIntent> claimDueIntents(String workerId, int limit) {
        Instant now = Instant.now();
        Pageable pageable = PageRequest.of(0, Math.min(limit, batchSize));

        promoteScheduledIntents();

        List<ActionIntent> readyIntents = actionIntentRepository.findDueClaimableIntents(
                ActionIntentStatus.READY, CampaignStatus.ACTION_PENDING, now, pageable);
        List<ActionIntent> claimedIntents = new ArrayList<>();

        for (ActionIntent intent : readyIntents) {
            String claimToken = UUID.randomUUID().toString();
            if (tryClaim(intent.getId(), intent.getMerchantId(), intent.getCampaignId(), workerId, claimToken, now)) {
                intent.setStatus(ActionIntentStatus.CLAIMED);
                intent.setClaimedAt(now);
                intent.setClaimToken(claimToken);
                intent.setWorkerId(workerId);
                claimedIntents.add(intent);

                log.info("Worker '{}' successfully claimed Action Intent '{}' for campaign '{}' (Token: {})",
                        workerId, intent.getId(), intent.getCampaignId(), claimToken);
            } else {
                log.info("Worker '{}' failed to claim Action Intent '{}' due to concurrent update", workerId, intent.getId());
            }
        }

        return claimedIntents;
    }

    @Transactional
    public List<ActionIntent> releaseExpiredClaims() {
        Instant expiredThreshold = Instant.now().minus(leaseDuration);
        List<ActionIntent> expiredClaims = actionIntentRepository.findExpiredClaims(expiredThreshold);

        List<ActionIntent> releasedIntents = new ArrayList<>();
        for (ActionIntent intent : expiredClaims) {
            // Only undispatched claims are released; possibly-dispatched work (EXECUTING) never returns to READY (invariant 13).
            if (!transitions.releaseIfUndispatched(intent.getId(), expiredThreshold)) {
                continue;
            }
            intent.setStatus(ActionIntentStatus.READY);
            intent.setClaimedAt(null);
            intent.setClaimToken(null);
            intent.setWorkerId(null);
            releasedIntents.add(intent);

            auditService.logEvent(
                    intent.getMerchantId(),
                    intent.getCampaignId(),
                    "CLAIM_RELEASED",   // M2 §9 failure table: worker crash before dispatch
                    "SYSTEM",
                    null,
                    "Released expired claim for Action Intent " + intent.getId() + " back to READY"
            );

            log.warn("Released expired claim lease for Action Intent '{}' back to READY", intent.getId());
        }

        return releasedIntents;
    }

    @Transactional
    public List<ActionIntent> processExpiredIntents() {
        Instant now = Instant.now();
        List<ActionIntent> expiredIntents = actionIntentRepository.findExpiredIntents(now);

        List<ActionIntent> processed = new ArrayList<>();
        for (ActionIntent intent : expiredIntents) {
            if (!transitions.expire(intent.getId(), now)) {
                continue;
            }
            intent.setStatus(ActionIntentStatus.EXPIRED);
            intent.setCompletedAt(now);
            processed.add(intent);

            auditService.logEvent(
                    intent.getMerchantId(),
                    intent.getCampaignId(),
                    "STATE_TRANSITION",
                    "SYSTEM",
                    null,
                    "Action Intent " + intent.getId() + " marked EXPIRED (past execution window)"
            );

            log.info("Action Intent '{}' marked EXPIRED (scheduledAt: {}, expiresAt: {})",
                    intent.getId(), intent.getScheduledAt(), intent.getExpiresAt());
        }

        return processed;
    }

    /**
     * Atomic claim of one specific intent, with the same predicate as the scheduler's claim (READY, due, campaign
     * ACTION_PENDING). Used by every non-scheduler path into execution. Empty when another actor owns it or it is not claimable.
     */
    public Optional<ActionIntent> claimSpecificIntent(UUID intentId, String workerId) {
        String claimToken = UUID.randomUUID().toString();
        Optional<ActionIntent> target = actionIntentRepository.findById(intentId);
        if (target.isEmpty()
                || !tryClaim(intentId, target.get().getMerchantId(), target.get().getCampaignId(), workerId, claimToken, Instant.now())) {
            log.info("Worker '{}' could not claim Action Intent '{}' (not claimable or owned by another worker)", workerId, intentId);
            return Optional.empty();
        }
        return actionIntentRepository.findById(intentId);
    }

    /**
     * One claim in its own transaction ({@code claimIsolated}, REQUIRES_NEW). The claim predicate already
     * refuses an intent whose invoice has another in-flight intent; under a true race the unique index decides, and the
     * loser is simply not claimed.
     */
    private boolean tryClaim(UUID intentId, UUID merchantId, UUID campaignId, String workerId, String claimToken, Instant now) {
        try {
            return transitions.claimIsolated(intentId, workerId, claimToken, now);
        } catch (org.springframework.dao.DataIntegrityViolationException violation) {
            if (!violates(violation, ONE_IN_FLIGHT_INDEX)) {
                throw violation;
            }
            log.info("event=claim_blocked_inflight intent_id={} worker={}", intentId, workerId);
            auditService.logEvent(merchantId, campaignId, "CLAIM_BLOCKED_INFLIGHT", "SYSTEM", null,
                    "intent=" + intentId + " not claimed: another intent for the same invoice is in flight");
            return false;
        }
    }

    static final String ONE_IN_FLIGHT_INDEX = "ux_action_intents_one_inflight_per_invoice";

    /** True when the violated constraint is {@code constraintName} (searched through the cause chain). */
    private static boolean violates(Throwable error, String constraintName) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve && constraintName.equals(cve.getConstraintName())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains(constraintName)) {
                return true;
            }
        }
        return false;
    }
}

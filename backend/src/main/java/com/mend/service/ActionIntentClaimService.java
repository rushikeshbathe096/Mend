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
import java.util.UUID;

@Service
public class ActionIntentClaimService {

    private static final Logger log = LoggerFactory.getLogger(ActionIntentClaimService.class);

    private final ActionIntentRepository actionIntentRepository;
    private final AuditService auditService;
    private final int batchSize;
    private final Duration leaseDuration;

    public ActionIntentClaimService(
            ActionIntentRepository actionIntentRepository,
            AuditService auditService,
            @org.springframework.beans.factory.annotation.Value("${mend.scheduler.batch-size:50}") int batchSize,
            @org.springframework.beans.factory.annotation.Value("${mend.scheduler.lease-duration-minutes:5}") int leaseDurationMinutes) {
        this.actionIntentRepository = actionIntentRepository;
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
            intent.setStatus(ActionIntentStatus.READY);
            actionIntentRepository.save(intent);
            promotedIntents.add(intent);

            log.info("Promoted Action Intent '{}' for campaign '{}' from SCHEDULED to READY", intent.getId(), intent.getCampaignId());
        }

        return promotedIntents;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ActionIntent> claimDueIntents(String workerId, int limit) {
        Instant now = Instant.now();
        Pageable pageable = PageRequest.of(0, Math.min(limit, batchSize));

        promoteScheduledIntents();

        List<ActionIntent> readyIntents = actionIntentRepository.findDueClaimableIntents(
                ActionIntentStatus.READY, CampaignStatus.ACTION_PENDING, now, pageable);
        List<ActionIntent> claimedIntents = new ArrayList<>();

        for (ActionIntent intent : readyIntents) {
            String claimToken = UUID.randomUUID().toString();
            int updated = actionIntentRepository.claimIntentAtomic(
                    intent.getId(),
                    ActionIntentStatus.READY,
                    ActionIntentStatus.CLAIMED,
                    now,
                    claimToken,
                    workerId,
                    CampaignStatus.ACTION_PENDING
            );

            if (updated == 1) {
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
            intent.setStatus(ActionIntentStatus.READY);
            intent.setClaimedAt(null);
            intent.setClaimToken(null);
            intent.setWorkerId(null);
            actionIntentRepository.save(intent);
            releasedIntents.add(intent);

            auditService.logEvent(
                    intent.getMerchantId(),
                    intent.getCampaignId(),
                    "STATE_TRANSITION",
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
            intent.setStatus(ActionIntentStatus.EXPIRED);
            intent.setCompletedAt(now);
            actionIntentRepository.save(intent);
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
}

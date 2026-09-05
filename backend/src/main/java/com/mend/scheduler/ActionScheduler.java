package com.mend.scheduler;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.service.ActionExecutionService;
import com.mend.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class ActionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActionScheduler.class);

    private final ActionIntentRepository actionIntentRepository;
    private final ActionExecutionService actionExecutionService;
    private final AuditService auditService;
    private final int batchSize;
    private final Duration leaseDuration;
    private final String workerId;
    private final boolean enabled;
    private final boolean scheduledEnabled;

    @Autowired
    public ActionScheduler(
            ActionIntentRepository actionIntentRepository,
            ActionExecutionService actionExecutionService,
            AuditService auditService,
            @Value("${mend.scheduler.worker-id:}") String workerId,
            @Value("${mend.scheduler.batch-size:50}") int batchSize,
            @Value("${mend.scheduler.lease-duration-minutes:5}") int leaseDurationMinutes,
            @Value("${mend.scheduler.enabled:true}") boolean enabled,
            @Value("${mend.scheduler.scheduled-enabled:true}") boolean scheduledEnabled) {
        this.actionIntentRepository = actionIntentRepository;
        this.actionExecutionService = actionExecutionService;
        this.auditService = auditService;
        this.batchSize = batchSize;
        this.leaseDuration = Duration.ofMinutes(leaseDurationMinutes);
        this.enabled = enabled;
        this.scheduledEnabled = scheduledEnabled;
        this.workerId = (workerId != null && !workerId.isBlank())
                ? workerId
                : "worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public ActionScheduler(
            ActionIntentRepository actionIntentRepository,
            ActionExecutionService actionExecutionService,
            AuditService auditService,
            String workerId,
            int batchSize,
            int leaseDurationMinutes,
            boolean enabled) {
        this(actionIntentRepository, actionExecutionService, auditService, workerId,
            batchSize, leaseDurationMinutes, enabled, true);
        }

        public ActionScheduler(
            ActionIntentRepository actionIntentRepository,
            ActionExecutionService actionExecutionService,
            AuditService auditService,
            int batchSize,
            int leaseDurationMinutes) {
        this(actionIntentRepository, actionExecutionService, auditService, null, batchSize, leaseDurationMinutes, true, true);
    }

    public ActionScheduler(
            ActionIntentRepository actionIntentRepository,
            AuditService auditService,
            int batchSize,
            int leaseDurationMinutes) {
        this(actionIntentRepository, null, auditService, null, batchSize, leaseDurationMinutes, true, true);
    }

    @Scheduled(fixedDelayString = "${mend.scheduler.poll-interval-ms:5000}")
    public List<PaymentExecutionResult> scheduledPollAndExecuteDueActions() {
        if (!scheduledEnabled) {
            return Collections.emptyList();
        }
        return pollAndExecuteDueActions();
    }

    @Transactional
    public List<PaymentExecutionResult> pollAndExecuteDueActions() {
        if (!enabled) {
            return Collections.emptyList();
        }
        log.debug("ActionScheduler poll cycle starting (workerId: {})...", workerId);

        // 1. Release expired claims
        releaseExpiredClaims();

        // 2. Process expired intents
        processExpiredIntents();

        // 3. Claim due intents
        List<ActionIntent> claimedIntents = claimDueIntents(workerId, batchSize);
        if (claimedIntents.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. Execute claimed intents via ActionExecutionService
        List<PaymentExecutionResult> results = new ArrayList<>();
        for (ActionIntent intent : claimedIntents) {
            if (actionExecutionService == null) {
                log.warn("ActionExecutionService is null in ActionScheduler; skipping execution of claimed intent '{}'", intent.getId());
                continue;
            }
            try {
                PaymentExecutionResult result = actionExecutionService.executeActionIntent(intent.getId(), workerId);
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception e) {
                log.error("Unhandled exception executing ActionIntent '{}' in scheduler loop: {}", intent.getId(), e.getMessage(), e);
            }
        }

        return results;
    }

    public String getWorkerId() {
        return workerId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional
    public List<ActionIntent> promoteScheduledIntents() {
        Instant now = Instant.now();
        Pageable pageable = PageRequest.of(0, batchSize);
        List<ActionIntent> scheduledIntents = actionIntentRepository.findDueIntents(ActionIntentStatus.SCHEDULED, now, pageable);

        List<ActionIntent> promotedIntents = new ArrayList<>();
        for (ActionIntent intent : scheduledIntents) {
            intent.setStatus(ActionIntentStatus.READY);
            actionIntentRepository.save(intent);
            promotedIntents.add(intent);

            log.info("Promoted Action Intent '{}' for campaign '{}' from SCHEDULED to READY", intent.getId(), intent.getCampaignId());
        }

        return promotedIntents;
    }

    @Transactional
    public List<ActionIntent> claimDueIntents(String workerId, int limit) {
        Instant now = Instant.now();
        Pageable pageable = PageRequest.of(0, Math.min(limit, batchSize));

        // 1. First promote any due SCHEDULED intents to READY
        promoteScheduledIntents();

        // 2. Fetch READY intents
        List<ActionIntent> readyIntents = actionIntentRepository.findDueIntents(ActionIntentStatus.READY, now, pageable);
        List<ActionIntent> claimedIntents = new ArrayList<>();

        for (ActionIntent intent : readyIntents) {
            String claimToken = UUID.randomUUID().toString();
            int updated = actionIntentRepository.claimIntentAtomic(
                    intent.getId(),
                    ActionIntentStatus.READY,
                    ActionIntentStatus.CLAIMED,
                    now,
                    claimToken,
                    workerId
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

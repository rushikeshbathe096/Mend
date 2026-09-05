package com.mend.scheduler;

import com.mend.domain.entity.ActionIntent;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.service.ActionExecutionService;
import com.mend.service.ActionIntentClaimService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class ActionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActionScheduler.class);

    private final ActionIntentClaimService actionIntentClaimService;
    private final ActionExecutionService actionExecutionService;
    private final int batchSize;
    private final String workerId;
    private final boolean enabled;
    private final boolean scheduledEnabled;

    @Autowired
    public ActionScheduler(
            ActionIntentClaimService actionIntentClaimService,
            ActionExecutionService actionExecutionService,
            @Value("${mend.scheduler.worker-id:}") String workerId,
            @Value("${mend.scheduler.batch-size:50}") int batchSize,
            @Value("${mend.scheduler.enabled:true}") boolean enabled,
            @Value("${mend.scheduler.scheduled-enabled:true}") boolean scheduledEnabled) {
        this.actionIntentClaimService = actionIntentClaimService;
        this.actionExecutionService = actionExecutionService;
        this.batchSize = batchSize;
        this.enabled = enabled;
        this.scheduledEnabled = scheduledEnabled;
        this.workerId = (workerId != null && !workerId.isBlank())
                ? workerId
                : "worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${mend.scheduler.poll-interval-ms:5000}")
    public List<PaymentExecutionResult> scheduledPollAndExecuteDueActions() {
        if (!scheduledEnabled) {
            return Collections.emptyList();
        }
        return pollAndExecuteDueActions();
    }

    public List<PaymentExecutionResult> pollAndExecuteDueActions() {
        if (!enabled) {
            return Collections.emptyList();
        }
        log.debug("ActionScheduler poll cycle starting (workerId: {})...", workerId);

        // 1. Release expired claims
        actionIntentClaimService.releaseExpiredClaims();

        // 2. Process expired intents
        actionIntentClaimService.processExpiredIntents();

        // 3. Claim due intents (transactional claim operation)
        List<ActionIntent> claimedIntents = actionIntentClaimService.claimDueIntents(workerId, batchSize);
        if (claimedIntents.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. Execute claimed intents via ActionExecutionService (OUTSIDE claim transaction)
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
}

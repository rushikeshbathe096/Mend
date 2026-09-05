package com.mend.service;

import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.enums.OutboxEventStatus;
import com.mend.domain.repository.OutboxEventRepository;
import com.mend.publisher.OutboxEventTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@ConditionalOnProperty(name = "mend.redis.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);
    private static final List<OutboxEventStatus> PUBLISHABLE_STATUSES =
            List.of(OutboxEventStatus.PENDING, OutboxEventStatus.FAILED);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventTransport outboxEventTransport;
    private final OutboxEventStateService stateService;
    private final int batchSize;
    private final long retryBaseDelaySeconds;
    private final long retryMaxDelaySeconds;
    private final boolean scheduledEnabled;

    public OutboxPublisherService(
            OutboxEventRepository outboxEventRepository,
            OutboxEventTransport outboxEventTransport,
            OutboxEventStateService stateService,
            @Value("${mend.outbox.publisher.batch-size:50}") int batchSize,
            @Value("${mend.outbox.publisher.retry-base-delay-seconds:1}") long retryBaseDelaySeconds,
            @Value("${mend.outbox.publisher.retry-max-delay-seconds:300}") long retryMaxDelaySeconds,
            @Value("${mend.outbox.publisher.scheduled-enabled:true}") boolean scheduledEnabled) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventTransport = outboxEventTransport;
        this.stateService = stateService;
        this.batchSize = Math.max(1, batchSize);
        this.retryBaseDelaySeconds = Math.max(1, retryBaseDelaySeconds);
        this.retryMaxDelaySeconds = Math.max(this.retryBaseDelaySeconds, retryMaxDelaySeconds);
        this.scheduledEnabled = scheduledEnabled;
    }

    @Scheduled(fixedDelayString = "${mend.outbox.publisher.poll-interval-ms:1000}")
    public void scheduledPublish() {
        if (scheduledEnabled) {
            publishDueEvents();
        }
    }

    /**
     * No transaction is opened around this method: selecting work, Redis I/O,
     * and recording the outcome are separate operations. A process crash after
     * Redis accepts a record leaves the row publishable, intentionally causing
     * safe at-least-once redelivery.
     */
    public int publishDueEvents() {
        List<OutboxEvent> due = outboxEventRepository.findDueForPublication(
                PUBLISHABLE_STATUSES, Instant.now(), PageRequest.of(0, batchSize));
        int published = 0;

        for (OutboxEvent event : due) {
            if (publishOne(event)) {
                published++;
            }
        }
        return published;
    }

    boolean publishOne(OutboxEvent event) {
        try {
            outboxEventTransport.publishOutbox(event);
            stateService.markPublished(event.getId(), Instant.now());
            return true;
        } catch (Exception publishFailure) {
            Instant failedAt = Instant.now();
            Instant nextAttemptAt = failedAt.plus(
                    retryDelaySeconds(event.getAttempts() + 1), ChronoUnit.SECONDS);
            try {
                stateService.markFailed(event.getId(), publishFailure.getMessage(), failedAt, nextAttemptAt);
            } catch (Exception stateFailure) {
                log.error("Could not record failure for outbox event '{}': {}",
                        event.getId(), stateFailure.getMessage(), stateFailure);
            }
            log.warn("Outbox publication failed for event '{}' (attempt {}). Next attempt at {}: {}",
                    event.getId(), event.getAttempts() + 1, nextAttemptAt, publishFailure.getMessage());
            return false;
        }
    }

    private long retryDelaySeconds(int attempt) {
        int exponent = Math.min(Math.max(0, attempt - 1), 20);
        long multiplier = 1L << exponent;
        if (retryBaseDelaySeconds > retryMaxDelaySeconds / multiplier) {
            return retryMaxDelaySeconds;
        }
        return Math.min(retryMaxDelaySeconds, retryBaseDelaySeconds * multiplier);
    }
}

package com.mend.reply;

import com.mend.domain.entity.CustomerReply;
import com.mend.domain.entity.ReplySuggestion;
import com.mend.domain.repository.CustomerReplyRepository;
import com.mend.domain.repository.ReplySuggestionRepository;
import com.mend.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Asynchronous customer-reply parsing (ADR-003 §5). Off the payment path: nothing on the payment flow waits for it.
 *
 * <p>Per batch: (1) lock due PENDING replies ({@code SKIP LOCKED}), count the attempt and lease each row, commit;
 * (2) call ai-service outside any transaction; (3) store the result in a transaction fenced by the claimed attempt
 * number: a valid or invalid suggestion (exactly one per reply) and the policy's effect, or a retry with exponential
 * backoff, or FAILED once the attempt cap is reached. No model (503 model_unavailable) means no suggestion, ever.
 */
@Component
public class ReplyParsingJob {

    private static final Logger log = LoggerFactory.getLogger(ReplyParsingJob.class);

    private final CustomerReplyRepository replies;
    private final ReplySuggestionRepository suggestions;
    private final ReplyParserClient client;
    private final ReplyPolicy policy;
    private final AuditService auditService;
    private final TransactionTemplate tx;
    private final boolean scheduledEnabled;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration backoffBase;
    private final Duration backoffCap;
    private final Duration lease;

    public ReplyParsingJob(CustomerReplyRepository replies, ReplySuggestionRepository suggestions, ReplyParserClient client,
                           ReplyPolicy policy, AuditService auditService, PlatformTransactionManager transactionManager,
                           @Value("${mend.replies.scheduled-enabled:true}") boolean scheduledEnabled,
                           @Value("${mend.replies.batch-size:20}") int batchSize,
                           @Value("${mend.replies.max-attempts:5}") int maxAttempts,
                           @Value("${mend.replies.backoff-base-seconds:30}") long backoffBaseSeconds,
                           @Value("${mend.replies.backoff-cap-seconds:3600}") long backoffCapSeconds,
                           @Value("${mend.replies.lease-seconds:120}") long leaseSeconds) {
        this.replies = replies;
        this.suggestions = suggestions;
        this.client = client;
        this.policy = policy;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
        this.scheduledEnabled = scheduledEnabled;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.backoffBase = Duration.ofSeconds(backoffBaseSeconds);
        this.backoffCap = Duration.ofSeconds(backoffCapSeconds);
        this.lease = Duration.ofSeconds(leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${mend.replies.poll-interval-ms:10000}")
    public void scheduledRun() {
        if (scheduledEnabled) {
            runOnce();
        }
    }

    /**
     * Up to one batch; returns how many replies were attempted. Rows are claimed one at a time (U2.4 review), so a lease
     * only has to cover one model call, never a whole batch.
     */
    public int runOnce() {
        int attempted = 0;
        while (attempted < batchSize) {
            List<Claimed> claimed = tx.execute(status -> claimDue(Instant.now()));
            if (claimed == null || claimed.isEmpty()) {
                break;
            }
            Claimed c = claimed.get(0);
            attempted++;
            ReplyParseOutcome outcome;
            try {
                outcome = client.parse(c.text());
            } catch (RuntimeException e) {
                outcome = new ReplyParseOutcome.Failed("AI_CLIENT_ERROR");
            }
            ReplyParseOutcome result = outcome;
            try {
                tx.executeWithoutResult(status -> record(c, result));
            } catch (RuntimeException e) {
                // Nothing was half-written (rolled back). The attempt still counts: retry with backoff, FAILED at the cap,
                // so a reply that cannot be recorded is not re-sent to the model forever (U2.4 review).
                log.error("event=reply_record_failed reply={} error={}", c.id(), e.getClass().getSimpleName());
                tx.executeWithoutResult(status -> retryOrFail(c, "RECORD_FAILED", Instant.now()));
            }
        }
        return attempted;
    }

    private List<Claimed> claimDue(Instant now) {
        List<Claimed> claimed = new ArrayList<>();
        for (UUID id : replies.lockDue(now, 1)) {
            if (replies.claim(id, now.plus(lease), now) == 1) {
                CustomerReply reply = replies.findById(id).orElseThrow();
                claimed.add(new Claimed(id, reply.getAttempts(), reply.getText()));
            }
        }
        return claimed;
    }

    private void record(Claimed c, ReplyParseOutcome outcome) {
        Instant now = Instant.now();
        switch (outcome) {
            case ReplyParseOutcome.Parsed parsed -> recordParsed(c, parsed, now);
            case ReplyParseOutcome.ModelUnavailable unavailable -> retryOrFail(c, "MODEL_UNAVAILABLE", now);
            case ReplyParseOutcome.Failed failed -> retryOrFail(c, failed.code(), now);
        }
    }

    private void recordParsed(Claimed c, ReplyParseOutcome.Parsed parsed, Instant now) {
        if (replies.markParsed(c.id(), c.attempts(), now) != 1) {
            log.info("event=reply_result_stale reply={} attempt={}", c.id(), c.attempts());
            return;
        }
        CustomerReply reply = replies.findById(c.id()).orElseThrow();
        // ai-service values are untrusted (invariant 8): an over-long id is unknown (NULL), never truncated into a value
        String modelId = bounded(parsed.modelId(), 100);
        String promptVersion = bounded(parsed.promptVersion(), 50);
        ReplySuggestion suggestion = switch (parsed.suggestion() == null
                ? new ReplySuggestionValidator.Invalid(outputErrorCode(parsed.outputError()))
                : ReplySuggestionValidator.validate(parsed.suggestion())) {
            case ReplySuggestionValidator.Valid v -> ReplySuggestion.valid(reply.getId(), reply.getMerchantId(), v.intent(),
                    v.promisedDate(), v.abstain(), modelId, promptVersion);
            case ReplySuggestionValidator.Invalid i -> ReplySuggestion.invalid(reply.getId(), reply.getMerchantId(), i.error(),
                    modelId, promptVersion);
        };
        suggestions.saveAndFlush(suggestion);
        auditService.logEvent(reply.getMerchantId(), reply.getCampaignId(), "REPLY_PARSED", "SYSTEM", null,
                "reply=" + reply.getId() + " suggestion=" + suggestion.getId() + " valid=" + suggestion.isValid()
                        + (suggestion.isValid() ? " intent=" + suggestion.getIntent() : " error=" + suggestion.getValidationError()));
        suggestion.setEffect(policy.apply(reply, suggestion));
        suggestions.saveAndFlush(suggestion);
    }

    private void retryOrFail(Claimed c, String code, Instant now) {
        CustomerReply reply = replies.findById(c.id()).orElse(null);
        if (reply == null) {
            return;
        }
        if (c.attempts() >= maxAttempts) {
            if (replies.markFailed(c.id(), c.attempts(), code, now) == 1) {
                auditService.logEvent(reply.getMerchantId(), reply.getCampaignId(), "REPLY_PARSE_FAILED", "SYSTEM", null,
                        "reply=" + c.id() + " attempts=" + c.attempts() + " code=" + code);
            }
            return;
        }
        replies.scheduleRetry(c.id(), c.attempts(), now.plus(backoff(c.attempts())), code, now);
    }

    static String bounded(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength ? value : null;
    }

    /** ai-service's reason for "no suggestion", mapped to a closed set (never copied). */
    static String outputErrorCode(String outputError) {
        return switch (outputError == null ? "" : outputError) {
            case "not_json" -> "MODEL_OUTPUT_NOT_JSON";
            case "not_object" -> "MODEL_OUTPUT_NOT_OBJECT";
            case "no_text" -> "MODEL_OUTPUT_NO_TEXT";
            default -> "MODEL_OUTPUT_MISSING";
        };
    }

    /** base·2^(attempt-1), capped. */
    Duration backoff(int attempt) {
        Duration delay = backoffBase.multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 20));
        return delay.compareTo(backoffCap) > 0 ? backoffCap : delay;
    }

    private record Claimed(UUID id, int attempts, String text) {
    }
}

package com.mend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.CampaignAttempt;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryAttribution;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignAttemptRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.provider.PaymentEvidenceStatus;
import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.PaymentLinkState;
import com.mend.provider.PaymentStatusInterpreter;
import com.mend.provider.ProviderAttemptKey;
import com.mend.statemachine.CampaignStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DefaultPaymentReconciliationService implements PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPaymentReconciliationService.class);

    private final ActionIntentRepository actionIntentRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignAttemptRepository campaignAttemptRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final CampaignLifecycleService campaignLifecycleService;
    private final CampaignStateMachine campaignStateMachine;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ActionIntentTransitions transitions;
    private final HumanApprovalService humanApprovalService;

    /** Evidence may move an intent only out of these statuses (M2 §4). */
    private static final java.util.Set<ActionIntentStatus> EVIDENCE_FROM = java.util.EnumSet.of(
            ActionIntentStatus.EXECUTING, ActionIntentStatus.AWAITING_PAYMENT, ActionIntentStatus.NEEDS_RECONCILIATION);
    private static final java.util.Set<ActionIntentStatus> FAILURE_FROM = java.util.EnumSet.of(
            ActionIntentStatus.EXECUTING, ActionIntentStatus.NEEDS_RECONCILIATION);
    /** Single interpreter of provider status strings (M1 U5): only captured is money. */
    private final PaymentStatusInterpreter evidenceMapper;

    public DefaultPaymentReconciliationService(
            ActionIntentRepository actionIntentRepository,
            CampaignRepository campaignRepository,
            CampaignAttemptRepository campaignAttemptRepository,
            WebhookEventRepository webhookEventRepository,
            CampaignLifecycleService campaignLifecycleService,
            CampaignStateMachine campaignStateMachine,
            AuditService auditService,
            ObjectMapper objectMapper,
            PaymentStatusInterpreter evidenceMapper,
            ActionIntentTransitions transitions,
            HumanApprovalService humanApprovalService) {
        this.actionIntentRepository = actionIntentRepository;
        this.campaignRepository = campaignRepository;
        this.campaignAttemptRepository = campaignAttemptRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.campaignLifecycleService = campaignLifecycleService;
        this.campaignStateMachine = campaignStateMachine;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.evidenceMapper = evidenceMapper;
        this.transitions = transitions;
        this.humanApprovalService = humanApprovalService;
    }

    /**
     * M2 U8 (ADR-002 decision 9, invariant 15): what a webhook correlates to. Only {@link Intent} may finalize an intent;
     * a {@link CampaignLevel} match (the original payment, or the subscription) never does.
     */
    private sealed interface Correlation {
        record Intent(ActionIntent intent) implements Correlation {}
        /** More than one intent carries the same provider reference: impossible after U6, so surfaced, never guessed. */
        record Ambiguous(String reference, List<ActionIntent> intents) implements Correlation {}
        record CampaignLevel(List<Campaign> campaigns, CampaignMatch matchedBy) implements Correlation {}
        record None() implements Correlation {}
    }

    private enum CampaignMatch { PROVIDER_PAYMENT_ID, PAYMENT_ID, SUBSCRIPTION_ID }

    @Override
    @Transactional
    public ReconciliationResult reconcileWebhookEvent(WebhookEvent event) {
        if (event == null) {
            return ReconciliationResult.unmatched("WebhookEvent is null");
        }

        // Parse payload details safely
        PayloadDetails payloadDetails = parsePayloadDetails(event);

        return switch (correlate(event, payloadDetails)) {
            case Correlation.Intent(ActionIntent intent) -> reconcileIntent(event, payloadDetails, intent);
            case Correlation.Ambiguous(String reference, List<ActionIntent> intents) -> ambiguousReference(event, reference, intents);
            case Correlation.CampaignLevel(List<Campaign> campaigns, CampaignMatch matchedBy) ->
                    reconcileCampaignLevel(event, payloadDetails, campaigns, matchedBy);
            case Correlation.None() -> {
                log.debug("No matching ActionIntent found for webhook event [eventId={}, externalEventId={}]",
                        event.getId(), event.getExternalEventId());
                yield ReconciliationResult.unmatched("No matching ActionIntent found");
            }
        };
    }

    /** Intent-specific evidence (notes, idempotency key, provider reference): the only path that finalizes an intent. */
    private ReconciliationResult reconcileIntent(WebhookEvent event, PayloadDetails payloadDetails, ActionIntent intent) {
        // Validate Tenant Isolation (Strict Tenant Ownership Check)
        if (event.getMerchantId() != null && !event.getMerchantId().equals(intent.getMerchantId())) {
            log.warn("Tenant mismatch during reconciliation! Event merchantId='{}', Intent merchantId='{}', ActionIntent='{}'",
                    event.getMerchantId(), intent.getMerchantId(), intent.getId());
            return ReconciliationResult.tenantMismatch("Webhook merchantId (" + event.getMerchantId() +
                    ") does not match ActionIntent merchantId (" + intent.getMerchantId() + ")");
        }

        Campaign campaign = campaignRepository.findById(intent.getCampaignId()).orElse(null);
        if (campaign == null) {
            log.warn("Campaign '{}' referenced by ActionIntent '{}' not found", intent.getCampaignId(), intent.getId());
            return ReconciliationResult.unmatched("Referenced Campaign not found");
        }

        if (event.getMerchantId() != null && !event.getMerchantId().equals(campaign.getMerchantId())) {
            log.warn("Tenant mismatch! Event merchantId='{}', Campaign merchantId='{}'",
                    event.getMerchantId(), campaign.getMerchantId());
            return ReconciliationResult.tenantMismatch("Webhook merchantId does not match Campaign merchantId");
        }

        // Determine if the payload evidences SUCCESS (captured) or FAILURE (failed); anything else changes nothing
        Boolean isSuccessEvent = evidencedOutcome(payloadDetails);
        if (isSuccessEvent == null) {
            log.info("Webhook event '{}' carries no captured/failed payment evidence; no state change [eventType={}]",
                    event.getId(), event.getEventType());
            return ReconciliationResult.unsupported("Event type '" + event.getEventType() + "' carries no captured or failed payment evidence");
        }

        // Check if Campaign or Intent is already terminal/finalized (Late Webhook Protection)
        if (campaignStateMachine.isTerminal(campaign.getCurrentState())) {
            if (Boolean.TRUE.equals(isSuccessEvent) && currentStatus(intent) != ActionIntentStatus.SUCCEEDED) {
                // Real money on an intent that did not succeed, on a campaign that is over (e.g. the link was paid after the
                // original payment was captured late, U8): never swallowed — audit and human review (same as the reconciler).
                String paymentRef = payloadDetails.paymentId() != null ? payloadDetails.paymentId() : event.getExternalEventId();
                log.warn("event=captured_on_terminal_campaign intent_id={} campaign_id={} campaign_state={} webhook_event_id={}",
                        intent.getId(), campaign.getId(), campaign.getCurrentState(), event.getId());
                auditService.logEvent(intent.getMerchantId(), campaign.getId(), "CAPTURED_ON_TERMINAL_CAMPAIGN", "SYSTEM", null,
                        "intent=" + intent.getId() + " payment=" + paymentRef + " campaign_state=" + campaign.getCurrentState()
                                + " webhook_event=" + event.getId());
                humanApprovalService.createReview(intent.getMerchantId(), campaign.getId(), "CAPTURED_ON_TERMINAL_CAMPAIGN: intent "
                        + intent.getId() + " payment " + paymentRef + " captured while campaign is " + campaign.getCurrentState()
                        + "; possible double payment, human decision required");
            }
            log.info("Late webhook received for campaign '{}' which is already in terminal state '{}'. Ignoring reconciliation.",
                    campaign.getId(), campaign.getCurrentState());
            return ReconciliationResult.alreadyFinalized(intent.getId(), campaign.getId(),
                    "Campaign " + campaign.getId() + " is already in terminal state " + campaign.getCurrentState());
        }

        // Idempotency check: if ActionIntent is already SUCCEEDED and incoming is SUCCESS
        if (intent.getStatus() == ActionIntentStatus.SUCCEEDED && Boolean.TRUE.equals(isSuccessEvent)) {
            log.info("ActionIntent '{}' already SUCCEEDED. Idempotently ignoring duplicate success webhook event [eventId={}]",
                    intent.getId(), event.getId());
            return ReconciliationResult.alreadyFinalized(intent.getId(), campaign.getId(),
                    "ActionIntent " + intent.getId() + " is already SUCCEEDED");
        }

        // Owner decision D1 (M1): a payment link accepts several attempts, so one failed attempt does not end it.
        // For an intent that issued a link, a FAILED payment changes nothing; the intent stays EXECUTING.
        if (Boolean.FALSE.equals(isSuccessEvent) && PaymentLinkSnapshot.isLinkId(intent.getResponseReference())) {
            log.info("event=link_attempt_failed intent_id={} campaign_id={} webhook_event_id={}",
                    intent.getId(), campaign.getId(), event.getId());
            return ReconciliationResult.unsupported("Failed attempt on payment link " + intent.getResponseReference()
                    + "; link remains payable");
        }

        String providerRef = payloadDetails.paymentId() != null ? payloadDetails.paymentId() : event.getExternalEventId();
        Instant now = Instant.now();

        if (Boolean.TRUE.equals(isSuccessEvent)) {
            // Reconcile to SUCCESS — fenced: only an in-flight intent (EXECUTING / AWAITING_PAYMENT / NEEDS_RECONCILIATION).
            if (!transitions.recordEvidence(intent.getId(), EVIDENCE_FROM, ActionIntentStatus.SUCCEEDED, providerRef, now)) {
                ActionIntentStatus current = currentStatus(intent);
                if (current != ActionIntentStatus.SUCCEEDED) {
                    // Captured money for an intent that is not in flight (e.g. already FAILED). §4 keeps the intent where it
                    // is; the evidence is surfaced (WARN + audit) instead of being dropped silently. Owner decision pending.
                    log.warn("event=captured_evidence_not_applied intent_id={} status_at_read={} status_now={} webhook_event_id={}",
                            intent.getId(), intent.getStatus(), current, event.getId());
                    auditService.logEvent(intent.getMerchantId(), intent.getCampaignId(), "CAPTURED_EVIDENCE_NOT_APPLIED", "SYSTEM", null,
                            "intent=" + intent.getId() + " status=" + current + " webhook_event=" + event.getId());
                } else {
                    log.info("event=reconciliation_not_applied intent_id={} status_now={} outcome=SUCCESS", intent.getId(), current);
                }
                return ReconciliationResult.alreadyFinalized(intent.getId(), campaign.getId(),
                        "ActionIntent " + intent.getId() + " is not in flight (" + current + "); evidence not applied");
            }

            // Create / update CampaignAttempt
            CampaignAttempt attempt = campaignAttemptRepository
                    .findByCampaignIdAndAttemptNumber(campaign.getId(), intent.getAttemptNumber())
                    .orElseGet(() -> new CampaignAttempt(UUID.randomUUID(), campaign.getId(), intent.getAttemptNumber()));

            attempt.setActionType(intent.getActionType());
            attempt.setStatus("SUCCESS");
            attempt.setExternalReference(providerRef);
            attempt.setCompletedAt(now);
            attempt.setFailureReason(null);
            campaignAttemptRepository.save(attempt);

            // Update Campaign state machine
            transitionCampaignToSuccess(campaign, intent, providerRef);

            auditService.logEvent(
                    intent.getMerchantId(), intent.getCampaignId(),
                    "RECONCILED_SUCCESS", "SYSTEM", null,
                    "ActionIntent " + intent.getId() + " reconciled to SUCCESS via webhook event " + event.getId()
            );

            // Mark WebhookEvent PROCESSED
            event.setProcessingStatus(WebhookEventStatus.PROCESSED);
            event.setProcessedAt(now);
            webhookEventRepository.save(event);

            log.info("Successfully reconciled ActionIntent '{}' and Campaign '{}' to SUCCESS via eventId='{}'",
                    intent.getId(), campaign.getId(), event.getId());

            return ReconciliationResult.success(intent.getId(), campaign.getId(), providerRef,
                    "Reconciled execution attempt to SUCCESS");

        } else {
            // Reconcile to FAILURE — fenced: only an in-flight intent.
            // AWAITING_PAYMENT is excluded: it always means a live payment link, which a failed attempt never ends (D1).
            // This also covers a race where the intent was read before the link reference was written.
            if (!transitions.recordEvidence(intent.getId(), FAILURE_FROM, ActionIntentStatus.FAILED, null, now)) {
                ActionIntentStatus current = currentStatus(intent);
                log.info("event=reconciliation_not_applied intent_id={} status_now={} outcome=FAILURE", intent.getId(), current);
                return ReconciliationResult.alreadyFinalized(intent.getId(), campaign.getId(),
                        "ActionIntent " + intent.getId() + " is not open to failure evidence (" + current + "); evidence not applied");
            }

            CampaignAttempt attempt = campaignAttemptRepository
                    .findByCampaignIdAndAttemptNumber(campaign.getId(), intent.getAttemptNumber())
                    .orElseGet(() -> new CampaignAttempt(UUID.randomUUID(), campaign.getId(), intent.getAttemptNumber()));

            attempt.setActionType(intent.getActionType());
            attempt.setStatus("FAILED");
            attempt.setFailureReason(payloadDetails.failureReason() != null ? payloadDetails.failureReason() : "Payment failed via webhook confirmation");
            attempt.setCompletedAt(now);
            campaignAttemptRepository.save(attempt);

            transitionCampaignToFailure(campaign, intent, payloadDetails.failureReason());

            auditService.logEvent(
                    intent.getMerchantId(), intent.getCampaignId(),
                    "RECONCILED_FAILURE", "SYSTEM", null,
                    "ActionIntent " + intent.getId() + " reconciled to FAILURE via webhook event " + event.getId()
            );

            event.setProcessingStatus(WebhookEventStatus.PROCESSED);
            event.setProcessedAt(now);
            webhookEventRepository.save(event);

            log.info("Successfully reconciled ActionIntent '{}' and Campaign '{}' to FAILURE via eventId='{}'",
                    intent.getId(), campaign.getId(), event.getId());

            return ReconciliationResult.failure(intent.getId(), campaign.getId(), providerRef,
                    "Reconciled execution attempt to FAILURE");
        }
    }

    private Correlation correlate(WebhookEvent event, PayloadDetails payload) {
        // 1. Mend idempotency key
        if (payload.idempotencyKey() != null && !payload.idempotencyKey().isBlank()) {
            Optional<ActionIntent> byKey = actionIntentRepository.findByIdempotencyKey(payload.idempotencyKey());
            if (byKey.isPresent()) {
                log.info("Correlated ActionIntent '{}' by idempotencyKey '{}'", byKey.get().getId(), payload.idempotencyKey());
                return new Correlation.Intent(byKey.get());
            }
        }

        // 2. Intent id in payload notes (mend_intent_id, legacy action_intent_id)
        if (payload.actionIntentId() != null) {
            try {
                UUID intentId = UUID.fromString(payload.actionIntentId());
                Optional<ActionIntent> byId = actionIntentRepository.findById(intentId);
                if (byId.isPresent()) {
                    log.info("Correlated ActionIntent '{}' by payload actionIntentId", intentId);
                    return new Correlation.Intent(byId.get());
                }
            } catch (IllegalArgumentException ignored) {
                // untrusted payload: not a UUID, so it identifies nothing
            }
        }

        // 3. Provider reference the intent recorded (payment id, then payment link id)
        for (String reference : new String[] {payload.paymentId(), payload.paymentLinkId()}) {
            if (reference == null || reference.isBlank()) {
                continue;
            }
            List<ActionIntent> byRef = actionIntentRepository.findAllByResponseReference(reference);
            if (byRef.size() > 1) {
                return new Correlation.Ambiguous(reference, byRef);
            }
            if (byRef.size() == 1) {
                log.info("Correlated ActionIntent '{}' by provider reference '{}'", byRef.get(0).getId(), reference);
                return new Correlation.Intent(byRef.get(0));
            }
        }

        // Campaign level (old strategies 4 and 5): never an intent.
        if (event.getMerchantId() == null) {
            return new Correlation.None();
        }
        if (payload.paymentId() != null && !payload.paymentId().isBlank()) {
            List<Campaign> byProviderPayment = campaignRepository.findAllByMerchantIdAndProviderPaymentId(event.getMerchantId(), payload.paymentId());
            if (!byProviderPayment.isEmpty()) {
                return new Correlation.CampaignLevel(byProviderPayment, CampaignMatch.PROVIDER_PAYMENT_ID);
            }
        }
        String paymentIdToMatch = payload.paymentId() != null ? payload.paymentId() : event.getExternalEventId();
        if (paymentIdToMatch != null && !paymentIdToMatch.isBlank()) {
            Optional<Campaign> byPaymentId = campaignRepository.findByMerchantIdAndPaymentId(event.getMerchantId(), paymentIdToMatch);
            if (byPaymentId.isPresent()) {
                return new Correlation.CampaignLevel(List.of(byPaymentId.get()), CampaignMatch.PAYMENT_ID);
            }
        }
        if (payload.subscriptionId() != null && !payload.subscriptionId().isBlank()) {
            List<Campaign> bySubscription = campaignRepository.findAllByMerchantIdAndSubscriptionId(event.getMerchantId(), payload.subscriptionId());
            if (!bySubscription.isEmpty()) {
                return new Correlation.CampaignLevel(bySubscription, CampaignMatch.SUBSCRIPTION_ID);
            }
        }
        return new Correlation.None();
    }

    /** >1 intent with one provider reference: change nothing, say so loudly, and hand it to a human (per campaign). */
    private ReconciliationResult ambiguousReference(WebhookEvent event, String reference, List<ActionIntent> intents) {
        log.error("event=ambiguous_provider_reference reference={} intent_count={} webhook_event_id={}",
                reference, intents.size(), event.getId());
        intents.stream()
                .filter(intent -> event.getMerchantId() == null || event.getMerchantId().equals(intent.getMerchantId()))
                .map(intent -> List.of(intent.getMerchantId(), intent.getCampaignId()))
                .distinct()
                .forEach(ids -> {
                    auditService.logEvent(ids.get(0), ids.get(1), "AMBIGUOUS_PROVIDER_REFERENCE", "SYSTEM", null,
                            "reference=" + reference + " intents=" + intents.size() + " webhook_event=" + event.getId());
                    humanApprovalService.createReview(ids.get(0), ids.get(1), "AMBIGUOUS_PROVIDER_REFERENCE: " + intents.size()
                            + " intents carry provider reference " + reference + "; no state changed; human decision required");
                });
        return ReconciliationResult.campaignLevel(null, "Provider reference " + reference + " matches " + intents.size() + " intents; not applied");
    }

    /**
     * The original payment (or the subscription) — not a MEND action. A failure changes nothing. A capture of the original
     * payment is real money that MEND did not recover: the campaign ends with attribution ORIGINAL_PAYMENT_LATE_CAPTURE and
     * open intents are cancelled; any link or debit that may already exist is flagged DUPLICATE_PAYMENT_RISK, because the
     * payment-link cancel endpoint is not verified and recorded (ADR-002), so MEND cannot cancel it at the provider.
     */
    private ReconciliationResult reconcileCampaignLevel(WebhookEvent event, PayloadDetails payload, List<Campaign> matched,
                                                        CampaignMatch matchedBy) {
        Boolean outcome = evidencedOutcome(payload);
        List<Campaign> campaigns = matched.stream()
                .filter(campaign -> event.getMerchantId().equals(campaign.getMerchantId()))
                .sorted(java.util.Comparator.comparing(Campaign::getId))   // one lock order for concurrent captures
                .toList();
        // A captured payment on the subscription may belong to another billing cycle: not evidence for this invoice.
        // The event that created the campaign is not a later capture of it.
        boolean lateCapture = Boolean.TRUE.equals(outcome) && matchedBy != CampaignMatch.SUBSCRIPTION_ID
                && payload.paymentId() != null && !campaigns.isEmpty()
                && campaigns.stream().noneMatch(campaign -> campaign.getPaymentId().equals(event.getExternalEventId()));
        if (!lateCapture) {
            log.info("event=original_payment_event webhook_event_id={} matched_by={} campaigns={} outcome={}",
                    event.getId(), matchedBy, campaigns.size(), outcome == null ? "NONE" : (outcome ? "CAPTURED" : "FAILED"));
            return ReconciliationResult.campaignLevel(campaigns.isEmpty() ? null : campaigns.get(0).getId(),
                    "Campaign-level event (" + matchedBy + "); recovery intents are not changed");
        }

        Instant now = Instant.now();
        UUID firstApplied = null;
        for (Campaign campaign : campaigns) {
            if (lateCapture(campaign.getId(), payload.paymentId(), event, now)) {
                firstApplied = firstApplied == null ? campaign.getId() : firstApplied;
            }
        }
        if (firstApplied == null) {
            return ReconciliationResult.alreadyFinalized(null, campaigns.get(0).getId(),
                    "Original payment " + payload.paymentId() + " captured; campaign already terminal");
        }
        event.setProcessingStatus(WebhookEventStatus.PROCESSED);
        event.setProcessedAt(now);
        webhookEventRepository.save(event);
        return ReconciliationResult.success(null, firstApplied, payload.paymentId(),
                "Original payment captured late; campaign closed with attribution ORIGINAL_PAYMENT_LATE_CAPTURE");
    }

    /**
     * One campaign, under row locks on its intents and then the campaign (no claim or dispatch can slip between read and
     * cancel). Intents before campaign: the same order as the worker finalize, the reconciler and the webhook intent path.
     */
    private boolean lateCapture(UUID campaignId, String paymentId, WebhookEvent event, Instant now) {
        List<ActionIntent> campaignIntents = actionIntentRepository.findByCampaignIdForUpdate(campaignId);
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId).orElseThrow();
        CampaignStatus state = campaign.getCurrentState();
        if (campaignStateMachine.isTerminal(state)) {
            log.info("event=original_payment_captured_on_terminal_campaign campaign_id={} state={} payment={}", campaignId, state, paymentId);
            auditService.logEvent(campaign.getMerchantId(), campaignId, "ORIGINAL_PAYMENT_CAPTURED_ON_TERMINAL_CAMPAIGN", "SYSTEM", null,
                    "payment=" + paymentId + " campaign_state=" + state + " webhook_event=" + event.getId());
            return false;
        }

        List<String> possiblyLive = new ArrayList<>();
        for (ActionIntent intent : campaignIntents) {
            ActionIntentStatus status = intent.getStatus();
            if (!transitions.cancelNonTerminal(intent.getId(), now)) {
                continue;   // already terminal
            }
            auditService.logEvent(intent.getMerchantId(), campaignId, "INTENT_CANCELLED_ORIGINAL_PAYMENT_CAPTURED", "SYSTEM", null,
                    "intent=" + intent.getId() + " " + status + "->CANCELLED payment=" + paymentId);
            // CLAIMED cannot dispatch any more (the dispatch fence needs CLAIMED); these may already exist at the provider.
            if (status == ActionIntentStatus.EXECUTING || status == ActionIntentStatus.NEEDS_RECONCILIATION
                    || status == ActionIntentStatus.AWAITING_PAYMENT) {
                possiblyLive.add(PaymentLinkSnapshot.isLinkId(intent.getResponseReference())
                        ? intent.getResponseReference()
                        : "attempt_key=" + ProviderAttemptKey.forIntent(intent.getId()).value() + " (" + intent.getActionType() + ", " + status + ")");
            }
        }

        CampaignStatus target = campaignStateMachine.isValidTransition(state, CampaignStatus.RECOVERED)
                ? CampaignStatus.RECOVERED
                : CampaignStatus.CANCELLED;   // before any action (e.g. ELIGIBLE): stop, never chase a paid invoice
        campaignLifecycleService.transitionState(campaign.getMerchantId(), campaignId, target,
                "ORIGINAL_PAYMENT_LATE_CAPTURE: original payment " + paymentId + " captured; not attributed to MEND", "SYSTEM", null);
        campaignRepository.setRecoveryAttribution(campaignId, RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE);
        auditService.logEvent(campaign.getMerchantId(), campaignId, "ORIGINAL_PAYMENT_LATE_CAPTURE", "SYSTEM", null,
                "payment=" + paymentId + " " + state + "->" + target + " webhook_event=" + event.getId());

        if (!possiblyLive.isEmpty()) {
            String items = String.join(", ", possiblyLive);
            log.warn("event=duplicate_payment_risk campaign_id={} items={}", campaignId, items);
            auditService.logEvent(campaign.getMerchantId(), campaignId, "DUPLICATE_PAYMENT_RISK", "SYSTEM", null,
                    "payment=" + paymentId + " live_or_unknown=" + items);
            humanApprovalService.createReview(campaign.getMerchantId(), campaignId, "DUPLICATE_PAYMENT_RISK: original payment "
                    + paymentId + " was captured while recovery action(s) may still be payable: " + items
                    + ". Cancel them at Razorpay (MEND cannot: cancel endpoint not verified).");
        }
        return true;
    }

    /**
     * TRUE only on captured evidence (for link events: link PAID and its payment CAPTURED); FALSE only on a FAILED
     * payment; null (no state change) for authorized, created, refunded, unrecognised or missing statuses.
     * The event type is not a success signal.
     */
    private Boolean evidencedOutcome(PayloadDetails payload) {
        PaymentEvidenceStatus paymentStatus = payload.status() == null ? null : evidenceMapper.mapPaymentStatus(payload.status());
        if (paymentStatus == PaymentEvidenceStatus.FAILED) {
            return false;
        }
        if (paymentStatus != PaymentEvidenceStatus.CAPTURED) {
            return null;
        }
        if (payload.paymentLinkPresent()) {
            PaymentLinkState linkState = evidenceMapper.mapLinkStatus(payload.paymentLinkStatus());
            return linkState == PaymentLinkState.PAID ? true : null;
        }
        return true;
    }

    private void transitionCampaignToSuccess(Campaign campaign, ActionIntent intent, String providerRef) {
        CampaignStatus current = campaign.getCurrentState();
        if (current == CampaignStatus.ACTION_PENDING) {
            campaignLifecycleService.transitionState(
                    campaign.getMerchantId(), campaign.getId(),
                    CampaignStatus.EXECUTING,
                    "Action execution started for reconciliation of ActionIntent " + intent.getId(),
                    "SYSTEM", null
            );
            current = CampaignStatus.EXECUTING;
        }

        if (current == CampaignStatus.EXECUTING || current == CampaignStatus.FAILED ||
            current == CampaignStatus.ELIGIBLE || current == CampaignStatus.SCHEDULED) {
            campaignLifecycleService.transitionState(
                    campaign.getMerchantId(), campaign.getId(),
                    CampaignStatus.RECOVERED,
                    "Payment recovery succeeded via webhook reconciliation (Ref: " + providerRef + ")",
                    "SYSTEM", null
            );
            campaignRepository.setRecoveryAttribution(campaign.getId(), RecoveryAttribution.MEND);
        }
    }

    private void transitionCampaignToFailure(Campaign campaign, ActionIntent intent, String failureReason) {
        CampaignStatus current = campaign.getCurrentState();
        if (current == CampaignStatus.ACTION_PENDING) {
            campaignLifecycleService.transitionState(
                    campaign.getMerchantId(), campaign.getId(),
                    CampaignStatus.EXECUTING,
                    "Action execution started for reconciliation of ActionIntent " + intent.getId(),
                    "SYSTEM", null
            );
            current = CampaignStatus.EXECUTING;
        }

        if (current == CampaignStatus.EXECUTING) {
            campaignLifecycleService.transitionState(
                    campaign.getMerchantId(), campaign.getId(),
                    CampaignStatus.FAILED,
                    "Action execution failed via webhook reconciliation: " + failureReason,
                    "SYSTEM", null
            );
            current = CampaignStatus.FAILED;
        }

        if (current == CampaignStatus.FAILED) {
            campaignLifecycleService.transitionState(
                    campaign.getMerchantId(), campaign.getId(),
                    CampaignStatus.EXHAUSTED,
                    "Campaign attempts exhausted following failed action execution reconciliation",
                    "SYSTEM", null
            );
        }
    }

    private PayloadDetails parsePayloadDetails(WebhookEvent event) {
        String paymentId = null;
        String subscriptionId = null;
        String idempotencyKey = null;
        String actionIntentId = null;
        String status = null;
        String failureReason = null;
        boolean paymentLinkPresent = false;
        String paymentLinkId = null;
        String paymentLinkStatus = null;
        String mendIntentId = null;

        if (event.getRawPayload() != null && !event.getRawPayload().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(event.getRawPayload());
                JsonNode paymentEntity = null;

                if (root.has("payload")) {
                    JsonNode payload = root.get("payload");
                    if (payload.has("payment") && payload.get("payment").has("entity")) {
                        paymentEntity = payload.get("payment").get("entity");
                    }
                    if (payload.has("payment_link") && payload.get("payment_link").has("entity")) {
                        JsonNode linkEntity = payload.get("payment_link").get("entity");
                        paymentLinkPresent = true;
                        if (linkEntity.has("id") && !linkEntity.get("id").isNull()) {
                            paymentLinkId = linkEntity.get("id").asText();
                        }
                        if (linkEntity.has("status") && !linkEntity.get("status").isNull()) {
                            paymentLinkStatus = linkEntity.get("status").asText();
                        }
                        if (linkEntity.has("notes") && linkEntity.get("notes").isObject()) {
                            mendIntentId = text(linkEntity.get("notes"), "mend_intent_id");
                        }
                    }
                    if (payload.has("subscription") && payload.get("subscription").has("entity")) {
                        JsonNode subEntity = payload.get("subscription").get("entity");
                        if (subEntity.has("id") && !subEntity.get("id").isNull()) {
                            subscriptionId = subEntity.get("id").asText();
                        }
                    }
                } else {
                    paymentEntity = root;
                }

                if (paymentEntity != null) {
                    if (paymentEntity.has("id") && !paymentEntity.get("id").isNull()) {
                        paymentId = paymentEntity.get("id").asText();
                    }
                    if (paymentEntity.has("status") && !paymentEntity.get("status").isNull()) {
                        status = paymentEntity.get("status").asText();
                    }
                    if (paymentEntity.has("subscription_id") && !paymentEntity.get("subscription_id").isNull()) {
                        subscriptionId = paymentEntity.get("subscription_id").asText();
                    }
                    if (paymentEntity.has("notes") && paymentEntity.get("notes").isObject()) {
                        JsonNode notes = paymentEntity.get("notes");
                        String keyFromNotes = text(notes, "idempotency_key");
                        if (keyFromNotes != null) idempotencyKey = keyFromNotes;
                        String paymentMendIntentId = text(notes, "mend_intent_id");
                        if (paymentMendIntentId != null) {
                            if (mendIntentId != null && !mendIntentId.equals(paymentMendIntentId)) {
                                log.warn("event=reconciliation_conflicting_intent_ids link_intent_id={} payment_intent_id={}",
                                        mendIntentId, paymentMendIntentId);
                            }
                            mendIntentId = paymentMendIntentId;
                        }
                        // Legacy key, kept for intents created before M1.
                        String legacyIntentId = text(notes, "action_intent_id");
                        if (legacyIntentId != null) actionIntentId = legacyIntentId;
                    }
                    // M1: `receipt` is no longer an idempotency-key fallback (payment links send no receipt; the
                    // legacy rcpt_<n> value could never match an intent).
                    if (paymentEntity.has("error_description") && !paymentEntity.get("error_description").isNull()) {
                        failureReason = paymentEntity.get("error_description").asText();
                    }
                }

                if (idempotencyKey == null && root.has("idempotency_key")) {
                    idempotencyKey = root.get("idempotency_key").asText();
                }

            } catch (Exception e) {
                log.debug("Could not parse rawPayload for reconciliation details: {}", e.getMessage());
            }
        }

        // notes.mend_intent_id (what M1 sends) takes precedence over the legacy notes.action_intent_id.
        String intentIdForCorrelation = mendIntentId != null ? mendIntentId : actionIntentId;
        return new PayloadDetails(paymentId, subscriptionId, idempotencyKey, intentIdForCorrelation, status, failureReason,
                paymentLinkPresent, paymentLinkId, paymentLinkStatus);
    }

    private ActionIntentStatus currentStatus(ActionIntent intent) {
        return actionIntentRepository.findById(intent.getId()).map(ActionIntent::getStatus).orElse(intent.getStatus());
    }

    /** Untrusted payload: only a non-blank string value counts; null, numbers or objects are ignored. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank() ? value.stringValue() : null;
    }

    private record PayloadDetails(
            String paymentId,
            String subscriptionId,
            String idempotencyKey,
            String actionIntentId,
            String status,
            String failureReason,
            boolean paymentLinkPresent,
            String paymentLinkId,
            String paymentLinkStatus
    ) {}
}

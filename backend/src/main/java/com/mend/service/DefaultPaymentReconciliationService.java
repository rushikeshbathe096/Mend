package com.mend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.CampaignAttempt;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignAttemptRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.provider.PaymentEvidenceStatus;
import com.mend.provider.PaymentLinkState;
import com.mend.provider.razorpay.RazorpayEvidenceMapper;
import com.mend.statemachine.CampaignStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    /** Single interpreter of provider status strings (M1 U5): only captured is money. */
    private final RazorpayEvidenceMapper evidenceMapper = new RazorpayEvidenceMapper();

    public DefaultPaymentReconciliationService(
            ActionIntentRepository actionIntentRepository,
            CampaignRepository campaignRepository,
            CampaignAttemptRepository campaignAttemptRepository,
            WebhookEventRepository webhookEventRepository,
            CampaignLifecycleService campaignLifecycleService,
            CampaignStateMachine campaignStateMachine,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.actionIntentRepository = actionIntentRepository;
        this.campaignRepository = campaignRepository;
        this.campaignAttemptRepository = campaignAttemptRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.campaignLifecycleService = campaignLifecycleService;
        this.campaignStateMachine = campaignStateMachine;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ReconciliationResult reconcileWebhookEvent(WebhookEvent event) {
        if (event == null) {
            return ReconciliationResult.unmatched("WebhookEvent is null");
        }

        // Parse payload details safely
        PayloadDetails payloadDetails = parsePayloadDetails(event);

        // Correlate execution attempt
        ActionIntent intent = correlateActionIntent(event, payloadDetails);
        if (intent == null) {
            log.debug("No matching ActionIntent found for webhook event [eventId={}, externalEventId={}]",
                    event.getId(), event.getExternalEventId());
            return ReconciliationResult.unmatched("No matching ActionIntent found");
        }

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

        // Owner decision D1 (M1): a payment link accepts several attempts, so one failed attempt does not end it.
        // For an intent that issued a link, a FAILED payment changes nothing; the intent stays EXECUTING.
        if (Boolean.FALSE.equals(isSuccessEvent) && isPaymentLinkReference(intent.getResponseReference())) {
            log.info("event=link_attempt_failed intent_id={} campaign_id={} webhook_event_id={}",
                    intent.getId(), campaign.getId(), event.getId());
            return ReconciliationResult.unsupported("Failed attempt on payment link " + intent.getResponseReference()
                    + "; link remains payable");
        }

        // Check if Campaign or Intent is already terminal/finalized (Late Webhook Protection)
        if (campaignStateMachine.isTerminal(campaign.getCurrentState())) {
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

        String providerRef = payloadDetails.paymentId() != null ? payloadDetails.paymentId() : event.getExternalEventId();
        Instant now = Instant.now();

        if (Boolean.TRUE.equals(isSuccessEvent)) {
            // Reconcile to SUCCESS
            intent.setStatus(ActionIntentStatus.SUCCEEDED);
            if (providerRef != null) {
                intent.setResponseReference(providerRef);
            }
            intent.setCompletedAt(now);
            actionIntentRepository.save(intent);

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
            // Reconcile to FAILURE
            intent.setStatus(ActionIntentStatus.FAILED);
            intent.setCompletedAt(now);
            actionIntentRepository.save(intent);

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

    private ActionIntent correlateActionIntent(WebhookEvent event, PayloadDetails payload) {
        // Strategy 1: Mend Idempotency Key
        if (payload.idempotencyKey() != null && !payload.idempotencyKey().isBlank()) {
            Optional<ActionIntent> byKey = actionIntentRepository.findByIdempotencyKey(payload.idempotencyKey());
            if (byKey.isPresent()) {
                log.info("Correlated ActionIntent '{}' by idempotencyKey '{}'", byKey.get().getId(), payload.idempotencyKey());
                return byKey.get();
            }
        }

        // Strategy 2: ActionIntent ID in payload notes
        if (payload.actionIntentId() != null) {
            try {
                UUID intentId = UUID.fromString(payload.actionIntentId());
                Optional<ActionIntent> byId = actionIntentRepository.findById(intentId);
                if (byId.isPresent()) {
                    log.info("Correlated ActionIntent '{}' by payload actionIntentId", intentId);
                    return byId.get();
                }
            } catch (Exception ignored) {}
        }

        // Strategy 3: External Provider Payment Reference
        if (payload.paymentId() != null && !payload.paymentId().isBlank()) {
            Optional<ActionIntent> byRef = actionIntentRepository.findByResponseReference(payload.paymentId());
            if (byRef.isPresent()) {
                log.info("Correlated ActionIntent '{}' by provider paymentId '{}'", byRef.get().getId(), payload.paymentId());
                return byRef.get();
            }
        }

        // Strategy 3b: Payment link id (ActionExecutionService stores the issued link id as responseReference)
        if (payload.paymentLinkId() != null && !payload.paymentLinkId().isBlank()) {
            Optional<ActionIntent> byLink = actionIntentRepository.findByResponseReference(payload.paymentLinkId());
            if (byLink.isPresent()) {
                log.info("Correlated ActionIntent '{}' by payment link id '{}'", byLink.get().getId(), payload.paymentLinkId());
                return byLink.get();
            }
        }

        // Strategy 4: Campaign ID & Payment ID Correlation
        String paymentIdToMatch = payload.paymentId() != null ? payload.paymentId() : event.getExternalEventId();
        if (paymentIdToMatch != null && !paymentIdToMatch.isBlank() && event.getMerchantId() != null) {
            Optional<Campaign> campaignOpt = campaignRepository.findByMerchantIdAndPaymentId(event.getMerchantId(), paymentIdToMatch);
            if (campaignOpt.isPresent()) {
                UUID campaignId = campaignOpt.get().getId();
                Optional<ActionIntent> latestIntent = actionIntentRepository.findFirstByCampaignIdOrderByCreatedAtDesc(campaignId);
                if (latestIntent.isPresent()) {
                    log.info("Correlated ActionIntent '{}' via Campaign '{}' paymentId '{}'",
                            latestIntent.get().getId(), campaignId, paymentIdToMatch);
                    return latestIntent.get();
                }
            }
        }

        // Strategy 5: Subscription ID Correlation
        if (payload.subscriptionId() != null && !payload.subscriptionId().isBlank() && event.getMerchantId() != null) {
            Optional<Campaign> campaignOpt = campaignRepository.findByMerchantIdAndSubscriptionId(event.getMerchantId(), payload.subscriptionId());
            if (campaignOpt.isPresent()) {
                UUID campaignId = campaignOpt.get().getId();
                Optional<ActionIntent> latestIntent = actionIntentRepository.findFirstByCampaignIdOrderByCreatedAtDesc(campaignId);
                if (latestIntent.isPresent()) {
                    log.info("Correlated ActionIntent '{}' via Campaign '{}' subscriptionId '{}'",
                            latestIntent.get().getId(), campaignId, payload.subscriptionId());
                    return latestIntent.get();
                }
            }
        }

        return null;
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

    private static boolean isPaymentLinkReference(String responseReference) {
        return responseReference != null && responseReference.startsWith("plink_");
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

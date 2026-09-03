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

        // Determine if event represents SUCCESS or FAILURE
        Boolean isSuccessEvent = isSuccessOutcome(event.getEventType(), payloadDetails);
        if (isSuccessEvent == null) {
            log.info("Webhook event type '{}' is not an outcome reconciliation event", event.getEventType());
            return ReconciliationResult.unsupported("Event type '" + event.getEventType() + "' is not a reconciliation event");
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

    private Boolean isSuccessOutcome(String eventType, PayloadDetails payload) {
        if (eventType != null) {
            String type = eventType.toLowerCase();
            if (type.contains("captured") || type.contains("authorized") || type.contains("order.paid") || type.contains("charged")) {
                return true;
            }
            if (type.contains("failed") || type.contains("cancelled") || type.contains("halted")) {
                return false;
            }
        }
        if (payload.status() != null) {
            String st = payload.status().toLowerCase();
            if (st.equals("captured") || st.equals("authorized") || st.equals("paid") || st.equals("active")) {
                return true;
            }
            if (st.equals("failed") || st.equals("cancelled")) {
                return false;
            }
        }
        return null;
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

        if (event.getRawPayload() != null && !event.getRawPayload().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(event.getRawPayload());
                JsonNode paymentEntity = null;

                if (root.has("payload")) {
                    JsonNode payload = root.get("payload");
                    if (payload.has("payment") && payload.get("payment").has("entity")) {
                        paymentEntity = payload.get("payment").get("entity");
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
                        if (notes.has("idempotency_key")) idempotencyKey = notes.get("idempotency_key").asText();
                        if (notes.has("action_intent_id")) actionIntentId = notes.get("action_intent_id").asText();
                    }
                    if (paymentEntity.has("receipt") && !paymentEntity.get("receipt").isNull()) {
                        if (idempotencyKey == null) idempotencyKey = paymentEntity.get("receipt").asText();
                    }
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

        return new PayloadDetails(paymentId, subscriptionId, idempotencyKey, actionIntentId, status, failureReason);
    }

    private record PayloadDetails(
            String paymentId,
            String subscriptionId,
            String idempotencyKey,
            String actionIntentId,
            String status,
            String failureReason
    ) {}
}

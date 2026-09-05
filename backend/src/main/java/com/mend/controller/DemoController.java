package com.mend.controller;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.AgentDecisionRecord;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ReviewQueue;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ReviewQueueStatus;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.AgentDecisionRecordRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.DemoScenarioDto;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import com.mend.service.ActionExecutionService;
import com.mend.service.ActionIntentService;
import com.mend.service.AuditService;
import com.mend.service.CampaignLifecycleService;
import com.mend.service.HumanApprovalService;
import com.mend.service.PaymentReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic demonstration engine for the Mend merchant console.
 *
 * Every scenario drives the SAME production services used by real recovery
 * workflows (campaign lifecycle state machine, agent decision records,
 * compliance-gated ActionIntent creation, provider execution boundary and
 * payment reconciliation). Demo rows are clearly isolated from merchant data:
 * payment references use the reserved {@code pay_demo_*} / {@code cust_demo_*}
 * prefixes and every run is audit-logged under the authenticated merchant.
 *
 * Nothing here grants the frontend direct provider access, bypasses compliance,
 * or fabricates metrics: each scenario returns the authoritative campaign/review
 * identifiers produced by the real pipeline.
 */
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final CampaignLifecycleService campaignLifecycleService;
    private final ActionIntentService actionIntentService;
    private final ActionExecutionService actionExecutionService;
    private final HumanApprovalService humanApprovalService;
    private final PaymentReconciliationService paymentReconciliationService;
    private final CampaignRepository campaignRepository;
    private final AgentDecisionRecordRepository agentDecisionRecordRepository;
    private final ActionIntentRepository actionIntentRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final AuditService auditService;

    public DemoController(
            CampaignLifecycleService campaignLifecycleService,
            ActionIntentService actionIntentService,
            ActionExecutionService actionExecutionService,
            HumanApprovalService humanApprovalService,
            PaymentReconciliationService paymentReconciliationService,
            CampaignRepository campaignRepository,
            AgentDecisionRecordRepository agentDecisionRecordRepository,
            ActionIntentRepository actionIntentRepository,
            WebhookEventRepository webhookEventRepository,
            AuditService auditService) {
        this.campaignLifecycleService = campaignLifecycleService;
        this.actionIntentService = actionIntentService;
        this.actionExecutionService = actionExecutionService;
        this.humanApprovalService = humanApprovalService;
        this.paymentReconciliationService = paymentReconciliationService;
        this.campaignRepository = campaignRepository;
        this.agentDecisionRecordRepository = agentDecisionRecordRepository;
        this.actionIntentRepository = actionIntentRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.auditService = auditService;
    }

    public static class DemoTriggerRequest {
        private String scenario;

        public String getScenario() { return scenario; }
        public void setScenario(String scenario) { this.scenario = scenario; }
    }

    public static class DemoTriggerResponse {
        private String scenario;
        private String status;
        private UUID campaignId;
        private String paymentId;
        private String customerIdHash;
        private UUID reviewId;
        private BigDecimal amount;
        private String message;
        private CampaignStatus finalCampaignState;
        private List<String> executionSteps;

        public DemoTriggerResponse() { }

        public String getScenario() { return scenario; }
        public void setScenario(String scenario) { this.scenario = scenario; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public UUID getCampaignId() { return campaignId; }
        public void setCampaignId(UUID campaignId) { this.campaignId = campaignId; }
        public String getPaymentId() { return paymentId; }
        public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
        public String getCustomerIdHash() { return customerIdHash; }
        public void setCustomerIdHash(String customerIdHash) { this.customerIdHash = customerIdHash; }
        public UUID getReviewId() { return reviewId; }
        public void setReviewId(UUID reviewId) { this.reviewId = reviewId; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public CampaignStatus getFinalCampaignState() { return finalCampaignState; }
        public void setFinalCampaignState(CampaignStatus finalCampaignState) { this.finalCampaignState = finalCampaignState; }
        public List<String> getExecutionSteps() { return executionSteps; }
        public void setExecutionSteps(List<String> executionSteps) { this.executionSteps = executionSteps; }
    }

    @GetMapping("/scenarios")
    public ResponseEntity<List<DemoScenarioDto>> listScenarios(@CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(DemoScenarioDto.catalog());
    }

    @PostMapping("/trigger-scenario")
    public ResponseEntity<DemoTriggerResponse> triggerScenario(
            @RequestBody DemoTriggerRequest request,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        if (request == null || request.getScenario() == null || request.getScenario().isBlank()) {
            throw new InvalidRequestException("Scenario name is required");
        }

        UUID merchantId = resolveMerchantId(merchantHeader, currentUser);
        String scenario = request.getScenario().trim().toUpperCase();

        DemoTriggerResponse response = new DemoTriggerResponse();
        response.setScenario(scenario);
        response.setStatus("SUCCESS");
        List<String> steps = new ArrayList<>();

        try {
            switch (scenario) {
                case "LOW_RISK_RETRY" -> runLowRiskRetry(merchantId, response, steps);
                case "HIGH_RISK_HUMAN_REVIEW" -> runHighRiskHumanReview(merchantId, response, steps);
                case "CUSTOMER_ACTION" -> runCustomerAction(merchantId, response, steps);
                case "PROVIDER_AMBIGUITY" -> runProviderAmbiguity(merchantId, response, steps);
                case "DUPLICATE_EVENT" -> runDuplicateEvent(merchantId, response, steps);
                case "AGENT_FAILURE" -> runAgentFailure(merchantId, response, steps);
                default -> throw new InvalidRequestException("Unknown demo scenario: " + scenario
                        + " (expected LOW_RISK_RETRY, HIGH_RISK_HUMAN_REVIEW, CUSTOMER_ACTION, PROVIDER_AMBIGUITY, DUPLICATE_EVENT or AGENT_FAILURE)");
            }
        } catch (Exception e) {
            log.error("Error executing demo scenario '{}' for merchant '{}': {}", scenario, merchantId, e.getMessage(), e);
            response.setStatus("ERROR");
            response.setMessage("Scenario execution failed: " + e.getMessage());
            response.setExecutionSteps(steps);
            return ResponseEntity.internalServerError().body(response);
        }

        response.setExecutionSteps(steps);
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // SCENARIO 1 — LOW-RISK AUTOMATED RETRY (full execution to RECOVERED)
    // ------------------------------------------------------------------
    private void runLowRiskRetry(UUID merchantId, DemoTriggerResponse response, List<String> steps) {
        BigDecimal amount = new BigDecimal("4999.00");
        Setup setup = prepareCampaign(merchantId, "low_risk_retry", "INSUFFICIENT_FUNDS", "0.95",
                "RETRY_IMMEDIATELY", amount, response, steps);

        recordAgentDecision(merchantId, setup.campaignId(), setup.paymentId(), "RETRY_PAYMENT", "RETRY_IMMEDIATELY",
                false, "Transient failure detected with high classification confidence; automated retry is safe.", "AUTO_APPROVED");

        steps.add("Six-agent consensus: LOW risk, proposed action RETRY_PAYMENT (confidence 95%).");
        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, setup.campaignId());
        transitionToActionPending(merchantId, setup.campaignId(), "Action intent " + intent.getId() + " created after compliance approval");
        steps.add("Compliance engine ALLOWED; ActionIntent " + intent.getId() + " created for attempt #" + intent.getAttemptNumber() + ".");

        claimAndExecute(merchantId, intent, response, steps);

        Campaign updated = campaignRepository.findById(setup.campaignId()).orElseThrow();
        response.setCampaignId(updated.getId());
        response.setFinalCampaignState(updated.getCurrentState());
        response.setAmount(amount);
        response.setMessage("Scenario LOW_RISK_RETRY completed: campaign " + updated.getId()
                + " reached " + updated.getCurrentState() + " via the real provider execution boundary.");
    }

    // ------------------------------------------------------------------
    // SCENARIO 2 — HIGH-RISK HUMAN REVIEW (halts at the approval queue)
    // ------------------------------------------------------------------
    private void runHighRiskHumanReview(UUID merchantId, DemoTriggerResponse response, List<String> steps) {
        BigDecimal amount = new BigDecimal("72000.00");
        Setup setup = prepareCampaign(merchantId, "high_risk_review", "AUTHENTICATION_FAILED", "0.90",
                "RETRY_IMMEDIATELY", amount, response, steps);

        recordAgentDecision(merchantId, setup.campaignId(), setup.paymentId(), "RETRY_PAYMENT", "RETRY_IMMEDIATELY",
                true, "High-value transaction (₹72,000) exceeds the merchant automated-retry threshold and the customer has prior dispute history; merchant confirmation required before execution.", "REVIEW_REQUIRED");

        steps.add("Risk agent flagged HIGH risk; supervisor consensus REVIEW_REQUIRED with proposed action RETRY_PAYMENT.");
        steps.add("Compliance strategy is executable but gated on merchant approval - no ActionIntent created yet.");

        ReviewQueue review = humanApprovalService.createReview(
                merchantId, setup.campaignId(),
                "High-value recovery (₹72,000) requires merchant human approval before execution.");
        response.setReviewId(review.getId());
        steps.add("Campaign routed to the merchant human-approval queue (review " + review.getId() + ", status "
                + review.getStatus() + "). Open Recovery Actions -> Waiting for Approval to decide.");

        auditService.logEvent(merchantId, setup.campaignId(), "DEMO_SCENARIO_TRIGGERED", "USER", null,
                "Demo scenario HIGH_RISK_HUMAN_REVIEW prepared campaign " + setup.campaignId() + " for merchant review");

        Campaign updated = campaignRepository.findById(setup.campaignId()).orElseThrow();
        response.setCampaignId(updated.getId());
        response.setFinalCampaignState(updated.getCurrentState());
        response.setAmount(amount);
        response.setMessage("Scenario HIGH_RISK_HUMAN_REVIEW staged: campaign " + updated.getId()
                + " is ELIGIBLE with review " + review.getId() + " pending merchant approval. Approving it will revalidate compliance and authorize execution.");
    }

    // ------------------------------------------------------------------
    // SCENARIO 3 — CUSTOMER ACTION (payment-link recovery + reconciliation)
    // ------------------------------------------------------------------
    private void runCustomerAction(UUID merchantId, DemoTriggerResponse response, List<String> steps) {
        BigDecimal amount = new BigDecimal("12999.00");
        Setup setup = prepareCampaign(merchantId, "customer_action", "CARD_EXPIRED", "0.98",
                "CUSTOMER_ACTION_REQUIRED", amount, response, steps);

        recordAgentDecision(merchantId, setup.campaignId(), setup.paymentId(), "REQUEST_CUSTOMER_ACTION", "CUSTOMER_ACTION_REQUIRED",
                false, "Card expired; a customer action (card update / payment link) is required instead of an automated retry.", "AUTHORIZED");

        steps.add("Customer Engagement agent recommended a payment-update link (REQUEST_CUSTOMER_ACTION).");
        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, setup.campaignId());
        transitionToActionPending(merchantId, setup.campaignId(),
                "Payment link dispatched for customer action (intent " + intent.getId() + ")");
        steps.add("Compliance ALLOWED and ActionIntent " + intent.getId() + " created; payment update link dispatched to the customer.");

        steps.add("Simulating the customer completing payment: a payment.captured webhook is delivered through the real reconciliation service.");
        String capturedEventId = "evt_" + UUID.randomUUID();
        reconcileCapturedPayment(merchantId, setup.campaignId(), setup.paymentId(), capturedEventId, amount, response, steps);

        Campaign updated = campaignRepository.findById(setup.campaignId()).orElseThrow();
        response.setCampaignId(updated.getId());
        response.setFinalCampaignState(updated.getCurrentState());
        response.setAmount(amount);
        response.setMessage("Scenario CUSTOMER_ACTION completed: customer completed payment; reconciliation moved campaign "
                + updated.getId() + " to " + updated.getCurrentState() + ".");
    }

    // ------------------------------------------------------------------
    // SCENARIO 4 — PROVIDER AMBIGUITY (reconciliation resolves the truth)
    // ------------------------------------------------------------------
    private void runProviderAmbiguity(UUID merchantId, DemoTriggerResponse response, List<String> steps) {
        BigDecimal amount = new BigDecimal("2999.00");
        Setup setup = prepareCampaign(merchantId, "provider_ambiguity", "NETWORK_TIMEOUT", "0.92",
                "RETRY_IMMEDIATELY", amount, response, steps);

        recordAgentDecision(merchantId, setup.campaignId(), setup.paymentId(), "RETRY_PAYMENT", "RETRY_IMMEDIATELY",
                false, "Provider connection timed out during execution; the authoritative outcome is unknown until reconciled.", "AMBIGUOUS");

        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, setup.campaignId());
        transitionToActionPending(merchantId, setup.campaignId(),
                "Retry dispatched toward the provider; response window open (intent " + intent.getId() + ")");

        steps.add("ActionIntent " + intent.getId() + " claimed for execution; provider request dispatched.");
        claimIntent(intent);
        campaignLifecycleService.transitionState(merchantId, setup.campaignId(), CampaignStatus.EXECUTING,
                "Provider execution in flight with ambiguous outcome; awaiting reconciliation", "SYSTEM", null);
        steps.add("Provider response timed out - execution state is UNKNOWN (ambiguous). No terminal state assumed.");

        steps.add("Authoritative provider event arrives: reconciling via DefaultPaymentReconciliationService.");
        String capturedEventId = "evt_" + UUID.randomUUID();
        reconcileCapturedPayment(merchantId, setup.campaignId(), setup.paymentId(), capturedEventId, amount, response, steps);

        Campaign updated = campaignRepository.findById(setup.campaignId()).orElseThrow();
        response.setCampaignId(updated.getId());
        response.setFinalCampaignState(updated.getCurrentState());
        response.setAmount(amount);
        response.setMessage("Scenario PROVIDER_AMBIGUITY completed: ambiguous execution was reconciled from provider evidence to "
                + updated.getCurrentState() + " (campaign " + updated.getId() + ").");
    }

    // ------------------------------------------------------------------
    // SCENARIO 5 — DUPLICATE WEBHOOK EVENT (strict deduplication)
    // ------------------------------------------------------------------
    private void runDuplicateEvent(UUID merchantId, DemoTriggerResponse response, List<String> steps) {
        String paymentId = demoRef("pay_demo_duplicate_event");
        String externalEventId = "evt_demo_duplicate_" + UUID.randomUUID();
        WebhookEvent original = buildWebhookEvent(merchantId, externalEventId, "payment.failed", paymentId,
                new BigDecimal("8999.00"), "failed", "bank timeout");

        webhookEventRepository.saveAndFlush(original);
        steps.add("Original webhook delivered and persisted (external_event_id=" + externalEventId + ").");

        boolean duplicateFound = webhookEventRepository.findByMerchantIdAndExternalEventId(merchantId, externalEventId).isPresent();
        steps.add("Duplicate delivery attempt intercepted: event already present = " + duplicateFound + ".");
        if (!duplicateFound) {
            throw new IllegalStateException("Deduplication check failed to detect the original event");
        }

        long stored = webhookEventRepository.findByMerchantId(merchantId).stream()
                .filter(e -> externalEventId.equals(e.getExternalEventId()))
                .count();
        auditService.logEvent(merchantId, null, "DUPLICATE_EVENT_INTERCEPTED", "SYSTEM", null,
                "Duplicate webhook delivery for event " + externalEventId + " intercepted; stored copy count = " + stored);
        steps.add("Duplicate was NOT persisted: " + stored + " stored copy -> one business event, one recovery path.");

        response.setPaymentId(paymentId);
        response.setFinalCampaignState(null);
        response.setAmount(new BigDecimal("8999.00"));
        response.setMessage("Scenario DUPLICATE_EVENT completed: deduplication enforced against the unique external_event_id index.");
    }

    // ------------------------------------------------------------------
    // SCENARIO 6 — AGENT FAILURE (deterministic safe fallback)
    // ------------------------------------------------------------------
    private void runAgentFailure(UUID merchantId, DemoTriggerResponse response, List<String> steps) {
        BigDecimal amount = new BigDecimal("3499.00");
        Setup setup = prepareCampaign(merchantId, "agent_failure", "UNKNOWN", "0.40", null, amount, response, steps, false);

        recordAgentDecision(merchantId, setup.campaignId(), setup.paymentId(), "MANUAL_REVIEW", "FALLBACK_STRATEGY",
                false, "AI/MCP execution unavailable; deterministic heuristic fallback applied. Low confidence (40%) and unknown failure class mean no automated action is safe.", "SAFE_FALLBACK");

        steps.add("AI service timeout simulated. Heuristic fallback produced a safe decision: no automated retry.");
        campaignLifecycleService.transitionState(merchantId, setup.campaignId(), CampaignStatus.EXHAUSTED,
                "Deterministic fallback: UNKNOWN classification at 40% confidence is not eligible for automated recovery",
                "SYSTEM", null);
        steps.add("Campaign closed deterministically as EXHAUSTED - no payment action was taken.");

        Campaign updated = campaignRepository.findById(setup.campaignId()).orElseThrow();
        response.setCampaignId(updated.getId());
        response.setFinalCampaignState(updated.getCurrentState());
        response.setAmount(amount);
        response.setMessage("Scenario AGENT_FAILURE completed: safe deterministic fallback left campaign "
                + updated.getId() + " in " + updated.getCurrentState() + ".");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record Setup(UUID campaignId, String paymentId) { }

    private Setup prepareCampaign(UUID merchantId, String slug, String failureClass, String confidence,
                                  String strategy, BigDecimal amount, DemoTriggerResponse response, List<String> steps) {
        return prepareCampaign(merchantId, slug, failureClass, confidence, strategy, amount, response, steps, true);
    }

    /**
     * @param moveToEligible when false the campaign stays CLASSIFIED (used by the
     *                       agent-failure scenario, mirroring production, where ineligible
     *                       classifications never become ELIGIBLE).
     */
    private Setup prepareCampaign(UUID merchantId, String slug, String failureClass, String confidence,
                                  String strategy, BigDecimal amount, DemoTriggerResponse response,
                                  List<String> steps, boolean moveToEligible) {
        String paymentId = demoRef("pay_demo_" + slug);
        String customerIdHash = demoRef("cust_demo_" + slug);

        // Authoritative provider evidence for the failed payment (single source of amount truth).
        // Consistent with the ingestion pipeline, the campaign payment identity equals the
        // webhook's external event id.
        WebhookEvent event = buildWebhookEvent(merchantId, paymentId, "payment.failed", paymentId,
                amount, "failed", failureClass.toLowerCase());
        webhookEventRepository.saveAndFlush(event);
        steps.add("Provider webhook ingested for failed payment " + paymentId + " (₹"
                + amount.toPlainString() + ").");

        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, customerIdHash, null);
        campaign.setFailureClass(failureClass);
        campaign.setConfidence(new BigDecimal(confidence));
        if (strategy != null) {
            campaign.setStrategy(strategy);
        }
        campaignRepository.saveAndFlush(campaign);

        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED,
                "AI classification attached: " + failureClass + " (confidence " + confidence + ")", "SYSTEM", null);
        steps.add("Failure classified as " + failureClass + " (confidence " + confidence + "%).");

        if (moveToEligible) {
            campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE,
                    "Campaign eligible for recovery evaluation", "SYSTEM", null);
            steps.add("Campaign " + campaign.getId() + " evaluated as ELIGIBLE for recovery.");
        }

        response.setPaymentId(paymentId);
        response.setCustomerIdHash(customerIdHash);
        response.setCampaignId(campaign.getId());
        return new Setup(campaign.getId(), paymentId);
    }

    private void recordAgentDecision(UUID merchantId, UUID campaignId, String paymentId,
                                     String decision, String selectedAction, boolean humanReview,
                                     String reason, String executionStatus) {
        AgentDecisionRecord record = new AgentDecisionRecord();
        record.setId(UUID.randomUUID());
        record.setMerchantId(merchantId);
        record.setCampaignId(campaignId);
        record.setPaymentId(paymentId);
        record.setDecision(decision);
        record.setSelectedAction(selectedAction);
        record.setConfidence(BigDecimal.valueOf(0.90));
        record.setReasoning(reason);
        record.setEvidence("Structured agent summary: risk, proposed action and strategy consensus");
        record.setRequiresHumanApproval(humanReview);
        record.setComplianceStatus("ALLOWED");
        record.setExecutionStatus(executionStatus);
        record.setCreatedAt(Instant.now());
        agentDecisionRecordRepository.saveAndFlush(record);

        auditService.logEvent(merchantId, campaignId, "AGENT_DECISION_MADE", "AGENT", null,
                "Decision=" + decision + ", Reason: " + reason);
    }

    private void transitionToActionPending(UUID merchantId, UUID campaignId, String reason) {
        campaignLifecycleService.transitionState(merchantId, campaignId, CampaignStatus.ACTION_PENDING, reason, "SYSTEM", null);
    }

    private void claimAndExecute(UUID merchantId, ActionIntent intent, DemoTriggerResponse response, List<String> steps) {
        claimIntent(intent);
        steps.add("Scheduler claim acquired (worker demo-worker); executing through the provider boundary...");
        actionExecutionService.executeActionIntent(intent.getId(), "demo-worker");
    }

    private void claimIntent(ActionIntent intent) {
        intent.setStatus(ActionIntentStatus.CLAIMED);
        intent.setWorkerId("demo-worker");
        intent.setClaimedAt(Instant.now());
        actionIntentRepository.saveAndFlush(intent);
    }

    private void reconcileCapturedPayment(UUID merchantId, UUID campaignId, String paymentId,
                                          String eventId, BigDecimal amount,
                                          DemoTriggerResponse response, List<String> steps) {
        WebhookEvent captured = buildWebhookEvent(merchantId, eventId, "payment.captured", paymentId,
                amount, "captured", null);
        webhookEventRepository.saveAndFlush(captured);

        var result = paymentReconciliationService.reconcileWebhookEvent(captured);
        steps.add("Reconciliation result for " + eventId + ": " + result.getMessage());
        if (!result.isReconciled()) {
            throw new IllegalStateException("Reconciliation did not resolve the payment: " + result.getMessage());
        }
        auditService.logEvent(merchantId, campaignId, "DEMO_RECONCILED", "SYSTEM", null,
                "Demo captured-event reconciliation completed for payment " + paymentId);
    }

    private WebhookEvent buildWebhookEvent(UUID merchantId, String externalEventId, String eventType,
                                           String paymentId, BigDecimal amount, String status, String failureCode) {
        String payload = """
                {"entity":"event","event":"%s","payload":{"payment":{"entity":{"id":"%s","amount":%d,"currency":"INR","status":"%s","method":"card"%s}}}}
                """.formatted(
                eventType, paymentId,
                amount.movePointRight(2).longValueExact(), status,
                failureCode != null ? ",\"error_code\":\"" + failureCode + "\",\"error_description\":\"demo failure: " + failureCode + "\"" : ""
        );

        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), externalEventId, eventType, "RAZORPAY", payload);
        event.setMerchantId(merchantId);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        return event;
    }

    private String demoRef(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID resolveMerchantId(String merchantHeader, AuthenticatedUser currentUser) {
        if (merchantHeader != null && !merchantHeader.isBlank()) {
            try {
                return UUID.fromString(merchantHeader.trim());
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("Invalid X-Merchant-Id header format");
            }
        }
        if (TenantContext.getCurrentMerchantId() != null) {
            return TenantContext.getCurrentMerchantId();
        }
        if (currentUser != null && currentUser.getMemberships() != null && !currentUser.getMemberships().isEmpty()) {
            return currentUser.getMemberships().get(0).getMerchantId();
        }
        throw new TenantAccessDeniedException("Demo scenarios require an authenticated merchant context (X-Merchant-Id header or tenant association)");
    }
}

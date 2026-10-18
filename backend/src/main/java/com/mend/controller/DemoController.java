package com.mend.controller;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.ClassificationResult;
import com.mend.taxonomy.FailureClassification;
import com.mend.taxonomy.FailureTaxonomy;
import com.mend.taxonomy.ProviderFailure;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ReviewQueue;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ReviewQueueStatus;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.ActionIntentRepository;
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
 * workflows (campaign lifecycle state machine,
 * compliance-gated ActionIntent creation, provider execution boundary and
 * payment reconciliation). Demo rows are clearly isolated from merchant data:
 * payment references use the reserved {@code pay_demo_*} / {@code cust_demo_*}
 * prefixes and every run is audit-logged under the authenticated merchant.
 *
 * Nothing here grants the frontend direct provider access, bypasses compliance,
 * or fabricates metrics: each scenario returns the authoritative campaign/review
 * identifiers produced by the real pipeline.
 * <p>
 * Pre-M3 hygiene: the inputs are fabricated (webhooks, the customer's payment, timeouts),
 * so every such step is labelled {@value #SIMULATED} in the narration, the stored reasons and the response. The controller
 * exists only when {@code mend.demo.enabled=true} (default false; true in the dev profile).
 */
@RestController
@RequestMapping("/api/v1/demo")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "mend.demo.enabled", havingValue = "true")
public class DemoController {

    static final String SIMULATED = "SIMULATED";
    /** Source of every webhook row the demo fabricates (real ones are RAZORPAY). */
    static final String DEMO_SOURCE = "MEND_DEMO_SIMULATED";

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final CampaignLifecycleService campaignLifecycleService;
    private final ActionIntentService actionIntentService;
    private final ActionExecutionService actionExecutionService;
    private final HumanApprovalService humanApprovalService;
    private final PaymentReconciliationService paymentReconciliationService;
    private final CampaignRepository campaignRepository;
    private final com.mend.domain.repository.ClassificationResultRepository classificationResultRepository;
    private final ActionIntentRepository actionIntentRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final AuditService auditService;
    private final com.mend.service.ActionIntentClaimService actionIntentClaimService;
    private final com.mend.service.ActionIntentTransitions actionIntentTransitions;
    /** {@code mock}: provider calls are SIMULATED; {@code razorpay}: they reach Razorpay (test mode) and are labelled so. */
    private final String providerMode;

    public DemoController(
            CampaignLifecycleService campaignLifecycleService,
            ActionIntentService actionIntentService,
            ActionExecutionService actionExecutionService,
            HumanApprovalService humanApprovalService,
            PaymentReconciliationService paymentReconciliationService,
            CampaignRepository campaignRepository,
            com.mend.domain.repository.ClassificationResultRepository classificationResultRepository,
            ActionIntentRepository actionIntentRepository,
            WebhookEventRepository webhookEventRepository,
            AuditService auditService,
            com.mend.service.ActionIntentClaimService actionIntentClaimService,
            com.mend.service.ActionIntentTransitions actionIntentTransitions,
            @org.springframework.beans.factory.annotation.Value("${mend.payment.provider:mock}") String providerMode) {
        this.campaignLifecycleService = campaignLifecycleService;
        this.actionIntentService = actionIntentService;
        this.actionExecutionService = actionExecutionService;
        this.humanApprovalService = humanApprovalService;
        this.paymentReconciliationService = paymentReconciliationService;
        this.campaignRepository = campaignRepository;
        this.classificationResultRepository = classificationResultRepository;
        this.actionIntentRepository = actionIntentRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.auditService = auditService;
        this.actionIntentClaimService = actionIntentClaimService;
        this.actionIntentTransitions = actionIntentTransitions;
        this.providerMode = providerMode;
    }

    /** A fabricated step (no real provider, customer, AI or webhook source behind it). */
    private static String simulated(String text) {
        return SIMULATED + ": " + text;
    }

    private String providerStep(String text) {
        return "mock".equalsIgnoreCase(providerMode)
                ? simulated(text + " (mock provider: no provider request, no money moves)")
                : text + " (provider mode " + providerMode + ": a real provider request in Razorpay test mode)";
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
        /** Always true: every scenario runs on fabricated inputs. */
        private final boolean simulated = true;

        public DemoTriggerResponse() { }

        public boolean isSimulated() { return simulated; }

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
        public void setMessage(String message) { this.message = message == null || message.startsWith(SIMULATED) ? message : SIMULATED + " demo: " + message; }
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
                case "UNCLASSIFIED_FAILURE" -> runUnclassifiedFailure(merchantId, response, steps);
                default -> throw new InvalidRequestException("Unknown demo scenario: " + scenario
                        + " (expected LOW_RISK_RETRY, HIGH_RISK_HUMAN_REVIEW, CUSTOMER_ACTION, PROVIDER_AMBIGUITY, DUPLICATE_EVENT or UNCLASSIFIED_FAILURE)");
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
        Setup setup = prepareCampaign(merchantId, "low_risk_retry", "insufficient_funds", "customer",
                "RETRY_IMMEDIATELY", amount, response, steps);

        steps.add(simulated("demo preset strategy RETRY_IMMEDIATELY (deterministic; no AI)."));
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
        Setup setup = prepareCampaign(merchantId, "high_risk_review", "authentication_failed", "customer",
                "RETRY_IMMEDIATELY", amount, response, steps);

        steps.add(simulated("the demo holds this high-value recovery for merchant approval (a review item, not an AI decision); no ActionIntent created yet."));

        ReviewQueue review = humanApprovalService.createReview(
                merchantId, setup.campaignId(),
                simulated("high-value recovery (₹72,000) requires merchant human approval before execution (demo review)."));
        response.setReviewId(review.getId());
        steps.add("Campaign routed to the merchant human-approval queue (review " + review.getId() + ", status "
                + review.getStatus() + "). Open Recovery Actions -> Waiting for Approval to decide.");

        auditService.logEvent(merchantId, setup.campaignId(), "DEMO_SCENARIO_TRIGGERED", "USER", null,
                simulated("demo scenario HIGH_RISK_HUMAN_REVIEW prepared campaign " + setup.campaignId() + " for merchant review"));

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
        Setup setup = prepareCampaign(merchantId, "customer_action", "card_expired", "customer",
                "CUSTOMER_ACTION_REQUIRED", amount, response, steps);

        steps.add(simulated("strategy CUSTOMER_ACTION_REQUIRED for INSTRUMENT_INVALID (deterministic table): a payment-update link."));
        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, setup.campaignId());
        transitionToActionPending(merchantId, setup.campaignId(),
                simulated("payment link requested for customer action (intent " + intent.getId() + ")"));
        steps.add("Compliance ALLOWED and ActionIntent " + intent.getId() + " created.");
        // M2: execute through the real claim + executor; the mock provider issues a (SYNTHETIC) payment link -> AWAITING_PAYMENT.
        claimAndExecute(merchantId, intent, response, steps);
        ActionIntentStatus linkState = actionIntentRepository.findById(intent.getId()).orElseThrow().getStatus();
        if (linkState != ActionIntentStatus.AWAITING_PAYMENT) {
            throw new IllegalStateException("Demo payment link was not issued; intent is " + linkState);
        }
        steps.add(providerStep("payment update link issued (intent " + linkState + "); no customer is notified by the demo"));

        steps.add(simulated("the customer completing payment: a fabricated payment.captured webhook is delivered to the real reconciliation service."));
        String capturedEventId = "evt_" + UUID.randomUUID();
        reconcileCapturedPayment(merchantId, setup.campaignId(), setup.paymentId(), intent.getId(), capturedEventId, amount, response, steps);

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
        Setup setup = prepareCampaign(merchantId, "provider_ambiguity", "gateway_technical_error", "gateway",
                "RETRY_IMMEDIATELY", amount, response, steps);

        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, setup.campaignId());
        transitionToActionPending(merchantId, setup.campaignId(),
                simulated("retry requested; response window open (intent " + intent.getId() + ")"));

        ActionIntent claimed = actionIntentClaimService.claimSpecificIntent(intent.getId(), "demo-worker")
                .orElseThrow(() -> new IllegalStateException("Demo intent " + intent.getId() + " could not be claimed"));
        // M2: record the dispatch (CLAIMED -> EXECUTING) as the executor does, then the simulated timeout as an unknown
        // outcome (NEEDS_RECONCILIATION). Demo-only: NO provider call is made here, which is why this skips the executor's
        // compliance re-check; never copy this pattern into a path that calls the provider (invariants 7 and 12).
        Instant now = Instant.now();
        if (!actionIntentTransitions.dispatch(claimed.getId(), claimed.getClaimToken(), now)) {
            throw new IllegalStateException("Demo dispatch was not applied for intent " + claimed.getId());
        }
        campaignLifecycleService.transitionState(merchantId, setup.campaignId(), CampaignStatus.EXECUTING,
                simulated("provider execution with ambiguous outcome (no provider request made); awaiting reconciliation"), "SYSTEM", null);
        if (!actionIntentTransitions.finalizeFromExecuting(claimed.getId(), claimed.getClaimToken(), ActionIntentStatus.NEEDS_RECONCILIATION,
                null, null, now)) {
            throw new IllegalStateException("Demo unknown outcome was not recorded for intent " + claimed.getId());
        }
        steps.add("ActionIntent " + intent.getId() + " claimed and dispatch recorded; " + simulated("provider call - NO provider request was made."));
        steps.add(simulated("provider timeout - execution state recorded as UNKNOWN (NEEDS_RECONCILIATION). No terminal state assumed."));

        steps.add(simulated("provider event: a fabricated payment.captured webhook is reconciled by the real DefaultPaymentReconciliationService."));
        String capturedEventId = "evt_" + UUID.randomUUID();
        reconcileCapturedPayment(merchantId, setup.campaignId(), setup.paymentId(), intent.getId(), capturedEventId, amount, response, steps);

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
                new BigDecimal("8999.00"), "failed", "bank_technical_error", "gateway", null);

        webhookEventRepository.saveAndFlush(original);
        steps.add(simulated("webhook delivery: a fabricated event was persisted (external_event_id=" + externalEventId + ")."));

        boolean duplicateFound = webhookEventRepository.findByMerchantIdAndExternalEventId(merchantId, externalEventId).isPresent();
        steps.add(simulated("duplicate delivery: no second request was sent; the stored event was looked up by external_event_id (found = " + duplicateFound + ")."));
        if (!duplicateFound) {
            throw new IllegalStateException("Deduplication check failed to detect the original event");
        }

        long stored = webhookEventRepository.findByMerchantId(merchantId).stream()
                .filter(e -> externalEventId.equals(e.getExternalEventId()))
                .count();
        auditService.logEvent(merchantId, null, "DUPLICATE_EVENT_INTERCEPTED", "SYSTEM", null,
                simulated("duplicate webhook delivery for event " + externalEventId + " (looked up, not re-sent); stored copy count = " + stored));
        steps.add(stored + " stored copy for that external_event_id (unique index) -> one business event, one recovery path.");

        response.setPaymentId(paymentId);
        response.setFinalCampaignState(null);
        response.setAmount(new BigDecimal("8999.00"));
        response.setMessage("Scenario DUPLICATE_EVENT completed: deduplication enforced against the unique external_event_id index.");
    }

    // ------------------------------------------------------------------
    // SCENARIO 6 — UNCLASSIFIED FAILURE (no documented mapping: never guessed, no action)
    // ------------------------------------------------------------------
    private void runUnclassifiedFailure(UUID merchantId, DemoTriggerResponse response, List<String> steps) {
        BigDecimal amount = new BigDecimal("3499.00");
        Setup setup = prepareCampaign(merchantId, "unclassified_failure", "payment_failed", "customer", null, amount, response, steps, false);

        campaignLifecycleService.transitionState(merchantId, setup.campaignId(), CampaignStatus.EXHAUSTED,
                "Ineligible for recovery: failure class 'UNCLASSIFIED' has no documented recovery mapping", "SYSTEM", null);
        steps.add("UNCLASSIFIED is ineligible (as in the real pipeline): campaign EXHAUSTED, no payment action was taken.");

        Campaign updated = campaignRepository.findById(setup.campaignId()).orElseThrow();
        response.setCampaignId(updated.getId());
        response.setFinalCampaignState(updated.getCurrentState());
        response.setAmount(amount);
        response.setMessage("Scenario UNCLASSIFIED_FAILURE completed: the undocumented failure left campaign "
                + updated.getId() + " in " + updated.getCurrentState() + ".");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record Setup(UUID campaignId, String paymentId) { }

    private Setup prepareCampaign(UUID merchantId, String slug, String errorReason, String errorSource,
                                  String strategy, BigDecimal amount, DemoTriggerResponse response, List<String> steps) {
        return prepareCampaign(merchantId, slug, errorReason, errorSource, strategy, amount, response, steps, true);
    }

    /**
     * @param moveToEligible when false the campaign stays CLASSIFIED (used by the
     *                       unclassified-failure scenario, mirroring production, where ineligible
     *                       classifications never become ELIGIBLE).
     */
    private Setup prepareCampaign(UUID merchantId, String slug, String errorReason, String errorSource,
                                  String strategy, BigDecimal amount, DemoTriggerResponse response,
                                  List<String> steps, boolean moveToEligible) {
        String paymentId = demoRef("pay_demo_" + slug);
        String customerIdHash = demoRef("cust_demo_" + slug);

        // Authoritative provider evidence for the failed payment (single source of amount truth).
        // Consistent with the ingestion pipeline, the campaign payment identity equals the
        // webhook's external event id.
        WebhookEvent event = buildWebhookEvent(merchantId, paymentId, "payment.failed", paymentId,
                amount, "failed", errorReason, errorSource, null);
        webhookEventRepository.saveAndFlush(event);
        steps.add(simulated("failed-payment webhook (fabricated, not from Razorpay) persisted for " + paymentId + " (₹"
                + amount.toPlainString() + ")."));

        // M3 (ADR-003): the simulated failure is classified by the real deterministic taxonomy from its error fields;
        // no confidence, no model.
        FailureClassification classification = FailureTaxonomy.classify(ProviderFailure.of("BAD_REQUEST_ERROR", errorReason, errorSource));
        String failureClass = classification.failureClass().name();
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, customerIdHash, null);
        campaign.setFailureClass(failureClass);
        if (strategy != null) {
            campaign.setStrategy(strategy);
        }
        campaignRepository.saveAndFlush(campaign);
        ClassificationResult row = new ClassificationResult(UUID.randomUUID(), event.getId(), campaign.getId(), failureClass,
                null, null, simulated("Razorpay error_reason '" + errorReason + "' on a fabricated webhook"), null);
        row.setTaxonomyVersion(classification.tableVersion());
        classificationResultRepository.saveAndFlush(row);

        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED,
                simulated("classification attached: " + failureClass + " (error_reason " + errorReason + ")"), "SYSTEM", null);
        steps.add(simulated("deterministic classification " + failureClass + " from error_reason '" + errorReason
                + "' (taxonomy " + classification.tableVersion() + ", no AI call)."));

        if (moveToEligible) {
            campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE,
                    simulated("campaign eligible for recovery evaluation (demo)"), "SYSTEM", null);
            steps.add("Campaign " + campaign.getId() + " moved to ELIGIBLE by the demo (" + simulated("eligibility, no strategy evaluation") + ").");
        }

        response.setPaymentId(paymentId);
        response.setCustomerIdHash(customerIdHash);
        response.setCampaignId(campaign.getId());
        return new Setup(campaign.getId(), paymentId);
    }

    private void transitionToActionPending(UUID merchantId, UUID campaignId, String reason) {
        campaignLifecycleService.transitionState(merchantId, campaignId, CampaignStatus.ACTION_PENDING, reason, "SYSTEM", null);
    }

    private void claimAndExecute(UUID merchantId, ActionIntent intent, DemoTriggerResponse response, List<String> steps) {
        // Same atomic claim primitive as the scheduler (M2): never claim by saving the entity.
        var claimed = actionIntentClaimService.claimSpecificIntent(intent.getId(), "demo-worker");
        if (claimed.isEmpty()) {
            steps.add("Claim not acquired (intent not claimable or owned by another worker); left to the scheduler.");
            return;
        }
        steps.add("Claim acquired through the real atomic claim (worker demo-worker); executing through the provider boundary: "
                + providerStep("provider call"));
        actionExecutionService.executeActionIntent(intent.getId(), "demo-worker", claimed.get().getClaimToken());
    }

    /** Delivers a captured webhook for the recovery payment, correlated to the intent (intent-specific, M2 invariant 15). */
    private void reconcileCapturedPayment(UUID merchantId, UUID campaignId, String paymentId, UUID intentId,
                                          String eventId, BigDecimal amount,
                                          DemoTriggerResponse response, List<String> steps) {
        String recoveryPaymentId = demoRef("pay_demo_recovery");
        WebhookEvent captured = buildWebhookEvent(merchantId, eventId, "payment.captured", recoveryPaymentId,
                amount, "captured", null, null, intentId);
        webhookEventRepository.saveAndFlush(captured);

        var result = paymentReconciliationService.reconcileWebhookEvent(captured);
        steps.add("Reconciliation result for " + eventId + ": " + result.getMessage());
        if (!result.isReconciled()) {
            throw new IllegalStateException("Reconciliation did not resolve the payment: " + result.getMessage());
        }
        auditService.logEvent(merchantId, campaignId, "DEMO_RECONCILED", "SYSTEM", null,
                simulated("captured-event reconciliation completed for payment " + paymentId));
    }

    private WebhookEvent buildWebhookEvent(UUID merchantId, String externalEventId, String eventType,
                                           String paymentId, BigDecimal amount, String status, String errorReason,
                                           String errorSource, UUID mendIntentId) {
        String payload = """
                {"entity":"event","event":"%s","payload":{"payment":{"entity":{"id":"%s","amount":%d,"currency":"INR","status":"%s","method":"card"%s%s}}}}
                """.formatted(
                eventType, paymentId,
                amount.movePointRight(2).longValueExact(), status,
                errorReason != null ? ",\"error_code\":\"BAD_REQUEST_ERROR\",\"error_reason\":\"" + errorReason
                        + "\",\"error_source\":\"" + errorSource + "\",\"error_description\":\"demo failure: " + errorReason + "\"" : "",
                mendIntentId != null ? ",\"notes\":{\"mend_intent_id\":\"" + mendIntentId + "\"}" : ""
        );

        // Invariant 3: a fabricated event is never stored as if Razorpay sent it.
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), externalEventId, eventType, DEMO_SOURCE, payload);
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

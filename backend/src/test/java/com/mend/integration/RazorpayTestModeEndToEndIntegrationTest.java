package com.mend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mend.client.AiClassificationClient;
import com.mend.client.RazorpayPaymentProviderClient;
import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.ai.AgentOrchestrationRequestDto;
import com.mend.dto.ai.AgentOrchestrationResponseDto;
import com.mend.dto.ai.ClassificationRequestDto;
import com.mend.dto.ai.ClassificationResponseDto;
import com.mend.dto.payment.PaymentExecutionRequest;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.scheduler.ActionScheduler;
import com.mend.security.RazorpaySignatureVerifier;
import com.mend.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class RazorpayTestModeEndToEndIntegrationTest {

    @Value("${razorpay.api.base-url:https://api.razorpay.com}")
    private String razorpayApiBaseUrl;

    @Value("${razorpay.webhook.secret:test_webhook_secret_key}")
    private String webhookSecret;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private CampaignLifecycleService campaignLifecycleService;

    @Autowired
    private RecoveryOrchestratorService recoveryOrchestratorService;

    @Autowired
    private ActionExecutionService actionExecutionService;

    @Autowired
    private ActionScheduler actionScheduler;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantConfigRepository merchantConfigRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private ClassificationResultRepository classificationResultRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    @Autowired
    private CampaignAttemptRepository campaignAttemptRepository;

    @Autowired
    private ComplianceDecisionRepository complianceDecisionRepository;

    @Autowired
    private AgentDecisionRecordRepository agentDecisionRecordRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @MockitoBean
    private AiClassificationClient aiClassificationClient;

    @MockitoBean
    private RazorpaySignatureVerifier razorpaySignatureVerifier;

    private Merchant testMerchant;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        actionIntentRepository.deleteAll();
        campaignAttemptRepository.deleteAll();
        complianceDecisionRepository.deleteAll();
        agentDecisionRecordRepository.deleteAll();
        classificationResultRepository.deleteAll();
        campaignRepository.deleteAll();
        webhookEventRepository.deleteAll();
        auditLogRepository.deleteAll();
        merchantConfigRepository.deleteAll();
        merchantRepository.deleteAll();

        testMerchant = merchantRepository.save(new Merchant(UUID.randomUUID(), "Test Merchant Operations"));
        merchantConfigRepository.save(new MerchantConfig(UUID.randomUUID(), testMerchant.getId()));

        when(razorpaySignatureVerifier.verifySignature(any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("1. Configuration Audit & Safety Check")
    void testConfigurationAudit() {
        assertThat(razorpayApiBaseUrl).isNotNull();
        assertThat(razorpayApiBaseUrl).contains("razorpay.com");
        MerchantConfig config = merchantConfigRepository.findByMerchantId(testMerchant.getId()).orElse(null);
        assertThat(config).isNotNull();
        assertThat(config.getMaxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("2. Complete End-to-End Razorpay TEST Mode Recovery Loop")
    void testFullRazorpayTestModeRecoveryLoop() throws Exception {
        // Step A: Ingest Razorpay Webhook (payment.failed)
        String externalEventId = "evt_rzp_test_" + UUID.randomUUID().toString().substring(0, 8);
        String paymentId = "pay_rzp_test_1001";
        String payload = String.format("""
                {
                  "entity": "event",
                  "account_id": "%s",
                  "event": "payment.failed",
                  "event_id": "%s",
                  "contains": ["payment"],
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "amount": 49900,
                        "currency": "INR",
                        "status": "failed",
                        "error_code": "BAD_REQUEST_ERROR",
                        "error_description": "insufficient funds in account",
                        "notes": {
                          "merchant_id": "%s"
                        }
                      }
                    }
                  },
                  "created_at": 1756980000
                }
                """, testMerchant.getId(), externalEventId, paymentId, testMerchant.getId());

        var webhookResponse = webhookService.processRazorpayWebhook(payload, "valid_test_signature");
        assertThat(webhookResponse).isNotNull();
        assertThat(webhookResponse.getStatus()).isEqualTo("ACCEPTED");

        // Verify PostgreSQL persistence
        WebhookEvent dbEvent = webhookEventRepository.findByExternalEventId(externalEventId).orElse(null);
        assertThat(dbEvent).isNotNull();
        assertThat(dbEvent.getEventType()).isEqualTo("payment.failed");

        // Step B: AI Failure Classification
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class))).thenReturn(
                new ClassificationResponseDto(
                        FailureClass.INSUFFICIENT_FUNDS,
                        new BigDecimal("0.95"),
                        RecommendedAction.RETRY_LATER,
                        "Diagnosed insufficient funds on attempt 1",
                        "v2.0-langgraph",
                        Map.of("detected_pattern", "INSUFFICIENT_FUNDS", "attempt_count", 0)
                )
        );

        ClassificationResult classification = classificationService.classifyAndPersist(dbEvent, "BAD_REQUEST_ERROR", "insufficient funds in account");
        assertThat(classification).isNotNull();
        assertThat(classification.getFailureClass()).isEqualTo("INSUFFICIENT_FUNDS");

        // Step C: Campaign Initialization
        Campaign campaign = campaignLifecycleService.processClassificationResult(dbEvent, classification);
        assertThat(campaign).isNotNull();
        assertThat(campaign.getCurrentState()).isEqualTo(CampaignStatus.ACTION_PENDING);
        assertThat(campaign.getMerchantId()).isEqualTo(testMerchant.getId());

        // Step D: Agent Orchestration (Attempt 1)
        when(aiClassificationClient.orchestrateAgent(any(AgentOrchestrationRequestDto.class))).thenReturn(
                new AgentOrchestrationResponseDto(
                        "trace_attempt_1",
                        testMerchant.getId().toString(),
                        campaign.getId().toString(),
                        paymentId,
                        "RETRY_PAYMENT",
                        new BigDecimal("0.95"),
                        "LOW",
                        "Attempt 1: Recommend RETRY_PAYMENT after exponential backoff.",
                        List.of("INSUFFICIENT_FUNDS_PATTERN", "FIRST_FAILURE_ATTEMPT"),
                        false,
                        "COMPLIANCE_ALLOWED",
                        "EXECUTE",
                        null,
                        1,
                        false
                )
        );

        ActionIntent actionIntent = recoveryOrchestratorService.orchestrateRecovery(testMerchant.getId(), campaign.getId());
        assertThat(actionIntent).isNotNull();
        assertThat(actionIntent.getActionType()).isEqualTo(ActionType.RETRY_PAYMENT.name());

        // Verify Agent Decision Persistence in DB
        List<AgentDecisionRecord> decisionRecords = agentDecisionRecordRepository.findByCampaignIdOrderByCreatedAtDesc(campaign.getId());
        assertThat(decisionRecords).isNotEmpty();
        assertThat(decisionRecords.get(0).getSelectedAction()).contains("RETRY_PAYMENT");
        assertThat(decisionRecords.get(0).getExecutionStatus()).isEqualTo("AUTHORIZED");

        // Step E: Scheduler Execution
        actionScheduler.pollAndExecuteDueActions();

        // Step F: Razorpay Provider Execution Simulation (Attempt 1 Fails)
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);

        mockServer.expect(requestTo(razorpayApiBaseUrl + "/v1/payments/" + paymentId + "/retry"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {
                          "error": {
                            "code": "BAD_REQUEST_ERROR",
                            "description": "Payment failed again due to low balance",
                            "source": "customer",
                            "step": "payment_authentication"
                          }
                        }
                        """));

        RazorpayPaymentProviderClient razorpayClient = new RazorpayPaymentProviderClient(
                razorpayApiBaseUrl,
                "rzp_test_key_123",
                "rzp_test_secret_456",
                restTemplate,
                merchantConfigRepository,
                objectMapper
        );

        PaymentExecutionRequest execReq = new PaymentExecutionRequest(
                testMerchant.getId(),
                campaign.getId(),
                actionIntent.getId(),
                paymentId,
                null,
                ActionType.RETRY_PAYMENT,
                1,
                actionIntent.getIdempotencyKey()
        );

        PaymentExecutionResult execResult = razorpayClient.executeAction(execReq);
        assertThat(execResult).isNotNull();
        assertThat(execResult.getStatus()).isEqualTo(PaymentExecutionStatus.FAILURE);

        // Step G: Outcome Reconciliation & Transition back to ELIGIBLE (Attempt 1 -> 1 attempt completed)
        campaign.setAttemptCount(2);
        campaign.setStrategy(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED.name());
        campaignRepository.save(campaign);

        campaignLifecycleService.transitionState(
                testMerchant.getId(),
                campaign.getId(),
                CampaignStatus.ELIGIBLE,
                "Attempt 1 failed. Re-eligible for agent evaluation.",
                "SYSTEM",
                null
        );

        // Step H: Agent Re-Evaluation (Attempt 2 - Dynamic Decision Shift)
        when(aiClassificationClient.orchestrateAgent(any(AgentOrchestrationRequestDto.class))).thenReturn(
                new AgentOrchestrationResponseDto(
                        "trace_attempt_2",
                        testMerchant.getId().toString(),
                        campaign.getId().toString(),
                        paymentId,
                        "REQUEST_CUSTOMER_ACTION",
                        new BigDecimal("0.92"),
                        "LOW",
                        "Attempt 2: Multiple insufficient balance failures detected. Shifting strategy from immediate retry to customer email dunning.",
                        List.of("PREVIOUS_RETRY_FAILED", "MULTIPLE_INSUFFICIENT_FUNDS"),
                        false,
                        "COMPLIANCE_ALLOWED",
                        "EXECUTE",
                        null,
                        2,
                        false
                )
        );

        ActionIntent actionIntent2 = recoveryOrchestratorService.orchestrateRecovery(testMerchant.getId(), campaign.getId());
        assertThat(actionIntent2).isNotNull();
        assertThat(actionIntent2.getActionType()).isEqualTo(ActionType.REQUEST_CUSTOMER_ACTION.name());

        // Verify Decision #2 in DB differs from Decision #1
        List<AgentDecisionRecord> updatedRecords = agentDecisionRecordRepository.findByCampaignIdOrderByCreatedAtDesc(campaign.getId());
        assertThat(updatedRecords.size()).isGreaterThanOrEqualTo(2);
        assertThat(updatedRecords.get(0).getSelectedAction()).contains("REQUEST_CUSTOMER_ACTION");

        // Step I: Customer Pays -> Final Terminal State Transition (RECOVERED)
        campaignLifecycleService.transitionState(
                testMerchant.getId(),
                campaign.getId(),
                CampaignStatus.RECOVERED,
                "Customer completed payment via dunning link",
                "RAZORPAY_WEBHOOK",
                null
        );

        Campaign finalCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertThat(finalCampaign.getCurrentState()).isEqualTo(CampaignStatus.RECOVERED);

        // Verify Agent stops executing on terminal state
        ActionIntent noOpIntent = recoveryOrchestratorService.orchestrateRecovery(testMerchant.getId(), campaign.getId());
        assertThat(noOpIntent).isNull();
    }

    @Test
    @DisplayName("3. Negative Path: Compliance Blocks ActionIntent Execution")
    void testComplianceRejectionPath() {
        Campaign campaign = new Campaign(UUID.randomUUID(), testMerchant.getId());
        campaign.setPaymentId("pay_compliance_blocked");
        campaign.setCustomerIdHash("cust_hash_1");
        campaign.setFailureClass(FailureClass.BANK_DECLINED.name());
        campaign.setStrategy(RecoveryStrategy.RETRY_LATER.name());
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        campaign.setAttemptCount(1);
        campaign.setConfidence(new BigDecimal("0.80"));
        campaign = campaignRepository.save(campaign);

        // Mock low confidence AI response to trigger compliance/human review block
        when(aiClassificationClient.orchestrateAgent(any(AgentOrchestrationRequestDto.class))).thenReturn(
                new AgentOrchestrationResponseDto(
                        "trace_compliance_block",
                        testMerchant.getId().toString(),
                        campaign.getId().toString(),
                        "pay_compliance_blocked",
                        "REVIEW_REQUIRED",
                        new BigDecimal("0.50"),
                        "HIGH",
                        "Low confidence. Require manual operational review.",
                        List.of("HIGH_RISK_BANK_DECLINE"),
                        true,
                        "HUMAN_REVIEW_REQUIRED",
                        "HUMAN_APPROVAL",
                        null,
                        1,
                        false
                )
        );

        ActionIntent intent = recoveryOrchestratorService.orchestrateRecovery(testMerchant.getId(), campaign.getId());
        // Intent should be null because human approval / review required stops automatic execution
        assertThat(intent).isNull();

        List<AgentDecisionRecord> records = agentDecisionRecordRepository.findByCampaignIdOrderByCreatedAtDesc(campaign.getId());
        assertThat(records).isNotEmpty();
        assertThat(records.get(0).getExecutionStatus()).isEqualTo("REVIEW_REQUIRED");

        // Verify campaign remains in safe state
        Campaign safeCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertThat(safeCampaign.getCurrentState()).isEqualTo(CampaignStatus.ELIGIBLE);
    }
}


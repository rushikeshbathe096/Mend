package com.mend.integration;

import com.mend.provider.mock.SyntheticDebitSimulator;
import com.mend.provider.razorpay.RazorpayHttp;
import com.mend.provider.razorpay.RazorpayPaymentProvider;
import com.mend.provider.razorpay.RazorpayProperties;
import com.mend.provider.razorpay.RazorpayProviderCapabilities;
import com.mend.provider.razorpay.RazorpayStubServer;
import com.mend.provider.razorpay.SingleTenantEnvCredentialsResolver;
import com.mend.service.ProviderActionDispatcher;
import com.mend.service.ProviderActionOutcome;
import com.mend.service.WebhookAmountResolver;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
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
    private AuditLogRepository auditLogRepository;

    @MockitoBean
    private RazorpaySignatureVerifier razorpaySignatureVerifier;

    private Merchant testMerchant;
    private ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.junit.jupiter.api.AfterEach
    void removeOwnRows() {
        com.mend.testsupport.TestRows.deleteMerchant(jdbc, testMerchant.getId());
    }

    @BeforeEach
    void setUp() {
        // Bug sweep item 6: was deleteAll() on ten shared tables (every class's rows, including the audit trail). The
        // scheduler polls across merchants, so other classes' claimable rows are parked; own rows go in removeOwnRows.
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING')");

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
                        "error_source": "customer",
                        "error_reason": "insufficient_funds",
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

        // Step B: Deterministic failure classification (M3 U2.2 behaviour change: was a mocked AI classification; the
        // fixture now carries Razorpay's documented error_reason / error_source, see ADR-003)
        ClassificationResult classification = classificationService.classifyAndPersist(dbEvent,
                com.mend.taxonomy.ProviderFailure.of("BAD_REQUEST_ERROR", "insufficient_funds", "customer"));
        assertThat(classification).isNotNull();
        assertThat(classification.getFailureClass()).isEqualTo("INSUFFICIENT_FUNDS");

        // Step C: Campaign Initialization
        Campaign campaign = campaignLifecycleService.processClassificationResult(dbEvent, classification);
        assertThat(campaign).isNotNull();
        assertThat(campaign.getCurrentState()).isEqualTo(CampaignStatus.ACTION_PENDING);
        assertThat(campaign.getMerchantId()).isEqualTo(testMerchant.getId());

        // Step D: Deterministic orchestration (attempt 1): INSUFFICIENT_FUNDS at attempt 0 -> RETRY_LATER -> RETRY_PAYMENT
        ActionIntent actionIntent = recoveryOrchestratorService.orchestrateRecovery(testMerchant.getId(), campaign.getId());
        assertThat(actionIntent).isNotNull();
        assertThat(actionIntent.getActionType()).isEqualTo(ActionType.RETRY_PAYMENT.name());

        // M3 U2.2 behaviour change (ADR-003): no agent decision is made or recorded (was: an AUTHORIZED agent record)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_decision_records WHERE campaign_id = ?", Long.class, campaign.getId())).isZero();

        // Step E: Scheduler Execution
        actionScheduler.pollAndExecuteDueActions();

        // Step F: Razorpay Provider Execution Simulation (Attempt 1 Fails)
        // M1 behaviour change: POST /v1/payments/{id}/retry does not exist in Razorpay (recorded fixture 10: 404). In
        // razorpay mode RETRY_PAYMENT is NotSupported locally with zero HTTP calls; the attempt still fails. See ADR-001.
        try (RazorpayStubServer razorpay = RazorpayStubServer.start()) {
            RazorpayProperties properties = new RazorpayProperties(new RazorpayProperties.Api(razorpay.baseUrl()),
                    "rzp_test_key_123", "rzp_test_secret_456", 1000, 1000, testMerchant.getId().toString());
            tools.jackson.databind.ObjectMapper providerMapper = new tools.jackson.databind.ObjectMapper();
            RazorpayPaymentProvider razorpayProvider = new RazorpayPaymentProvider(new RazorpayHttp(properties, providerMapper),
                    new SingleTenantEnvCredentialsResolver(properties), providerMapper);
            ProviderActionDispatcher razorpayDispatcher = new ProviderActionDispatcher(razorpayProvider,
                    new RazorpayProviderCapabilities(), mock(WebhookAmountResolver.class),
                    new StaticListableBeanFactory().getBeanProvider(SyntheticDebitSimulator.class));

            ProviderActionOutcome execResult = razorpayDispatcher.dispatch(actionIntent, campaign);

            assertThat(execResult).isEqualTo(new ProviderActionOutcome.NotSupported("RECURRING_DEBIT_NOT_ENABLED_UNTIL_M2"));
            assertThat(razorpay.requests()).isEmpty();
        }

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

        // Step H: Deterministic re-evaluation (attempt 2): INSUFFICIENT_FUNDS at attempt 2 -> CUSTOMER_ACTION_REQUIRED
        ActionIntent actionIntent2 = recoveryOrchestratorService.orchestrateRecovery(testMerchant.getId(), campaign.getId());
        assertThat(actionIntent2).isNotNull();
        assertThat(actionIntent2.getActionType()).isEqualTo(ActionType.REQUEST_CUSTOMER_ACTION.name());

        // M3 U2.2 behaviour change: the shift RETRY_PAYMENT -> REQUEST_CUSTOMER_ACTION comes from the deterministic strategy
        // table (attempt count), not from an agent; still no agent record (was: two agent records)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_decision_records WHERE campaign_id = ?", Long.class, campaign.getId())).isZero();

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
        campaign.setFailureClass("BANK_DECLINED");   // a pre-M3 (legacy) class name
        campaign.setStrategy(RecoveryStrategy.RETRY_LATER.name());
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        campaign.setAttemptCount(1);
        campaign.setConfidence(new BigDecimal("0.80"));
        campaign = campaignRepository.save(campaign);

        // M3 U2.2 behaviour change (ADR-003 §4): was a mocked low-confidence agent asking for review. Now the block is
        // deterministic: BANK_DECLINED is a pre-M3 (AI-era) class with no documented mapping, so compliance fails closed.
        ActionIntent intent = recoveryOrchestratorService.orchestrateRecovery(testMerchant.getId(), campaign.getId());
        assertThat(intent).isNull();
        assertThat(complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId()))
                .first().satisfies(d -> {
                    assertThat(d.getStatus()).isEqualTo(ComplianceStatus.COMPLIANCE_BLOCKED);
                    assertThat(d.getReason()).isEqualTo(ComplianceReason.UNKNOWN_CLASSIFICATION);   // legacy class fails closed
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_decision_records WHERE campaign_id = ?", Long.class, campaign.getId())).isZero();

        // Verify campaign remains in safe state
        Campaign safeCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertThat(safeCampaign.getCurrentState()).isEqualTo(CampaignStatus.ELIGIBLE);
    }
}


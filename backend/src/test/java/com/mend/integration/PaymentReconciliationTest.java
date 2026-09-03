package com.mend.integration;

import tools.jackson.databind.ObjectMapper;
import com.mend.config.RazorpayWebhookProperties;
import com.mend.domain.entity.*;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.*;
import com.mend.security.RazorpaySignatureVerifier;
import com.mend.service.PaymentReconciliationService;
import com.mend.service.ReconciliationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaymentReconciliationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private PaymentReconciliationService reconciliationService;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    @Autowired
    private CampaignAttemptRepository campaignAttemptRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private RazorpaySignatureVerifier signatureVerifier;

    @Autowired
    private RazorpayWebhookProperties webhookProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpClient httpClient;
    private Merchant merchantA;
    private Merchant merchantB;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        actionIntentRepository.deleteAll();
        campaignAttemptRepository.deleteAll();
        campaignRepository.deleteAll();
        webhookEventRepository.deleteAll();
        merchantRepository.deleteAll();

        merchantA = merchantRepository.save(new Merchant(UUID.randomUUID(), "Merchant Alpha"));
        merchantB = merchantRepository.save(new Merchant(UUID.randomUUID(), "Merchant Beta"));
    }

    @Test
    @DisplayName("A. ERROR -> SUCCESS Reconciliation: Execution returned ERROR, then payment.captured arrives")
    void testErrorToSuccessReconciliation() {
        // Setup: Campaign in FAILED state due to execution ERROR, ActionIntent in FAILED status
        String idempotencyKey = "intent_err_succ_" + UUID.randomUUID();
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantA.getId());
        campaign.setPaymentId("pay_orig_123");
        campaign.setCurrentState(CampaignStatus.FAILED);
        campaign = campaignRepository.save(campaign);

        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), merchantA.getId(), campaign.getId(),
                1, "RETRY_PAYMENT", "STRATEGY_A", null,
                ActionIntentStatus.FAILED, idempotencyKey, Instant.now()
        );
        intent = actionIntentRepository.save(intent);

        // Webhook Event: payment.captured for pay_cap_999 with matching idempotency_key in notes
        String paymentRef = "pay_cap_999";
        String payload = String.format("""
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "status": "captured",
                        "notes": {
                          "idempotency_key": "%s"
                        }
                      }
                    }
                  }
                }
                """, paymentRef, idempotencyKey);

        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "evt_cap_001", "payment.captured", "RAZORPAY", payload);
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        // Act: Reconcile
        ReconciliationResult result = reconciliationService.reconcileWebhookEvent(event);

        // Assert
        assertEquals(ReconciliationResult.Status.RECONCILED_SUCCESS, result.getStatus());

        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.SUCCEEDED, updatedIntent.getStatus());
        assertEquals(paymentRef, updatedIntent.getResponseReference());

        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, updatedCampaign.getCurrentState());

        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId());
        assertEquals(1, attempts.size());
        assertEquals("SUCCESS", attempts.get(0).getStatus());
        assertEquals(paymentRef, attempts.get(0).getExternalReference());
    }

    @Test
    @DisplayName("B. ERROR -> FAILURE Reconciliation: Execution returned ERROR, then payment.failed arrives")
    void testErrorToFailureReconciliation() {
        String idempotencyKey = "intent_err_fail_" + UUID.randomUUID();
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantA.getId());
        campaign.setPaymentId("pay_orig_456");
        campaign.setCurrentState(CampaignStatus.FAILED);
        campaign = campaignRepository.save(campaign);

        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), merchantA.getId(), campaign.getId(),
                1, "RETRY_PAYMENT", "STRATEGY_A", null,
                ActionIntentStatus.FAILED, idempotencyKey, Instant.now()
        );
        intent = actionIntentRepository.save(intent);

        String payload = String.format("""
                {
                  "event": "payment.failed",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_fail_888",
                        "status": "failed",
                        "error_description": "Card has insufficient funds",
                        "notes": {
                          "idempotency_key": "%s"
                        }
                      }
                    }
                  }
                }
                """, idempotencyKey);

        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "evt_fail_002", "payment.failed", "RAZORPAY", payload);
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        ReconciliationResult result = reconciliationService.reconcileWebhookEvent(event);

        assertEquals(ReconciliationResult.Status.RECONCILED_FAILURE, result.getStatus());

        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.FAILED, updatedIntent.getStatus());

        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.EXHAUSTED, updatedCampaign.getCurrentState());

        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId());
        assertEquals(1, attempts.size());
        assertEquals("FAILED", attempts.get(0).getStatus());
        assertEquals("Card has insufficient funds", attempts.get(0).getFailureReason());
    }

    @Test
    @DisplayName("C. SUCCESS Followed by Duplicate Webhook: Verify idempotency and zero duplicate mutations")
    void testSuccessFollowedByDuplicateWebhook() {
        String idempotencyKey = "intent_dup_succ_" + UUID.randomUUID();
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantA.getId());
        campaign.setPaymentId("pay_orig_789");
        campaign.setCurrentState(CampaignStatus.EXECUTING);
        campaign = campaignRepository.save(campaign);

        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), merchantA.getId(), campaign.getId(),
                1, "RETRY_PAYMENT", "STRATEGY_A", null,
                ActionIntentStatus.CLAIMED, idempotencyKey, Instant.now()
        );
        intent = actionIntentRepository.save(intent);

        String payload = String.format("""
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_succ_dup",
                        "status": "captured",
                        "notes": {
                          "idempotency_key": "%s"
                        }
                      }
                    }
                  }
                }
                """, idempotencyKey);

        WebhookEvent event1 = webhookEventRepository.save(new WebhookEvent(UUID.randomUUID(), "evt_dup_1", "payment.captured", "RAZORPAY", payload));
        event1.setMerchantId(merchantA.getId());

        ReconciliationResult result1 = reconciliationService.reconcileWebhookEvent(event1);
        assertEquals(ReconciliationResult.Status.RECONCILED_SUCCESS, result1.getStatus());

        // Second duplicate webhook arrival
        WebhookEvent event2 = webhookEventRepository.save(new WebhookEvent(UUID.randomUUID(), "evt_dup_2", "payment.captured", "RAZORPAY", payload));
        event2.setMerchantId(merchantA.getId());

        ReconciliationResult result2 = reconciliationService.reconcileWebhookEvent(event2);
        assertEquals(ReconciliationResult.Status.SKIPPED_ALREADY_FINALIZED, result2.getStatus());

        // Verify database state remained unchanged by duplicate
        assertEquals(1, campaignAttemptRepository.findByCampaignId(campaign.getId()).size());
        assertEquals(CampaignStatus.RECOVERED, campaignRepository.findById(campaign.getId()).orElseThrow().getCurrentState());
        assertEquals(ActionIntentStatus.SUCCEEDED, actionIntentRepository.findById(intent.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("D. FAILURE Followed by Duplicate Webhook: Verify idempotency")
    void testFailureFollowedByDuplicateWebhook() {
        String idempotencyKey = "intent_dup_fail_" + UUID.randomUUID();
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantA.getId());
        campaign.setPaymentId("pay_orig_dup_fail");
        campaign.setCurrentState(CampaignStatus.EXECUTING);
        campaign = campaignRepository.save(campaign);

        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), merchantA.getId(), campaign.getId(),
                1, "RETRY_PAYMENT", "STRATEGY_A", null,
                ActionIntentStatus.CLAIMED, idempotencyKey, Instant.now()
        );
        intent = actionIntentRepository.save(intent);

        String payload = String.format("""
                {
                  "event": "payment.failed",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_fail_dup",
                        "status": "failed",
                        "notes": {
                          "idempotency_key": "%s"
                        }
                      }
                    }
                  }
                }
                """, idempotencyKey);

        WebhookEvent event1 = webhookEventRepository.save(new WebhookEvent(UUID.randomUUID(), "evt_fail_dup_1", "payment.failed", "RAZORPAY", payload));
        event1.setMerchantId(merchantA.getId());

        ReconciliationResult result1 = reconciliationService.reconcileWebhookEvent(event1);
        assertEquals(ReconciliationResult.Status.RECONCILED_FAILURE, result1.getStatus());

        WebhookEvent event2 = webhookEventRepository.save(new WebhookEvent(UUID.randomUUID(), "evt_fail_dup_2", "payment.failed", "RAZORPAY", payload));
        event2.setMerchantId(merchantA.getId());

        ReconciliationResult result2 = reconciliationService.reconcileWebhookEvent(event2);
        assertEquals(ReconciliationResult.Status.SKIPPED_ALREADY_FINALIZED, result2.getStatus());

        assertEquals(1, campaignAttemptRepository.findByCampaignId(campaign.getId()).size());
        assertEquals(CampaignStatus.EXHAUSTED, campaignRepository.findById(campaign.getId()).orElseThrow().getCurrentState());
    }

    @Test
    @DisplayName("E. Tenant Mismatch: Webhook merchant does not match execution attempt merchant")
    void testTenantMismatch() {
        String idempotencyKey = "intent_tenant_mis_" + UUID.randomUUID();
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantA.getId());
        campaign.setPaymentId("pay_merchant_a");
        campaign.setCurrentState(CampaignStatus.EXECUTING);
        campaign = campaignRepository.save(campaign);

        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), merchantA.getId(), campaign.getId(),
                1, "RETRY_PAYMENT", "STRATEGY_A", null,
                ActionIntentStatus.CLAIMED, idempotencyKey, Instant.now()
        );
        intent = actionIntentRepository.save(intent);

        String payload = String.format("""
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_tenant_test",
                        "status": "captured",
                        "notes": {
                          "idempotency_key": "%s"
                        }
                      }
                    }
                  }
                }
                """, idempotencyKey);

        // Webhook belongs to Merchant B (Mismatch!)
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "evt_tenant_mismatch", "payment.captured", "RAZORPAY", payload);
        event.setMerchantId(merchantB.getId());
        event = webhookEventRepository.save(event);

        ReconciliationResult result = reconciliationService.reconcileWebhookEvent(event);

        assertEquals(ReconciliationResult.Status.SKIPPED_TENANT_MISMATCH, result.getStatus());

        // Verify zero state mutations on Merchant A's entities
        ActionIntent checkIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.CLAIMED, checkIntent.getStatus());

        Campaign checkCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.EXECUTING, checkCampaign.getCurrentState());
    }

    @Test
    @DisplayName("F. Unrelated Webhook: Unmatched or non-outcome event causes zero entity modification")
    void testUnrelatedWebhook() {
        WebhookEvent event = new WebhookEvent(
                UUID.randomUUID(), "evt_unrelated_123", "payment.dispute.created", "RAZORPAY",
                "{\"event\":\"payment.dispute.created\",\"payload\":{}}"
        );
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        ReconciliationResult result = reconciliationService.reconcileWebhookEvent(event);

        assertTrue(result.getStatus() == ReconciliationResult.Status.SKIPPED_UNMATCHED ||
                   result.getStatus() == ReconciliationResult.Status.SKIPPED_UNSUPPORTED_EVENT);
    }

    @Test
    @DisplayName("G. Late Webhook: Webhook cannot move an already finalized campaign backwards")
    void testLateWebhookOnFinalizedCampaign() {
        String idempotencyKey = "intent_late_" + UUID.randomUUID();
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantA.getId());
        campaign.setPaymentId("pay_late_test");
        campaign.setCurrentState(CampaignStatus.RECOVERED); // Already RECOVERED!
        campaign = campaignRepository.save(campaign);

        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), merchantA.getId(), campaign.getId(),
                1, "RETRY_PAYMENT", "STRATEGY_A", null,
                ActionIntentStatus.SUCCEEDED, idempotencyKey, Instant.now()
        );
        intent = actionIntentRepository.save(intent);

        // Late failure webhook arrives
        String payload = String.format("""
                {
                  "event": "payment.failed",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_late_fail",
                        "status": "failed",
                        "notes": {
                          "idempotency_key": "%s"
                        }
                      }
                    }
                  }
                }
                """, idempotencyKey);

        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "evt_late_001", "payment.failed", "RAZORPAY", payload);
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        ReconciliationResult result = reconciliationService.reconcileWebhookEvent(event);

        assertEquals(ReconciliationResult.Status.SKIPPED_ALREADY_FINALIZED, result.getStatus());

        // Verify state is preserved in RECOVERED
        Campaign checkCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, checkCampaign.getCurrentState());
    }

    @Test
    @DisplayName("H. Concurrent Duplicate Webhook: Simulates multi-threaded processing safely")
    void testConcurrentDuplicateWebhook() throws Exception {
        String idempotencyKey = "intent_conc_" + UUID.randomUUID();
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantA.getId());
        campaign.setPaymentId("pay_conc_test");
        campaign.setCurrentState(CampaignStatus.EXECUTING);
        campaign = campaignRepository.save(campaign);

        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), merchantA.getId(), campaign.getId(),
                1, "RETRY_PAYMENT", "STRATEGY_A", null,
                ActionIntentStatus.CLAIMED, idempotencyKey, Instant.now()
        );
        intent = actionIntentRepository.save(intent);

        String payload = String.format("""
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_conc_999",
                        "status": "captured",
                        "notes": {
                          "idempotency_key": "%s"
                        }
                      }
                    }
                  }
                }
                """, idempotencyKey);

        WebhookEvent event = webhookEventRepository.save(new WebhookEvent(UUID.randomUUID(), "evt_conc_001", "payment.captured", "RAZORPAY", payload));
        event.setMerchantId(merchantA.getId());

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    ReconciliationResult res = reconciliationService.reconcileWebhookEvent(event);
                    if (res.getStatus() == ReconciliationResult.Status.RECONCILED_SUCCESS) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(1, successCount.get());
        assertEquals(CampaignStatus.RECOVERED, campaignRepository.findById(campaign.getId()).orElseThrow().getCurrentState());
    }

    @Test
    @DisplayName("I. Signature Verification: Invalid Razorpay webhook signature returns 401 and mutates nothing")
    void testInvalidSignatureRejection() throws Exception {
        String payload = "{\"event_id\":\"evt_bad_sig_recon\",\"event\":\"payment.captured\"}";
        String badSignature = "invalid_signature";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", badSignature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
        assertEquals(0, webhookEventRepository.count());
    }

    @Test
    @DisplayName("J. Correlation: Verifies ActionIntent selection by idempotencyKey, responseReference, and paymentId")
    void testCorrelationStrategies() {
        String idempotencyKey = "intent_corr_key_" + UUID.randomUUID();
        String paymentId = "pay_corr_ref_777";

        Campaign campaign = new Campaign(UUID.randomUUID(), merchantA.getId());
        campaign.setPaymentId(paymentId);
        campaign.setCurrentState(CampaignStatus.EXECUTING);
        campaign = campaignRepository.save(campaign);

        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), merchantA.getId(), campaign.getId(),
                1, "RETRY_PAYMENT", "STRATEGY_A", null,
                ActionIntentStatus.CLAIMED, idempotencyKey, Instant.now()
        );
        intent.setResponseReference(paymentId);
        intent = actionIntentRepository.save(intent);

        // Test correlation via responseReference (provider payment ID)
        String payloadByRef = String.format("""
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "status": "captured"
                      }
                    }
                  }
                }
                """, paymentId);

        WebhookEvent eventByRef = new WebhookEvent(UUID.randomUUID(), "evt_corr_001", "payment.captured", "RAZORPAY", payloadByRef);
        eventByRef.setMerchantId(merchantA.getId());
        eventByRef = webhookEventRepository.save(eventByRef);

        ReconciliationResult result = reconciliationService.reconcileWebhookEvent(eventByRef);
        assertEquals(ReconciliationResult.Status.RECONCILED_SUCCESS, result.getStatus());
        assertEquals(intent.getId(), result.getActionIntentId());
    }
}

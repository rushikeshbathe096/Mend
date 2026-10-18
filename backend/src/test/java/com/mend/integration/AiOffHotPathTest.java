package com.mend.integration;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.*;
import com.mend.events.WebhookEventMessage;
import com.mend.service.CampaignLifecycleService;
import com.mend.service.ClassificationService;
import com.mend.service.DefaultEventProcessingService;
import com.mend.service.RecoveryOrchestratorService;
import com.mend.testsupport.TestRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 U2.2 proof (ADR-003 §2): the payment path (webhook → taxonomy → strategy → compliance → intent) makes no
 * ai-service call. The ai-service address points at a non-answering host, so a call would block for the 2 s connect
 * timeout. Before M3 this made classification fall back to a fabricated UNKNOWN / 0.30 and the campaign EXHAUSTED; now the
 * flow runs to RECOVERED like with a healthy ai-service, quickly.
 * M3 U2.3: the ai-service classification client is deleted (it was wrapped in a spy here to prove zero interactions);
 * the proof is now that no bean of an AI client or agent type exists in the application context at all.
 */
@SpringBootTest(properties = {"mend.ai.base-url=http://10.255.255.1:8000", "mend.ai.connect-timeout-ms=2000", "mend.ai.read-timeout-ms=3000"})
class AiOffHotPathTest extends AbstractIntegrationTest {

    @Autowired private DefaultEventProcessingService eventProcessing;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private CampaignRepository campaigns;
    @Autowired private ActionIntentRepository intents;
    @Autowired private ClassificationResultRepository classifications;
    @Autowired private MerchantRepository merchants;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.mend.scheduler.ActionScheduler actionScheduler;
    @Autowired private org.springframework.context.ApplicationContext context;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING')");
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "AI-off merchant"));
    }

    @AfterEach
    void tearDown() {
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    private WebhookEvent failedPayment(String reason) {
        String paymentId = "pay_aioff" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String externalEventId = "payment.failed_" + paymentId;
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), externalEventId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"amount\":49900,"
                        + "\"currency\":\"INR\",\"status\":\"failed\",\"error_code\":\"BAD_REQUEST_ERROR\",\"error_source\":\"customer\","
                        + "\"error_reason\":\"" + reason + "\"}}}}");
        event.setMerchantId(merchantId);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        return webhooks.save(event);
    }

    @Test
    @DisplayName("ai-service unreachable: a documented failure still classifies, creates the intent, and never waits on AI timeouts")
    void paymentFlowWithAiServiceUnreachable() {
        WebhookEvent event = failedPayment("insufficient_funds");

        Instant start = Instant.now();
        assertTrue(eventProcessing.processEvent(new WebhookEventMessage(event.getId(), event.getExternalEventId(), merchantId,
                event.getEventType(), Instant.now(), null)));
        Duration elapsed = Duration.between(start, Instant.now());

        ClassificationResult classification = classifications.findByEventId(event.getId()).orElseThrow();
        assertEquals("INSUFFICIENT_FUNDS", classification.getFailureClass(), "not a fabricated UNKNOWN fallback");
        assertNull(classification.getConfidence());
        Campaign campaign = campaigns.findByMerchantIdAndPaymentId(merchantId, event.getExternalEventId()).orElseThrow();
        assertEquals(CampaignStatus.ACTION_PENDING, campaign.getCurrentState());
        List<ActionIntent> created = intents.findByCampaignId(campaign.getId());
        assertEquals(1, created.size());
        assertEquals("RETRY_PAYMENT", created.get(0).getActionType());
        assertTrue(elapsed.compareTo(Duration.ofMillis(1500)) < 0, "no AI connect/read timeout on the path: " + elapsed);

        // ...and execution runs to RECOVERED (mock provider) without the AI service either.
        jdbc.update("UPDATE action_intents SET status = 'READY', scheduled_at = now() - interval '1 second' WHERE id = ?", created.get(0).getId());
        actionScheduler.pollAndExecuteDueActions();
        assertEquals(CampaignStatus.RECOVERED, campaigns.findById(campaign.getId()).orElseThrow().getCurrentState());
        for (String bean : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(bean);
            assertFalse(type != null && isAiType(org.springframework.util.ClassUtils.getUserClass(type)), "AI bean in context: " + bean);
        }
    }

    @Test
    @DisplayName("No payment-path service can reach the AI client or an agent engine (constructor and field types)")
    void paymentPathHasNoAiDependency() {
        for (Class<?> type : List.of(DefaultEventProcessingService.class, ClassificationService.class,
                CampaignLifecycleService.class, RecoveryOrchestratorService.class, com.mend.service.RecoveryStrategyService.class,
                com.mend.service.ComplianceService.class, com.mend.service.ActionIntentService.class,
                com.mend.service.HumanApprovalService.class, com.mend.service.ActionExecutionService.class,
                com.mend.service.ActionIntentClaimService.class, com.mend.service.ProviderActionDispatcher.class,
                com.mend.service.ExecutionReconciler.class, com.mend.service.DefaultPaymentReconciliationService.class,
                com.mend.scheduler.ActionScheduler.class, com.mend.compliance.ComplianceEngine.class,
                com.mend.strategy.RecoveryStrategyEngine.class)) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    assertFalse(isAiType(parameter), type.getSimpleName() + " constructor takes " + parameter.getName());
                }
            }
            for (Field field : type.getDeclaredFields()) {
                assertFalse(isAiType(field.getType()), type.getSimpleName() + " has field " + field.getName() + " of " + field.getType().getName());
            }
        }
    }

    private static boolean isAiType(Class<?> type) {
        String name = type.getName();
        return name.startsWith("com.mend.client.") || name.startsWith("com.mend.agent.") || name.startsWith("com.mend.dto.ai.")
                || type.getSimpleName().contains("Classification" + "Client");
    }
}

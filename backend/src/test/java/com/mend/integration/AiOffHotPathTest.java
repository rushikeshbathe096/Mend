package com.mend.integration;

import com.mend.client.AiClassificationClient;
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
 * ai-service call. The real {@link AiClassificationClient} is pointed at an address that refuses connections. Before M3
 * this made classification fall back to a fabricated UNKNOWN / 0.30 and the campaign EXHAUSTED; now the flow is the same
 * as with a healthy ai-service, and finishes without waiting for the 2 s / 3 s AI timeouts.
 */
@SpringBootTest(properties = {"mend.ai.base-url=http://127.0.0.1:9", "mend.ai.connect-timeout-ms=2000", "mend.ai.read-timeout-ms=3000"})
class AiOffHotPathTest extends AbstractIntegrationTest {

    @Autowired private DefaultEventProcessingService eventProcessing;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private CampaignRepository campaigns;
    @Autowired private ActionIntentRepository intents;
    @Autowired private ClassificationResultRepository classifications;
    @Autowired private MerchantRepository merchants;
    @Autowired private JdbcTemplate jdbc;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
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
    }

    @Test
    @DisplayName("No payment-path service can reach the AI client or an agent engine (constructor and field types)")
    void paymentPathHasNoAiDependency() {
        for (Class<?> type : List.of(DefaultEventProcessingService.class, ClassificationService.class,
                CampaignLifecycleService.class, RecoveryOrchestratorService.class)) {
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
        return AiClassificationClient.class.isAssignableFrom(type) || name.startsWith("com.mend.agent.")
                || name.startsWith("com.mend.dto.ai.");
    }
}

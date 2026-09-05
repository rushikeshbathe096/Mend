package com.mend.integration;

import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.OutboxEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.OutboxEventRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.security.RazorpaySignatureVerifier;
import com.mend.service.WebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "mend.outbox.publisher.scheduled-enabled=false"
})
class WebhookIngestionRedisUnavailableIntegrationTest {

    @Autowired private WebhookService webhookService;
    @Autowired private RazorpaySignatureVerifier signatureVerifier;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;

    @Test
    void unavailableRedisDoesNotRollBackWebhookOrOutbox() {
        Merchant merchant = new Merchant(UUID.randomUUID(), "Redis Unavailable Outbox Test");
        merchant.setExternalReference("acc_redis_unavailable_" + UUID.randomUUID());
        merchant = merchantRepository.saveAndFlush(merchant);
        String externalEventId = "evt_redis_unavailable_" + UUID.randomUUID();
        String payload = "{\"event_id\":\"" + externalEventId
                + "\",\"event\":\"payment.failed\",\"account_id\":\""
                + merchant.getExternalReference() + "\"}";

        webhookService.processRazorpayWebhook(
                payload, signatureVerifier.calculateHmacSha256(payload, "default_test_webhook_secret"));

        WebhookEvent webhook = webhookEventRepository.findByExternalEventId(externalEventId).orElseThrow();
        OutboxEvent outbox = outboxEventRepository
                .findByAggregateTypeAndAggregateIdAndEventType(
                        OutboxEvent.WEBHOOK_AGGREGATE_TYPE,
                        webhook.getId(),
                        OutboxEvent.WEBHOOK_VERIFIED_EVENT_TYPE)
                .orElseThrow();
        assertEquals(WebhookPublishStatus.PENDING, webhook.getPublishStatus());
        assertEquals(OutboxEventStatus.PENDING, outbox.getStatus());
        assertEquals(0, outbox.getAttempts());
    }
}

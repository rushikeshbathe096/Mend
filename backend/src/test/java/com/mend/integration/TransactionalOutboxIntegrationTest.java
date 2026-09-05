package com.mend.integration;

import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.OutboxEventStatus;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.OutboxEventRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.WebhookResponseDto;
import com.mend.events.WebhookEventMessage;
import com.mend.exception.WebhookSignatureException;
import com.mend.config.RedisStreamProperties;
import com.mend.consumer.RedisWebhookEventConsumer;
import com.mend.publisher.RedisWebhookEventPublisher;
import com.mend.service.EventProcessingService;
import com.mend.service.OutboxPublisherService;
import com.mend.service.WebhookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Range;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionalOutboxIntegrationTest extends AbstractIntegrationTest {

    private static final String TEST_WEBHOOK_SECRET = "default_test_webhook_secret";

    @Autowired private WebhookService webhookService;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private ActionIntentRepository actionIntentRepository;
    @Autowired private EventProcessingService eventProcessingService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RedisWebhookEventPublisher redisPublisher;
    @Autowired private RedisWebhookEventConsumer redisConsumer;
    @Autowired private OutboxPublisherService outboxPublisherService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RedisStreamProperties redisStreamProperties;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void ingestionCommitsWebhookAndOutboxWithoutContactingRedis() throws Exception {
        Merchant merchant = createMerchant("atomic");
        String externalEventId = "evt_outbox_atomic_" + UUID.randomUUID();
        String payload = payload(externalEventId, merchant.getExternalReference());
        MDC.put("traceId", "trace-atomic-123");
        MDC.put("correlationId", "correlation-atomic-456");

        WebhookResponseDto response = webhookService.processRazorpayWebhook(payload, sign(payload));

        assertEquals("ACCEPTED", response.getStatus());
        WebhookEvent webhook = webhookEventRepository.findByExternalEventId(externalEventId).orElseThrow();
        OutboxEvent outbox = outboxEventRepository
                .findByAggregateTypeAndAggregateIdAndEventType(
                        OutboxEvent.WEBHOOK_AGGREGATE_TYPE,
                        webhook.getId(),
                        OutboxEvent.WEBHOOK_VERIFIED_EVENT_TYPE)
                .orElseThrow();
        assertEquals(WebhookEventStatus.VERIFIED, webhook.getProcessingStatus());
        assertEquals(WebhookPublishStatus.PENDING, webhook.getPublishStatus());
        assertEquals(OutboxEventStatus.PENDING, outbox.getStatus());
        assertEquals(webhook.getMerchantId(), outbox.getMerchantId());
        assertEquals("trace-atomic-123", outbox.getTraceId());
        assertEquals("correlation-atomic-456", outbox.getCorrelationId());
        assertTrue(outbox.getPayload().contains(webhook.getPayloadHash()));
    }

    @Test
    void databaseRollbackRemovesBothWebhookAndOutbox() {
        Merchant merchant = createMerchant("rollback");
        UUID webhookId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThrows(IntentionalRollback.class, () -> template.executeWithoutResult(status -> {
            WebhookEvent webhook = new WebhookEvent(webhookId, "evt_rollback_" + UUID.randomUUID(), "payment.failed");
            webhook.setMerchantId(merchant.getId());
            webhook.setSource("RAZORPAY");
            webhook.setPayloadHash("rollback-hash");
            webhook.setProcessingStatus(WebhookEventStatus.VERIFIED);
            webhookEventRepository.saveAndFlush(webhook);

            OutboxEvent outbox = OutboxEvent.pendingWebhook(
                    webhookId, merchant.getId(), "{}", "trace-rollback", "correlation-rollback", Instant.now());
            outbox.setId(outboxId);
            outboxEventRepository.saveAndFlush(outbox);
            throw new IntentionalRollback();
        }));

        assertFalse(webhookEventRepository.existsById(webhookId));
        assertFalse(outboxEventRepository.existsById(outboxId));
    }

    @Test
    void duplicateConsumerDeliveryDoesNotCreateDuplicateCampaignOrAction() {
        Merchant merchant = createMerchant("duplicate");
        WebhookEvent webhook = new WebhookEvent(
                UUID.randomUUID(), "evt_duplicate_delivery_" + UUID.randomUUID(), "payment.failed");
        webhook.setMerchantId(merchant.getId());
        webhook.setSource("RAZORPAY");
        webhook.setPayloadHash("duplicate-hash");
        webhook.setRawPayload("{\"event\":\"payment.failed\",\"error_code\":\"unknown_test_failure\"}");
        webhook.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhook = webhookEventRepository.saveAndFlush(webhook);
        WebhookEventMessage message = new WebhookEventMessage(
                webhook.getId(), webhook.getExternalEventId(), merchant.getId(), webhook.getEventType(),
                webhook.getReceivedAt(), webhook.getPayloadHash());

        assertTrue(eventProcessingService.processEvent(message));
        long campaignsAfterFirst = campaignRepository.countByMerchantId(merchant.getId());
        long actionsAfterFirst = actionIntentRepository.countByMerchantId(merchant.getId());
        assertTrue(eventProcessingService.processEvent(message));

        assertEquals(1, campaignsAfterFirst);
        assertEquals(campaignsAfterFirst, campaignRepository.countByMerchantId(merchant.getId()));
        assertEquals(actionsAfterFirst, actionIntentRepository.countByMerchantId(merchant.getId()));
    }

    @Test
    void crashWindowRedeliveryPreservesMetadataAndRemainsConsumerIdempotent() throws Exception {
        redisTemplate.delete(redisStreamProperties.getStreamName());
        Merchant merchant = createMerchant("crash-window");
        String externalEventId = "evt_crash_window_" + UUID.randomUUID();
        String payload = payload(externalEventId, merchant.getExternalReference());
        MDC.put("traceId", "trace-crash-window");
        MDC.put("correlationId", "correlation-crash-window");
        webhookService.processRazorpayWebhook(payload, sign(payload));

        WebhookEvent webhook = webhookEventRepository.findByExternalEventId(externalEventId).orElseThrow();
        OutboxEvent outbox = outboxEventRepository
                .findByAggregateTypeAndAggregateIdAndEventType(
                        OutboxEvent.WEBHOOK_AGGREGATE_TYPE,
                        webhook.getId(),
                        OutboxEvent.WEBHOOK_VERIFIED_EVENT_TYPE)
                .orElseThrow();

        // First XADD succeeds, then the process is modelled as crashing before
        // markPublished. On restart the same committed outbox row is published again.
        redisPublisher.publishOutbox(outbox);
        redisPublisher.publishOutbox(outbox);
        assertEquals(OutboxEventStatus.PENDING,
                outboxEventRepository.findById(outbox.getId()).orElseThrow().getStatus());

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(redisStreamProperties.getStreamName(), Range.unbounded());
        assertNotNull(records);
        long matchingRecords = records.stream()
                .filter(record -> outbox.getId().toString().equals(record.getValue().get("outboxId")))
                .peek(record -> {
                    assertEquals("trace-crash-window", record.getValue().get("traceId"));
                    assertEquals("correlation-crash-window", record.getValue().get("correlationId"));
                    assertEquals(merchant.getId().toString(), record.getValue().get("merchantId"));
                    assertEquals(webhook.getPayloadHash(), record.getValue().get("payloadHash"));
                })
                .count();
        assertEquals(2, matchingRecords);

        redisConsumer.initConsumerGroup();
        assertTrue(redisConsumer.pollAndProcessMessages() >= 2);
        assertEquals(1, campaignRepository.countByMerchantId(merchant.getId()));
        long actionCount = actionIntentRepository.countByMerchantId(merchant.getId());

        // The normal publisher later retries the still-PENDING row and records
        // PUBLISHED only after another successful Redis call.
        assertTrue(outboxPublisherService.publishDueEvents() >= 1);
        OutboxEvent published = outboxEventRepository.findById(outbox.getId()).orElseThrow();
        WebhookEvent publishedWebhook = webhookEventRepository.findById(webhook.getId()).orElseThrow();
        assertEquals(OutboxEventStatus.PUBLISHED, published.getStatus());
        assertEquals(WebhookPublishStatus.PUBLISHED, publishedWebhook.getPublishStatus());
        assertNotNull(published.getPublishedAt());
        assertEquals(1, published.getAttempts());

        redisConsumer.pollAndProcessMessages();
        assertEquals(1, campaignRepository.countByMerchantId(merchant.getId()));
        assertEquals(actionCount, actionIntentRepository.countByMerchantId(merchant.getId()));
    }

    @Test
    void invalidSignatureStillPersistsNeitherWebhookNorOutbox() {
        Merchant merchant = createMerchant("signature");
        String externalEventId = "evt_invalid_signature_" + UUID.randomUUID();
        String payload = payload(externalEventId, merchant.getExternalReference());
        long outboxCountBefore = outboxEventRepository.count();

        assertThrows(WebhookSignatureException.class,
                () -> webhookService.processRazorpayWebhook(payload, "invalid-signature"));

        assertTrue(webhookEventRepository.findByExternalEventId(externalEventId).isEmpty());
        assertEquals(outboxCountBefore, outboxEventRepository.count());
    }

    private Merchant createMerchant(String prefix) {
        Merchant merchant = new Merchant(UUID.randomUUID(), "Outbox Test Merchant");
        merchant.setExternalReference("acc_outbox_" + prefix + "_" + UUID.randomUUID());
        return merchantRepository.saveAndFlush(merchant);
    }

    private String payload(String externalEventId, String accountId) {
        return "{\"event_id\":\"" + externalEventId
                + "\",\"event\":\"payment.failed\",\"account_id\":\"" + accountId + "\"}";
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static class IntentionalRollback extends RuntimeException {
    }
}

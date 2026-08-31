package com.mend.events;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WebhookEventMessageTest {

    @Test
    void webhookEventMessage_ConstructsAndRetrievesFieldsCorrectly() {
        UUID eventId = UUID.randomUUID();
        String externalEventId = "evt_razorpay_12345";
        UUID merchantId = UUID.randomUUID();
        String eventType = "payment.failed";
        Instant occurredAt = Instant.now();
        String payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        WebhookEventMessage message = new WebhookEventMessage(
                eventId,
                externalEventId,
                merchantId,
                eventType,
                occurredAt,
                payloadHash
        );

        assertEquals(eventId, message.eventId());
        assertEquals(externalEventId, message.externalEventId());
        assertEquals(merchantId, message.merchantId());
        assertEquals(eventType, message.eventType());
        assertEquals(occurredAt, message.occurredAt());
        assertEquals(payloadHash, message.payloadHash());
    }

    @Test
    void redisEventConstants_ExposesCorrectConstantValuesAndIsNonInstantiable() throws NoSuchMethodException {
        assertEquals("mend:webhooks", RedisEventConstants.WEBHOOK_STREAM);
        assertEquals("mend-webhook-processors", RedisEventConstants.CONSUMER_GROUP);
        assertEquals("backend-consumer", RedisEventConstants.CONSUMER_NAME);

        Constructor<RedisEventConstants> constructor = RedisEventConstants.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException exception = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, exception.getCause());
    }
}

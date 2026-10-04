package com.mend.service;

import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.provider.MoneyMinor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookAmountResolverTest {

    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID OTHER_MERCHANT = UUID.randomUUID();
    private static final String PAYMENT_ID = "pay_abc123";

    private final WebhookEventRepository repository = mock(WebhookEventRepository.class);
    private final WebhookAmountResolver resolver = new WebhookAmountResolver(repository, new ObjectMapper());

    private void stored(String entityJson) {
        String payload = "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":" + entityJson + "}}}";
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), PAYMENT_ID, "payment.failed", "razorpay", payload);
        when(repository.findByMerchantIdAndExternalEventId(MERCHANT, PAYMENT_ID)).thenReturn(Optional.of(event));
    }

    @Test
    void integralPaiseWithCurrencyResolves() {
        stored("{\"id\":\"pay_abc123\",\"amount\":49900,\"currency\":\"INR\"}");
        assertEquals(Optional.of(new MoneyMinor(49900, "INR")), resolver.resolveOriginalAmount(MERCHANT, PAYMENT_ID));
    }

    @Test
    void decimalAmountIsEmpty() {
        stored("{\"amount\":100.5,\"currency\":\"INR\"}");
        assertTrue(resolver.resolveOriginalAmount(MERCHANT, PAYMENT_ID).isEmpty());
    }

    @Test
    void stringAmountIsEmpty() {
        stored("{\"amount\":\"100\",\"currency\":\"INR\"}");
        assertTrue(resolver.resolveOriginalAmount(MERCHANT, PAYMENT_ID).isEmpty());
    }

    @Test
    void zeroOrNegativeAmountIsEmpty() {
        stored("{\"amount\":0,\"currency\":\"INR\"}");
        assertTrue(resolver.resolveOriginalAmount(MERCHANT, PAYMENT_ID).isEmpty());
    }

    @Test
    void missingCurrencyIsEmptyNotDefaultedToInr() {
        stored("{\"amount\":100}");
        assertTrue(resolver.resolveOriginalAmount(MERCHANT, PAYMENT_ID).isEmpty());
    }

    @Test
    void unsupportedCurrencyIsEmpty() {
        stored("{\"amount\":100,\"currency\":\"USD\"}");
        assertTrue(resolver.resolveOriginalAmount(MERCHANT, PAYMENT_ID).isEmpty());
    }

    @Test
    void malformedPayloadIsEmpty() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), PAYMENT_ID, "payment.failed", "razorpay", "{not json");
        when(repository.findByMerchantIdAndExternalEventId(MERCHANT, PAYMENT_ID)).thenReturn(Optional.of(event));
        assertTrue(resolver.resolveOriginalAmount(MERCHANT, PAYMENT_ID).isEmpty());
    }

    @Test
    void otherMerchantsEventIsNotVisible() {
        stored("{\"amount\":49900,\"currency\":\"INR\"}");
        when(repository.findByMerchantIdAndExternalEventId(OTHER_MERCHANT, PAYMENT_ID)).thenReturn(Optional.empty());

        assertTrue(resolver.resolveOriginalAmount(OTHER_MERCHANT, PAYMENT_ID).isEmpty());
        verify(repository, never()).findByExternalEventId(any());
    }

    @Test
    void existingResolveAmountResultsAreUnchanged() {
        stored("{\"amount\":49900,\"currency\":\"INR\"}");
        assertEquals(Optional.of(new BigDecimal("499.00")), resolver.resolveAmount(MERCHANT, PAYMENT_ID));

        stored("{\"amount\":\"100\"}");
        assertEquals(Optional.of(new BigDecimal("1.00")), resolver.resolveAmount(MERCHANT, PAYMENT_ID));

        stored("{\"currency\":\"INR\"}");
        assertTrue(resolver.resolveAmount(MERCHANT, PAYMENT_ID).isEmpty());
    }
}

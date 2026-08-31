package com.mend.service;

import tools.jackson.databind.ObjectMapper;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.WebhookResponseDto;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.WebhookSignatureException;
import com.mend.publisher.WebhookEventPublisher;
import com.mend.security.RazorpaySignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private RazorpaySignatureVerifier signatureVerifier;

    @Mock
    private WebhookEventRepository webhookEventRepository;

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private WebhookEventPublisher webhookEventPublisher;

    private ObjectMapper objectMapper;
    private WebhookService webhookService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        webhookService = new WebhookService(
                signatureVerifier,
                webhookEventRepository,
                merchantRepository,
                webhookEventPublisher,
                objectMapper
        );
    }

    @Test
    void processRazorpayWebhook_ValidPayloadAndSignature_Success() {
        String payload = "{\"event_id\":\"evt_1001\",\"event\":\"payment.failed\",\"account_id\":\"acc_merchant_1\"}";
        String signature = "valid_signature_hash";

        when(signatureVerifier.verifySignature(payload, signature)).thenReturn(true);
        when(webhookEventRepository.findByExternalEventId("evt_1001")).thenReturn(Optional.empty());

        Merchant merchant = new Merchant(UUID.randomUUID(), "Test Merchant");
        merchant.setExternalReference("acc_merchant_1");
        when(merchantRepository.findByExternalReference("acc_merchant_1")).thenReturn(Optional.of(merchant));
        when(webhookEventRepository.saveAndFlush(any(WebhookEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WebhookResponseDto response = webhookService.processRazorpayWebhook(payload, signature);

        assertNotNull(response);
        assertEquals("ACCEPTED", response.getStatus());
        assertEquals("evt_1001", response.getExternalEventId());

        verify(webhookEventRepository).saveAndFlush(any(WebhookEvent.class));
        verify(webhookEventPublisher).publish(any(WebhookEvent.class));
    }

    @Test
    void processRazorpayWebhook_InvalidSignature_ThrowsWebhookSignatureException() {
        String payload = "{\"event_id\":\"evt_1002\"}";
        String signature = "bad_signature";

        when(signatureVerifier.verifySignature(payload, signature)).thenReturn(false);

        assertThrows(WebhookSignatureException.class, () -> webhookService.processRazorpayWebhook(payload, signature));

        verifyNoInteractions(webhookEventRepository);
        verifyNoInteractions(webhookEventPublisher);
    }

    @Test
    void processRazorpayWebhook_DuplicateEvent_ReturnsDuplicateResponseWithoutRepublishing() {
        String payload = "{\"event_id\":\"evt_1003\",\"event\":\"payment.failed\"}";
        String signature = "valid_signature";

        when(signatureVerifier.verifySignature(payload, signature)).thenReturn(true);

        WebhookEvent existingEvent = new WebhookEvent();
        existingEvent.setId(UUID.randomUUID());
        existingEvent.setExternalEventId("evt_1003");
        existingEvent.setEventType("payment.failed");

        when(webhookEventRepository.findByExternalEventId("evt_1003")).thenReturn(Optional.of(existingEvent));

        WebhookResponseDto response = webhookService.processRazorpayWebhook(payload, signature);

        assertEquals("DUPLICATE", response.getStatus());
        assertEquals("evt_1003", response.getExternalEventId());

        verify(webhookEventRepository, never()).saveAndFlush(any());
        verify(webhookEventPublisher, never()).publish(any());
    }

    @Test
    void processRazorpayWebhook_MalformedJson_ThrowsInvalidRequestException() {
        String payload = "{bad_json_string";
        String signature = "valid_signature";

        when(signatureVerifier.verifySignature(payload, signature)).thenReturn(true);

        assertThrows(InvalidRequestException.class, () -> webhookService.processRazorpayWebhook(payload, signature));
    }
}

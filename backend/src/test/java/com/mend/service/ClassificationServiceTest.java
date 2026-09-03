package com.mend.service;

import com.mend.client.AiClassificationClient;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.FailureClass;
import com.mend.domain.enums.RecommendedAction;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.ai.ClassificationRequestDto;
import com.mend.dto.ai.ClassificationResponseDto;
import com.mend.exception.AiClassificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class ClassificationServiceTest {

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private ClassificationResultRepository classificationResultRepository;

    @MockitoBean
    private AiClassificationClient aiClassificationClient;

    @BeforeEach
    void setUp() {
        classificationResultRepository.deleteAll();
        webhookEventRepository.deleteAll();
    }

    @Test
    void classifyAndPersist_ValidEvent_ClassifiesAndPersistsResult() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "evt_ai_test_1", "payment.failed");
        event.setSource("RAZORPAY");
        event.setErrorMessage("insufficient_funds");
        WebhookEvent savedEvent = webhookEventRepository.save(event);

        ClassificationResponseDto aiResponse = new ClassificationResponseDto(
                FailureClass.INSUFFICIENT_FUNDS,
                new BigDecimal("0.95"),
                RecommendedAction.RETRY_LATER,
                "Insufficient customer funds",
                "v1.0.0-rule-based"
        );

        when(aiClassificationClient.classify(any(ClassificationRequestDto.class))).thenReturn(aiResponse);

        ClassificationResult result = classificationService.classifyAndPersist(savedEvent, "insufficient_funds", "Account balance low");

        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals(savedEvent.getId(), result.getEventId());
        assertEquals("INSUFFICIENT_FUNDS", result.getFailureClass());
        assertEquals(new BigDecimal("0.95"), result.getConfidence());
        assertEquals("RETRY_LATER", result.getStrategyRecommendation());
        assertEquals("Insufficient customer funds", result.getReasoning());

        WebhookEvent updatedEvent = webhookEventRepository.findById(savedEvent.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, updatedEvent.getProcessingStatus());
        assertNotNull(updatedEvent.getProcessedAt());
    }

    @Test
    void classifyAndPersist_DuplicateCall_IsIdempotent() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "evt_ai_test_2", "payment.failed");
        WebhookEvent savedEvent = webhookEventRepository.save(event);

        ClassificationResponseDto aiResponse = new ClassificationResponseDto(
                FailureClass.CARD_EXPIRED,
                new BigDecimal("0.95"),
                RecommendedAction.CUSTOMER_ACTION_REQUIRED,
                "Card expired",
                "v1.0.0-rule-based"
        );

        when(aiClassificationClient.classify(any(ClassificationRequestDto.class))).thenReturn(aiResponse);

        // First call
        ClassificationResult first = classificationService.classifyAndPersist(savedEvent, "expired_card", "Card expiry");

        // Second call (duplicate delivery)
        ClassificationResult second = classificationService.classifyAndPersist(savedEvent, "expired_card", "Card expiry");

        assertEquals(first.getId(), second.getId());
        // Verify AI client was only called ONCE due to DB idempotency check
        verify(aiClassificationClient, times(1)).classify(any(ClassificationRequestDto.class));
        assertEquals(1, classificationResultRepository.count());
    }

    @Test
    void classifyAndPersist_AiServiceFailure_FallsBackToUnknownAndPersistsResult() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "evt_ai_test_3", "payment.failed");
        final WebhookEvent savedEvent = webhookEventRepository.save(event);

        when(aiClassificationClient.classify(any(ClassificationRequestDto.class)))
                .thenThrow(new AiClassificationException("AI service unavailable"));

        ClassificationResult result = classificationService.classifyAndPersist(savedEvent, "bank_declined", "Bank error");

        assertNotNull(result);
        assertEquals("UNKNOWN", result.getFailureClass());
        assertEquals("REVIEW_REQUIRED", result.getStrategyRecommendation());
        assertEquals(new BigDecimal("0.30"), result.getConfidence());
        assertTrue(result.getReasoning().contains("AI classification service unavailable"));

        WebhookEvent updatedEvent = webhookEventRepository.findById(savedEvent.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, updatedEvent.getProcessingStatus());
        assertEquals(1, classificationResultRepository.count());
    }
}

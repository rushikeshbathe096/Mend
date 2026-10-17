package com.mend.service;

import com.mend.client.AiClassificationClient;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.taxonomy.FailureTaxonomy;
import com.mend.taxonomy.ProviderFailure;
import com.mend.testsupport.TestRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * M3 U2.2 behaviour change (ADR-003): classification is the deterministic Razorpay taxonomy. The three scenarios are kept
 * (valid event, idempotency, failure without a documented reason) and assert the deterministic result; the AI client is
 * mocked only to prove it is never called. Own rows only (was deleteAll() on five shared tables).
 */
@SpringBootTest
@ActiveProfiles("test")
class ClassificationServiceTest {

    @Autowired private ClassificationService classificationService;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private ClassificationResultRepository classificationResultRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean
    private AiClassificationClient aiClassificationClient;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        merchantRepository.save(new Merchant(merchantId, "Classification merchant"));
    }

    @AfterEach
    void tearDown() {
        verifyNoInteractions(aiClassificationClient);
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    private WebhookEvent event(String externalId) {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), externalId + "_" + UUID.randomUUID(), "payment.failed");
        event.setMerchantId(merchantId);
        return webhookEventRepository.save(event);
    }

    @Test
    void classifyAndPersist_ValidEvent_ClassifiesAndPersistsResult() {
        WebhookEvent savedEvent = event("evt_det_test_1");

        ClassificationResult result = classificationService.classifyAndPersist(savedEvent,
                ProviderFailure.of("BAD_REQUEST_ERROR", "insufficient_funds", "customer"));

        assertNotNull(result.getId());
        assertEquals(savedEvent.getId(), result.getEventId());
        assertEquals("INSUFFICIENT_FUNDS", result.getFailureClass());
        assertNull(result.getConfidence());
        assertNull(result.getStrategyRecommendation(), "the strategy engine decides the strategy, not the classifier");
        assertNull(result.getModelVersion());
        assertEquals(FailureTaxonomy.TABLE_VERSION, result.getTaxonomyVersion());
        assertTrue(result.getReasoning().contains("insufficient_funds"), result.getReasoning());
        assertEquals("insufficient_funds", result.getEvidence().get("errorReason"));
        assertEquals("customer", result.getEvidence().get("errorSource"));

        WebhookEvent updatedEvent = webhookEventRepository.findById(savedEvent.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, updatedEvent.getProcessingStatus());
        assertNotNull(updatedEvent.getProcessedAt());
    }

    @Test
    void classifyAndPersist_DuplicateCall_IsIdempotent() {
        WebhookEvent savedEvent = event("evt_det_test_2");
        ProviderFailure failure = ProviderFailure.of(null, "card_expired", "customer");

        ClassificationResult first = classificationService.classifyAndPersist(savedEvent, failure);
        ClassificationResult second = classificationService.classifyAndPersist(savedEvent, failure);

        assertEquals(first.getId(), second.getId());
        assertEquals("INSTRUMENT_INVALID", second.getFailureClass());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM classification_results WHERE event_id = ?", Long.class, savedEvent.getId()));
    }

    @Test
    void classifyAndPersist_NoDocumentedReason_IsUnclassifiedAndPersisted() {
        WebhookEvent savedEvent = event("evt_det_test_3");

        ClassificationResult result = classificationService.classifyAndPersist(savedEvent, ProviderFailure.of(null, "bank_declined", null));

        assertEquals("UNCLASSIFIED", result.getFailureClass());
        assertNull(result.getConfidence(), "no fabricated fallback confidence");
        assertNull(result.getStrategyRecommendation(), "no fabricated REVIEW_REQUIRED recommendation");
        assertEquals("NONE", result.getEvidence().get("matchedOn"));

        WebhookEvent updatedEvent = webhookEventRepository.findById(savedEvent.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, updatedEvent.getProcessingStatus());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM classification_results WHERE event_id = ?", Long.class, savedEvent.getId()));
    }
}

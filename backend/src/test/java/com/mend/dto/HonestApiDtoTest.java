package com.mend.dto;

import com.mend.controller.InternalSystemHealthController;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.ReviewQueue;
import com.mend.security.AuthenticatedUser;
import com.mend.taxonomy.FailureTaxonomy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 behaviour change (ADR-003 §3; invariant 3): was AiClassificationClientTest, which tested the deleted ai-service
 * classification client (its parsed confidence and its error mapping). These cases assert what replaced it: API
 * responses carry the deterministic taxonomy version and never a confidence, an agent field or an unmeasured number.
 */
class HonestApiDtoTest {

    // the API serializer (Spring Boot 4 / Jackson 3)
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void deterministicClassificationExposesTaxonomyVersionAndNoConfidence() {
        ClassificationResult row = new ClassificationResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "INSUFFICIENT_FUNDS", null, null, "Razorpay error_reason 'insufficient_funds'", null);
        row.setTaxonomyVersion(FailureTaxonomy.TABLE_VERSION);

        JsonNode dto = json.valueToTree(ClassificationResultDto.fromEntity(row));

        assertEquals("INSUFFICIENT_FUNDS", dto.get("failureClass").asText());
        assertEquals(FailureTaxonomy.TABLE_VERSION, dto.get("taxonomyVersion").asText());
        assertFalse(dto.has("confidence"));
    }

    @Test
    void legacyConfidenceAndAgentFieldsAreNotExposed() {
        // a legacy (AI-era) row: its stored confidence is history, never re-exposed; no taxonomy version marks it legacy
        ClassificationResult legacy = new ClassificationResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "UNKNOWN", new BigDecimal("0.30"), "MANUAL_REVIEW", "AI fallback", "v1.0.0");
        Campaign campaign = new Campaign(UUID.randomUUID(), UUID.randomUUID());
        campaign.setConfidence(new BigDecimal("0.30"));

        JsonNode classification = json.valueToTree(ClassificationResultDto.fromEntity(legacy));
        assertTrue(classification.get("taxonomyVersion").isNull());
        assertFalse(classification.has("confidence"));
        assertFalse(json.valueToTree(CampaignDto.fromEntity(campaign)).has("confidence"));

        ReviewQueue review = new ReviewQueue(UUID.randomUUID(), campaign.getId(), campaign.getMerchantId());
        JsonNode reviewItem = json.valueToTree(ReviewItemDto.from(review, campaign, null));
        reviewItem.propertyNames().forEach(name -> {
            assertFalse(name.startsWith("agent"), "review item field " + name);
            assertNotEquals("confidence", name);
        });
    }

    @Test
    void operationalHealthReportsOnlyMeasuredValues() {
        // operational health: unmeasured values are null and the status UNKNOWN (was a hardcoded HEALTHY / 45 ms / AI "UP")
        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "ops@mend.io", "Ops", "ACTIVE",
                List.of(new AuthenticatedUser.MerchantMembershipInfo(UUID.randomUUID(), "System", UUID.randomUUID(), "SYSTEM_ADMIN")));

        JsonNode health = json.valueToTree(new InternalSystemHealthController(null).getOperationalHealth(admin).getBody());

        assertEquals("UNKNOWN", health.get("status").asText());
        assertTrue(health.get("redisStreamLag").isNull());
        assertTrue(health.get("webhookProcessingLatencyMs").isNull());
        assertFalse(health.has("aiServiceAvailability"));
        assertFalse(health.has("aiFallbackRate"));
    }
}

package com.mend.service;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.taxonomy.FailureClassification;
import com.mend.taxonomy.FailureTaxonomy;
import com.mend.taxonomy.ProviderFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * M4 U4.8(a) (ADR-003 §4 follow-up): re-classifies pre-M3 campaigns from their STORED failure webhook with the deterministic
 * taxonomy, into a NEW classification row. The legacy row is never rewritten (invariant 10 spirit, ADR-003). Nothing acts:
 * no state changes, no intent, no review. The new row only lets a human-approved re-evaluation (e.g. a LEGACY_DECISION
 * review) see a deterministic class instead of failing closed. A campaign with no stored webhook keeps failing closed.
 */
@Service
public class LegacyReclassificationService {

    private static final Logger log = LoggerFactory.getLogger(LegacyReclassificationService.class);

    public enum Result { RECLASSIFIED, ALREADY_DETERMINISTIC, NO_CLASSIFICATION, NO_WEBHOOK }

    private final CampaignRepository campaignRepository;
    private final ClassificationResultRepository classificationResultRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public LegacyReclassificationService(CampaignRepository campaignRepository,
                                         ClassificationResultRepository classificationResultRepository,
                                         WebhookEventRepository webhookEventRepository, AuditService auditService,
                                         ObjectMapper objectMapper) {
        this.campaignRepository = campaignRepository;
        this.classificationResultRepository = classificationResultRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** Reclassifies every legacy campaign of the merchant; returns the count per result. */
    @Transactional
    public Map<Result, Integer> reclassifyMerchant(UUID merchantId) {
        Map<Result, Integer> counts = new LinkedHashMap<>();
        for (Result r : Result.values()) {
            counts.put(r, 0);
        }
        for (Campaign campaign : campaignRepository.findByMerchantId(merchantId)) {
            counts.merge(reclassify(campaign), 1, Integer::sum);
        }
        log.info("event=legacy_reclassification merchant={} counts={}", merchantId, counts);
        return counts;
    }

    @Transactional
    public Result reclassify(Campaign campaign) {
        Optional<ClassificationResult> latest = classificationResultRepository.findLatestByCampaignId(campaign.getId());
        if (latest.isEmpty()) {
            return Result.NO_CLASSIFICATION;
        }
        if (latest.get().getTaxonomyVersion() != null) {
            return Result.ALREADY_DETERMINISTIC;
        }
        Optional<WebhookEvent> webhook = campaign.getPaymentId() == null ? Optional.empty()
                : webhookEventRepository.findByMerchantIdAndExternalEventId(campaign.getMerchantId(), campaign.getPaymentId());
        if (webhook.isEmpty()) {
            return Result.NO_WEBHOOK;
        }
        ProviderFailure failure = DefaultEventProcessingService.providerFailureOf(objectMapper, webhook.get());
        FailureClassification classification = FailureTaxonomy.classify(failure);
        // event_id stays NULL: the legacy row keeps its event link (unique), the new row links by campaign
        ClassificationResult row = new ClassificationResult(null, null, campaign.getId(), classification.failureClass().name(),
                null, null, "Reclassified from the stored webhook with the deterministic taxonomy (legacy row "
                + latest.get().getId() + " kept)", null);
        row.setTaxonomyVersion(classification.tableVersion());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("reclassifiedFromClassificationId", latest.get().getId());
        evidence.put("webhookEventId", webhook.get().getId());
        evidence.putAll(classification.evidence(failure));
        row.setEvidence(evidence);
        classificationResultRepository.saveAndFlush(row);
        auditService.logStructuredEvent(campaign.getMerchantId(), campaign.getId(), "LEGACY_RECLASSIFIED", "SYSTEM", null,
                "Legacy classification " + latest.get().getId() + " kept; new deterministic row " + classification.failureClass(),
                Map.of("legacyClassificationId", latest.get().getId().toString(), "newClassificationId", row.getId().toString(),
                        "failureClass", classification.failureClass().name(), "taxonomyVersion", classification.tableVersion()),
                null);
        return Result.RECLASSIFIED;
    }
}

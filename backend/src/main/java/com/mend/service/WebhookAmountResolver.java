package com.mend.service;

import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.repository.WebhookEventRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the authoritative monetary value of a payment failure from the
 * original provider webhook payload. Amounts are never stored on the Campaign
 * entity; the raw provider payload is the single source of financial truth.
 */
@Component
public class WebhookAmountResolver {

    private static final Logger log = LoggerFactory.getLogger(WebhookAmountResolver.class);

    private final WebhookEventRepository webhookEventRepository;
    private final ObjectMapper objectMapper;

    public WebhookAmountResolver(WebhookEventRepository webhookEventRepository, ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.objectMapper = objectMapper;
    }

    public Optional<BigDecimal> resolveAmount(UUID merchantId, String paymentId) {
        if (merchantId == null || paymentId == null || paymentId.isBlank()) {
            return Optional.empty();
        }
        return webhookEventRepository.findByMerchantIdAndExternalEventId(merchantId, paymentId)
                .map(this::extractAmountFromWebhook)
                .filter(amount -> amount != null && amount.signum() > 0);
    }

    /**
     * Fallback lookup without tenant scoping is intentionally avoided; amounts are
     * always resolved within the merchant's own event history.
     */
    public BigDecimal extractAmountFromWebhook(WebhookEvent event) {
        if (event == null || event.getRawPayload() == null || event.getRawPayload().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(event.getRawPayload());
            JsonNode paymentNode = root.path("payload").path("payment").path("entity");
            if (paymentNode.isMissingNode() || paymentNode.isNull()) {
                paymentNode = root.path("payment").path("entity");
            }
            if (paymentNode.isMissingNode() || paymentNode.isNull()) {
                paymentNode = root;
            }

            if (paymentNode.has("amount") && !paymentNode.get("amount").isNull()) {
                long amountInPaise = paymentNode.get("amount").asLong();
                return BigDecimal.valueOf(amountInPaise)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            log.trace("Error parsing webhook payload amount: {}", e.getMessage());
        }
        return null;
    }
}

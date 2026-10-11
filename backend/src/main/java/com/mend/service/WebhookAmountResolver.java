package com.mend.service;

import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.provider.MoneyMinor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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

    /**
     * Display amount (major units, scale 2) derived from {@link #resolveOriginalAmount}; empty under exactly the same
     * conditions, never a lenient or default value.
     */
    public Optional<BigDecimal> resolveAmount(UUID merchantId, String paymentId) {
        return resolveOriginalAmount(merchantId, paymentId).map(WebhookAmountResolver::toDisplay);
    }

    /** Minor units to the major-unit display value (paise to rupees, scale 2). Exact: no floating point. */
    public static BigDecimal toDisplay(MoneyMinor amount) {
        return toDisplay(amount.amountMinor());
    }

    /** A sum of minor units to the major-unit display value (scale 2). */
    public static BigDecimal toDisplay(long amountMinor) {
        return BigDecimal.valueOf(amountMinor, 2);
    }

    /**
     * Original payment amount in integer minor units, with the currency from the same payment entity.
     * Empty when the event is missing, the amount is not a positive integral number, or the currency is
     * missing or unsupported. Nothing is defaulted. Tenant-scoped lookup only.
     */
    public Optional<MoneyMinor> resolveOriginalAmount(UUID merchantId, String paymentId) {
        if (merchantId == null || paymentId == null || paymentId.isBlank()) {
            return Optional.empty();
        }
        return webhookEventRepository.findByMerchantIdAndExternalEventId(merchantId, paymentId)
                .flatMap(this::extractMoneyMinor);
    }

    /**
     * Original payment amount of one stored webhook (the strict rule of {@link #resolveOriginalAmount}): a positive
     * integral minor-unit amount with a supported currency, otherwise empty.
     */
    public Optional<MoneyMinor> extractMoneyMinor(WebhookEvent event) {
        try {
            JsonNode paymentNode = paymentEntity(event);
            if (paymentNode == null) {
                log.debug("event=amount_unresolved reason=NO_PAYLOAD webhookEventId={}", event.getId());
                return Optional.empty();
            }
            JsonNode amount = paymentNode.path("amount");
            if (!amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() <= 0) {
                log.debug("event=amount_unresolved reason=AMOUNT_NOT_POSITIVE_INTEGER webhookEventId={}", event.getId());
                return Optional.empty();
            }
            JsonNode currency = paymentNode.path("currency");
            if (!currency.isString() || currency.stringValue().isBlank()) {
                log.debug("event=amount_unresolved reason=CURRENCY_MISSING webhookEventId={}", event.getId());
                return Optional.empty();
            }
            try {
                return Optional.of(new MoneyMinor(amount.longValue(), currency.stringValue()));
            } catch (IllegalArgumentException unsupported) {
                log.debug("event=amount_unresolved reason=CURRENCY_UNSUPPORTED webhookEventId={}", event.getId());
                return Optional.empty();
            }
        } catch (Exception e) {
            log.debug("event=amount_unresolved reason=PARSE_ERROR webhookEventId={}", event.getId());
            return Optional.empty();
        }
    }

    /** The payment entity node of a stored webhook, or null when there is no payload. */
    private JsonNode paymentEntity(WebhookEvent event) {
        if (event == null || event.getRawPayload() == null || event.getRawPayload().isBlank()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(event.getRawPayload());
        JsonNode paymentNode = root.path("payload").path("payment").path("entity");
        if (paymentNode.isMissingNode() || paymentNode.isNull()) {
            paymentNode = root.path("payment").path("entity");
        }
        if (paymentNode.isMissingNode() || paymentNode.isNull()) {
            paymentNode = root;
        }
        return paymentNode;
    }
}

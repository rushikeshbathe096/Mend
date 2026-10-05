package com.mend.service;

import com.mend.domain.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M2 U6: the invoice identity behind a failed payment (M2-execution-safety.md §6), used to allow at most one in-flight
 * recovery intent per invoice. Derived from the original failure webhook's payment entity (untrusted input):
 * <pre>
 *   inv:&lt;invoice_id&gt;  |  ord:&lt;order_id&gt;  |  pay:&lt;payment id&gt;  |  cmp:&lt;campaignId&gt; (fallback, WARN)
 * </pre>
 * The first field present (in that order) decides. A present but malformed id is not trusted and falls back to the campaign:
 * the derivation may under-group (one key per campaign, today's behaviour) but never merges two different invoices.
 * Field existence: DOC + RECORDED (recording 21); whether failed subscription payments always carry {@code invoice_id} is
 * AMBIGUOUS (no recorded failure webhook).
 */
@Component
public class InvoiceKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(InvoiceKeyResolver.class);

    private static final Pattern INVOICE_ID = Pattern.compile("^inv_[A-Za-z0-9]{1,60}$");
    private static final Pattern ORDER_ID = Pattern.compile("^order_[A-Za-z0-9]{1,60}$");
    private static final Pattern PAYMENT_ID = Pattern.compile("^pay_[A-Za-z0-9]{1,60}$");

    private final WebhookEventRepository webhookEventRepository;
    private final ObjectMapper objectMapper;

    public InvoiceKeyResolver(WebhookEventRepository webhookEventRepository, ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.objectMapper = objectMapper;
    }

    /** Key for a new campaign, from the stored failure webhook of {@code paymentId} (tenant-scoped); fallback without it. */
    public String resolveForCampaign(UUID merchantId, String paymentId, UUID campaignId) {
        if (merchantId == null || paymentId == null || paymentId.isBlank()) {
            return fallback(campaignId, "NO_PAYMENT_ID");
        }
        try {
            return webhookEventRepository.findByMerchantIdAndExternalEventId(merchantId, paymentId)
                    .map(event -> fromPayload(objectMapper.readTree(event.getRawPayload()), campaignId))
                    .orElseGet(() -> fallback(campaignId, "NO_WEBHOOK"));
        } catch (RuntimeException unreadable) {
            // Never let key derivation break campaign creation; the fallback only under-groups.
            return fallback(campaignId, "LOOKUP_OR_PARSE_FAILED");
        }
    }

    /** Pure derivation from a webhook payload root ({@code payload.payment.entity}). */
    public static String fromPayload(JsonNode root, UUID campaignId) {
        JsonNode entity = root == null ? null : root.path("payload").path("payment").path("entity");
        if (entity == null || !entity.isObject()) {
            return fallback(campaignId, "NO_PAYMENT_ENTITY");
        }
        String invoiceId = text(entity, "invoice_id");
        if (invoiceId != null) {
            return INVOICE_ID.matcher(invoiceId).matches() ? "inv:" + invoiceId : fallback(campaignId, "MALFORMED_INVOICE_ID");
        }
        String orderId = text(entity, "order_id");
        if (orderId != null) {
            return ORDER_ID.matcher(orderId).matches() ? "ord:" + orderId : fallback(campaignId, "MALFORMED_ORDER_ID");
        }
        String paymentId = text(entity, "id");
        if (paymentId != null) {
            return PAYMENT_ID.matcher(paymentId).matches() ? "pay:" + paymentId : fallback(campaignId, "MALFORMED_PAYMENT_ID");
        }
        return fallback(campaignId, "NO_IDS");
    }

    public static String fallback(UUID campaignId) {
        return "cmp:" + campaignId;
    }

    private static String fallback(UUID campaignId, String reason) {
        log.warn("event=invoice_key_fallback campaign_id={} reason={}", campaignId, reason);
        return fallback(campaignId);
    }

    /** Only a non-blank string counts; null, numbers and objects are ignored (untrusted payload). */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank() ? value.stringValue().trim() : null;
    }
}

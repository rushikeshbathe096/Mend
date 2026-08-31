package com.mend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.WebhookResponseDto;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.WebhookSignatureException;
import com.mend.publisher.WebhookEventPublisher;
import com.mend.security.RazorpaySignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final RazorpaySignatureVerifier signatureVerifier;
    private final WebhookEventRepository webhookEventRepository;
    private final MerchantRepository merchantRepository;
    private final WebhookEventPublisher webhookEventPublisher;
    private final ObjectMapper objectMapper;

    public WebhookService(
            RazorpaySignatureVerifier signatureVerifier,
            WebhookEventRepository webhookEventRepository,
            MerchantRepository merchantRepository,
            WebhookEventPublisher webhookEventPublisher,
            ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.webhookEventRepository = webhookEventRepository;
        this.merchantRepository = merchantRepository;
        this.webhookEventPublisher = webhookEventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WebhookResponseDto processRazorpayWebhook(String rawPayload, String signature) {
        // 1. Verify Razorpay HMAC-SHA256 signature
        if (signature == null || signature.isBlank() || !signatureVerifier.verifySignature(rawPayload, signature)) {
            log.warn("Razorpay webhook signature verification failed");
            throw new WebhookSignatureException("Invalid or missing Razorpay webhook signature");
        }

        if (rawPayload == null || rawPayload.isBlank()) {
            throw new InvalidRequestException("Empty webhook payload");
        }

        // 2. Parse payload structure
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            log.error("Failed to parse webhook JSON payload", e);
            throw new InvalidRequestException("Malformed JSON webhook payload");
        }

        String eventType = extractEventType(rootNode);
        String payloadHash = calculateSha256(rawPayload);
        String externalEventId = extractExternalEventId(rootNode, eventType, payloadHash);
        Instant eventCreatedAt = extractEventCreatedAt(rootNode);
        UUID merchantId = extractMerchantId(rootNode);

        // 3. Idempotency Check (Check if already processed)
        Optional<WebhookEvent> existingEventOpt = webhookEventRepository.findByExternalEventId(externalEventId);
        if (existingEventOpt.isPresent()) {
            WebhookEvent existing = existingEventOpt.get();
            log.info("Duplicate Razorpay webhook event received: {}. Skipping processing.", externalEventId);
            return new WebhookResponseDto(
                    existing.getId(),
                    existing.getExternalEventId(),
                    "DUPLICATE",
                    "Webhook event already processed",
                    existing.getReceivedAt()
            );
        }

        // 4. Create and persist WebhookEvent
        WebhookEvent webhookEvent = new WebhookEvent();
        webhookEvent.setId(UUID.randomUUID());
        webhookEvent.setExternalEventId(externalEventId);
        webhookEvent.setEventType(eventType);
        webhookEvent.setSource("RAZORPAY");
        webhookEvent.setReceivedAt(Instant.now());
        webhookEvent.setEventCreatedAt(eventCreatedAt);
        webhookEvent.setPayloadHash(payloadHash);
        webhookEvent.setRawPayload(rawPayload);
        webhookEvent.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhookEvent.setMerchantId(merchantId);

        try {
            webhookEvent = webhookEventRepository.saveAndFlush(webhookEvent);
        } catch (DataIntegrityViolationException e) {
            // Concurrent race condition handling for duplicate external_event_id
            log.info("Concurrent duplicate event insertion detected for: {}", externalEventId);
            Optional<WebhookEvent> concurrentEvent = webhookEventRepository.findByExternalEventId(externalEventId);
            if (concurrentEvent.isPresent()) {
                WebhookEvent existing = concurrentEvent.get();
                return new WebhookResponseDto(
                        existing.getId(),
                        existing.getExternalEventId(),
                        "DUPLICATE",
                        "Webhook event already processed",
                        existing.getReceivedAt()
                );
            }
            throw e;
        }

        // 5. Hand off to Phase 5 event publisher abstraction
        webhookEventPublisher.publish(webhookEvent);

        return new WebhookResponseDto(
                webhookEvent.getId(),
                webhookEvent.getExternalEventId(),
                "ACCEPTED",
                "Webhook event received and verified",
                webhookEvent.getReceivedAt()
        );
    }

    private String extractEventType(JsonNode root) {
        if (root.has("event") && !root.get("event").isNull()) {
            return root.get("event").asText("unknown");
        }
        return "unknown";
    }

    private String extractExternalEventId(JsonNode root, String eventType, String payloadHash) {
        // Check top-level event_id or id
        if (root.has("event_id") && !root.get("event_id").isNull() && !root.get("event_id").asText().isBlank()) {
            return root.get("event_id").asText();
        }
        if (root.has("id") && !root.get("id").isNull() && !root.get("id").asText().isBlank()) {
            return root.get("id").asText();
        }

        // Check nested entity ID in payload (e.g. payload.payment.entity.id)
        if (root.has("payload") && root.get("payload").isObject()) {
            JsonNode payload = root.get("payload");
            for (String fieldName : payload.propertyNames()) {
                JsonNode entityWrapper = payload.get(fieldName);
                if (entityWrapper.has("entity") && entityWrapper.get("entity").has("id")) {
                    String entityId = entityWrapper.get("entity").get("id").asText();
                    return eventType + "_" + entityId;
                }
            }
        }

        // Fallback to SHA256 of payload if no distinct provider ID exists
        return "event_" + payloadHash.substring(0, 16);
    }

    private Instant extractEventCreatedAt(JsonNode root) {
        if (root.has("created_at") && root.get("created_at").isNumber()) {
            long epochSeconds = root.get("created_at").asLong();
            return Instant.ofEpochSecond(epochSeconds);
        }
        return null;
    }

    private UUID extractMerchantId(JsonNode root) {
        // Do NOT trust X-Merchant-Id header. Resolve solely from payload.
        // 1. Check account_id at root level (Razorpay Merchant Account ID)
        if (root.has("account_id") && !root.get("account_id").isNull()) {
            String accountId = root.get("account_id").asText();
            Optional<Merchant> merchantOpt = merchantRepository.findByExternalReference(accountId);
            if (merchantOpt.isPresent()) {
                return merchantOpt.get().getId();
            }
        }

        // 2. Check notes.merchant_id in payload entities
        if (root.has("payload") && root.get("payload").isObject()) {
            JsonNode payloadNode = root.get("payload");
            for (String fieldName : payloadNode.propertyNames()) {
                JsonNode entityWrapper = payloadNode.get(fieldName);
                if (entityWrapper.has("entity") && entityWrapper.get("entity").has("notes")) {
                    JsonNode notesNode = entityWrapper.get("entity").get("notes");
                    if (notesNode.has("merchant_id") && !notesNode.get("merchant_id").isNull()) {
                        String merchantIdStr = notesNode.get("merchant_id").asText();
                        try {
                            UUID mId = UUID.fromString(merchantIdStr);
                            if (merchantRepository.existsById(mId)) {
                                return mId;
                            }
                        } catch (IllegalArgumentException ignored) {
                            // If external ref string
                            Optional<Merchant> merchantOpt = merchantRepository.findByExternalReference(merchantIdStr);
                            if (merchantOpt.isPresent()) {
                                return merchantOpt.get().getId();
                            }
                        }
                    }
                }
            }
        }

        // Return null if cannot reliably resolve merchant (do NOT guess)
        return null;
    }

    private String calculateSha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

package com.mend.service;

import com.mend.domain.entity.AuditLog;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.AuditLogRepository;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class AuditService {

    private static final List<String> SENSITIVE_KEYWORDS = List.of(
            "key", "secret", "password", "token", "cvv", "card_number", "authorization", "auth", "rzp_test_", "rzp_live_"
    );

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public AuditLog logStateTransition(
            UUID merchantId,
            UUID campaignId,
            CampaignStatus previousState,
            CampaignStatus newState,
            String actorType,
            UUID actorId,
            String reason) {

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("previousState", previousState != null ? previousState.name() : null);
        metadata.put("newState", newState != null ? newState.name() : null);
        metadata.put("timestamp", Instant.now().toString());

        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            metadata.put("correlationId", correlationId);
        }

        return logStructuredEvent(merchantId, campaignId, "CAMPAIGN_STATE_TRANSITION", actorType, actorId, reason, metadata, null);
    }

    @Transactional
    public AuditLog logEvent(
            UUID merchantId,
            UUID campaignId,
            String eventType,
            String actorType,
            UUID actorId,
            String reason) {

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("timestamp", Instant.now().toString());

        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            metadata.put("correlationId", correlationId);
        }

        return logStructuredEvent(merchantId, campaignId, eventType, actorType, actorId, reason, metadata, null);
    }

    @Transactional
    public AuditLog logStructuredEvent(
            UUID merchantId,
            UUID campaignId,
            String eventType,
            String actorType,
            UUID actorId,
            String reason,
            Map<String, Object> metadata,
            Map<String, Object> evidence) {

        AuditLog auditLog = new AuditLog(UUID.randomUUID(), eventType != null ? eventType : "GENERAL_EVENT");
        auditLog.setMerchantId(merchantId);
        auditLog.setCampaignId(campaignId);
        auditLog.setActorType(actorType != null ? actorType : "SYSTEM");
        auditLog.setActorId(actorId);
        auditLog.setReason(sanitizeText(reason));

        Map<String, Object> safeMetadata = sanitizeMap(metadata);
        if (safeMetadata == null) {
            safeMetadata = new HashMap<>();
        }
        if (!safeMetadata.containsKey("timestamp")) {
            safeMetadata.put("timestamp", Instant.now().toString());
        }
        String correlationId = MDC.get("correlationId");
        if (correlationId != null && !safeMetadata.containsKey("correlationId")) {
            safeMetadata.put("correlationId", correlationId);
        }

        auditLog.setMetadata(safeMetadata);
        auditLog.setEvidence(sanitizeMap(evidence));

        return auditLogRepository.save(auditLog);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getCampaignAuditLogs(UUID campaignId) {
        return auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getCampaignAuditLogs(UUID merchantId, UUID campaignId, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);
        return auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> getMerchantAuditLogs(UUID merchantId, Pageable pageable, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);
        return auditLogRepository.findByMerchantId(merchantId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> getMerchantAuditLogs(
            UUID merchantId,
            String eventType,
            String actorType,
            UUID campaignId,
            Pageable pageable,
            AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);

        if (eventType != null && !eventType.isBlank()) {
            return auditLogRepository.findByMerchantIdAndEventType(merchantId, eventType.trim(), pageable);
        }
        if (actorType != null && !actorType.isBlank()) {
            return auditLogRepository.findByMerchantIdAndActorType(merchantId, actorType.trim(), pageable);
        }
        if (campaignId != null) {
            return auditLogRepository.findByMerchantIdAndCampaignId(merchantId, campaignId, pageable);
        }

        return auditLogRepository.findByMerchantId(merchantId, pageable);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getMerchantAuditLogs(UUID merchantId, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);
        return auditLogRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    private void validateTenantAccess(UUID merchantId, AuthenticatedUser currentUser) {
        if (merchantId == null) {
            throw new TenantAccessDeniedException("Merchant context is required");
        }
        if (currentUser != null && !currentUser.isSystemAdmin() && !currentUser.isMemberOfMerchant(merchantId)) {
            throw new TenantAccessDeniedException("Access denied for merchant: " + merchantId);
        }
    }

    private String sanitizeText(String text) {
        if (text == null) return null;
        String sanitized = text;
        for (String kw : SENSITIVE_KEYWORDS) {
            if (sanitized.toLowerCase().contains(kw)) {
                // If it contains a secret prefix like rzp_test_ or rzp_live_, redact it
                sanitized = sanitized.replaceAll("rzp_(test|live)_[A-Za-z0-9]+", "[REDACTED]");
            }
        }
        return sanitized;
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> input) {
        if (input == null) return null;
        Map<String, Object> safeMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();

            boolean isSensitive = SENSITIVE_KEYWORDS.stream()
                    .anyMatch(kw -> key.toLowerCase().contains(kw));

            if (isSensitive) {
                safeMap.put(key, "[REDACTED]");
            } else if (val instanceof String strVal) {
                safeMap.put(key, sanitizeText(strVal));
            } else {
                safeMap.put(key, val);
            }
        }
        return safeMap;
    }
}

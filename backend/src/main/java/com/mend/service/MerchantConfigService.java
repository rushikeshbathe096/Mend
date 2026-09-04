package com.mend.service;

import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.repository.MerchantConfigRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.dto.MerchantConfigDto;
import com.mend.dto.UpdateMerchantConfigRequest;
import com.mend.exception.AuthenticationException;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class MerchantConfigService {

    private final MerchantConfigRepository merchantConfigRepository;
    private final MerchantRepository merchantRepository;

    public MerchantConfigService(MerchantConfigRepository merchantConfigRepository,
                                 MerchantRepository merchantRepository) {
        this.merchantConfigRepository = merchantConfigRepository;
        this.merchantRepository = merchantRepository;
    }

    @Transactional
    public MerchantConfigDto getMerchantConfig(UUID merchantId, AuthenticatedUser currentUser) {
        validateAuthentication(currentUser);
        validateMerchantExists(merchantId);
        validateTenantAccess(currentUser, merchantId);

        MerchantConfig config = merchantConfigRepository.findByMerchantId(merchantId)
                .orElseGet(() -> createDefaultConfig(merchantId));

        return MerchantConfigDto.fromEntity(config);
    }

    @Transactional
    public MerchantConfigDto updateMerchantConfig(UUID merchantId, UpdateMerchantConfigRequest request, AuthenticatedUser currentUser) {
        validateAuthentication(currentUser);
        validateMerchantExists(merchantId);
        validateTenantAccess(currentUser, merchantId);
        validateAdminPermission(currentUser, merchantId);

        if (request == null) {
            throw new InvalidRequestException("Request body is required");
        }

        if (request.getMaxAttempts() == null || request.getMaxAttempts() < 1 || request.getMaxAttempts() > 10) {
            throw new InvalidRequestException("maxAttempts must be between 1 and 10");
        }

        if (request.getContactWindowHours() == null || request.getContactWindowHours() < 1 || request.getContactWindowHours() > 168) {
            throw new InvalidRequestException("contactWindowHours must be between 1 and 168");
        }

        if (request.getRetryStrategy() == null || request.getRetryStrategy().isBlank()) {
            throw new InvalidRequestException("retryStrategy must not be blank");
        }

        MerchantConfig config = merchantConfigRepository.findByMerchantId(merchantId)
                .orElseGet(() -> new MerchantConfig(UUID.randomUUID(), merchantId));

        config.setMaxAttempts(request.getMaxAttempts());
        config.setContactWindowHours(request.getContactWindowHours());
        config.setRetryStrategy(request.getRetryStrategy().trim());

        if (request.getMaxContactAttempts() != null) {
            if (request.getMaxContactAttempts() < 1 || request.getMaxContactAttempts() > 10) {
                throw new InvalidRequestException("maxContactAttempts must be between 1 and 10");
            }
            config.setMaxContactAttempts(request.getMaxContactAttempts());
        }

        if (request.getEscalationThreshold() != null) {
            if (request.getEscalationThreshold() < 1 || request.getEscalationThreshold() > 10) {
                throw new InvalidRequestException("escalationThreshold must be between 1 and 10");
            }
            config.setEscalationThreshold(request.getEscalationThreshold());
        }

        if (request.getEnabledRecoveryActions() != null) {
            config.setEnabledRecoveryActions(request.getEnabledRecoveryActions().trim());
        }

        config = merchantConfigRepository.save(config);
        return MerchantConfigDto.fromEntity(config);
    }

    private MerchantConfig createDefaultConfig(UUID merchantId) {
        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setMaxAttempts(3);
        config.setMaxContactAttempts(3);
        config.setContactWindowHours(24);
        config.setRetryStrategy("EXPONENTIAL_BACKOFF");
        config.setEscalationThreshold(2);
        config.setEnabledRecoveryActions("RETRY_PAYMENT,SEND_EMAIL");
        return merchantConfigRepository.save(config);
    }

    private void validateAuthentication(AuthenticatedUser currentUser) {
        if (currentUser == null) {
            throw new AuthenticationException("Unauthenticated request");
        }
    }

    private void validateMerchantExists(UUID merchantId) {
        if (!merchantRepository.existsById(merchantId)) {
            throw new ResourceNotFoundException("Merchant not found: " + merchantId);
        }
    }

    private void validateTenantAccess(AuthenticatedUser currentUser, UUID merchantId) {
        if (!currentUser.isMemberOfMerchant(merchantId)) {
            throw new TenantAccessDeniedException("Access denied to merchant " + merchantId);
        }
    }

    private void validateAdminPermission(AuthenticatedUser currentUser, UUID merchantId) {
        if (!currentUser.isSystemAdmin() && !currentUser.hasMerchantRole(merchantId, "MERCHANT_ADMIN")) {
            throw new TenantAccessDeniedException("Requires MERCHANT_ADMIN privilege for merchant " + merchantId);
        }
    }
}

package com.mend.service;

import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.PageResponse;
import com.mend.dto.WebhookEventDetailDto;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WebhookQueryService {

    private final WebhookEventRepository webhookEventRepository;

    public WebhookQueryService(WebhookEventRepository webhookEventRepository) {
        this.webhookEventRepository = webhookEventRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<WebhookEventDetailDto> getWebhooks(
            UUID merchantId,
            WebhookEventStatus status,
            int page,
            int size,
            String sortBy,
            String sortOrder,
            AuthenticatedUser currentUser) {

        validateTenantAccess(merchantId, currentUser);

        String effectiveSortBy = (sortBy != null && !sortBy.isBlank()) ? sortBy : "receivedAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size > 0 ? size : 20, Sort.by(direction, effectiveSortBy));

        Page<WebhookEvent> eventPage;
        if (status != null) {
            eventPage = webhookEventRepository.findByMerchantIdAndProcessingStatus(merchantId, status, pageable);
        } else {
            eventPage = webhookEventRepository.findByMerchantId(merchantId, pageable);
        }

        List<WebhookEventDetailDto> dtoList = eventPage.getContent().stream()
                .map(WebhookEventDetailDto::fromEntity)
                .collect(Collectors.toList());

        return PageResponse.of(dtoList, eventPage.getNumber(), eventPage.getSize(), eventPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public WebhookEventDetailDto getWebhook(UUID merchantId, UUID webhookId, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);

        WebhookEvent event = webhookEventRepository.findByMerchantIdAndId(merchantId, webhookId)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook event not found for merchant " + merchantId + " with ID: " + webhookId));

        return WebhookEventDetailDto.fromEntity(event);
    }

    private void validateTenantAccess(UUID merchantId, AuthenticatedUser currentUser) {
        if (merchantId == null) {
            throw new TenantAccessDeniedException("Merchant context is required");
        }
        if (currentUser != null && !currentUser.isSystemAdmin() && !currentUser.isMemberOfMerchant(merchantId)) {
            throw new TenantAccessDeniedException("Access denied for merchant: " + merchantId);
        }
    }
}

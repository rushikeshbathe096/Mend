package com.mend.service;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.dto.ActionIntentDto;
import com.mend.dto.PageResponse;
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
public class RecoveryActionQueryService {

    private final ActionIntentRepository actionIntentRepository;

    public RecoveryActionQueryService(ActionIntentRepository actionIntentRepository) {
        this.actionIntentRepository = actionIntentRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<ActionIntentDto> getRecoveryActions(
            UUID merchantId,
            ActionIntentStatus status,
            int page,
            int size,
            String sortBy,
            String sortOrder,
            AuthenticatedUser currentUser) {

        validateTenantAccess(merchantId, currentUser);

        String effectiveSortBy = (sortBy != null && !sortBy.isBlank()) ? sortBy : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size > 0 ? size : 20, Sort.by(direction, effectiveSortBy));

        Page<ActionIntent> intentPage;
        if (status != null) {
            intentPage = actionIntentRepository.findByMerchantIdAndStatus(merchantId, status, pageable);
        } else {
            intentPage = actionIntentRepository.findByMerchantId(merchantId, pageable);
        }

        List<ActionIntentDto> dtoList = intentPage.getContent().stream()
                .map(ActionIntentDto::fromEntity)
                .collect(Collectors.toList());

        return PageResponse.of(dtoList, intentPage.getNumber(), intentPage.getSize(), intentPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ActionIntentDto getRecoveryAction(UUID merchantId, UUID actionId, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);

        ActionIntent intent = actionIntentRepository.findByMerchantIdAndId(merchantId, actionId)
                .orElseThrow(() -> new ResourceNotFoundException("Recovery action not found for merchant " + merchantId + " with ID: " + actionId));

        return ActionIntentDto.fromEntity(intent);
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

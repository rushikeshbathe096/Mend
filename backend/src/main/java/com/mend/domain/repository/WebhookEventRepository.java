package com.mend.domain.repository;

import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {
    Optional<WebhookEvent> findByExternalEventId(String externalEventId);
    
    List<WebhookEvent> findByMerchantId(UUID merchantId);
    long countByMerchantId(UUID merchantId);

    Page<WebhookEvent> findByMerchantId(UUID merchantId, Pageable pageable);
    Page<WebhookEvent> findByMerchantIdAndProcessingStatus(UUID merchantId, WebhookEventStatus status, Pageable pageable);
    Optional<WebhookEvent> findByMerchantIdAndId(UUID merchantId, UUID id);
    Optional<WebhookEvent> findByMerchantIdAndExternalEventId(UUID merchantId, String externalEventId);
}

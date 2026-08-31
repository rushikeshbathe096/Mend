package com.mend.domain.repository;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.enums.ActionIntentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActionIntentRepository extends JpaRepository<ActionIntent, UUID> {
    Optional<ActionIntent> findByIdempotencyKey(String idempotencyKey);
    List<ActionIntent> findByCampaignId(UUID campaignId);
    List<ActionIntent> findByStatus(ActionIntentStatus status);
}

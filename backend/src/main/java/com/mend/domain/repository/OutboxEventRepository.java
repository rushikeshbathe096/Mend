package com.mend.domain.repository;

import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.enums.OutboxEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("""
            SELECT o FROM OutboxEvent o
            WHERE o.status IN :statuses
              AND o.nextAttemptAt <= :now
            ORDER BY o.createdAt ASC
            """)
    List<OutboxEvent> findDueForPublication(@Param("statuses") Collection<OutboxEventStatus> statuses,
                                            @Param("now") Instant now,
                                            Pageable pageable);

    Optional<OutboxEvent> findByAggregateTypeAndAggregateIdAndEventType(
            String aggregateType, UUID aggregateId, String eventType);

}

package com.mend.domain.repository;

import com.mend.domain.entity.ReplySuggestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReplySuggestionRepository extends JpaRepository<ReplySuggestion, UUID> {

    Optional<ReplySuggestion> findByReplyId(UUID replyId);

    List<ReplySuggestion> findByReplyIdIn(Collection<UUID> replyIds);
}
